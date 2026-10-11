package app.zendiary.backup

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class DriveFile(val id: String, val name: String, val size: Long, val modified: String)

/**
 * Minimal Google Drive v3 REST client (no heavy SDK). Talks directly from the phone to Google with the
 * user's own OAuth token - no intermediary server. Files live in the hidden per-app `appDataFolder`.
 */
class DriveApi {
    private val base = "https://www.googleapis.com/drive/v3/files"

    private fun conn(url: String, token: String, method: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000; readTimeout = 60_000
        }

    private fun HttpURLConnection.ok(): HttpURLConnection {
        if (responseCode !in 200..299) {
            val err = runCatching { errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw IOException("Drive error $responseCode ${err?.take(200).orEmpty()}")
        }
        return this
    }

    fun upload(token: String, name: String, file: File, mime: String = "application/octet-stream"): String {
        val init = conn("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable", token, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", file.length().toString())
        }
        init.outputStream.use {
            it.write(JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString().toByteArray())
        }
        init.ok()
        val location = init.getHeaderField("Location") ?: throw IOException("Drive gave no upload URL")
        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Content-Type", mime)
            connectTimeout = 20_000; readTimeout = 180_000
        }
        file.inputStream().use { i -> put.outputStream.use { o -> i.copyTo(o) } }
        put.ok()
        return JSONObject(put.inputStream.bufferedReader().readText()).getString("id")
    }

    fun list(token: String): List<DriveFile> {
        val url = Uri.parse(base).buildUpon()
            .appendQueryParameter("spaces", "appDataFolder")
            .appendQueryParameter("fields", "files(id,name,size,modifiedTime)")
            .appendQueryParameter("orderBy", "modifiedTime desc")
            .appendQueryParameter("pageSize", "100").build().toString()
        val c = conn(url, token, "GET").ok()
        val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            DriveFile(it.getString("id"), it.getString("name"), it.optString("size", "0").toLongOrNull() ?: 0, it.optString("modifiedTime"))
        }
    }

    fun download(token: String, id: String, dest: File) {
        val c = conn("$base/$id?alt=media", token, "GET").ok()
        c.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
    }

    fun delete(token: String, id: String) { conn("$base/$id", token, "DELETE").ok().responseCode }
}
