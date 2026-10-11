package app.zendiary.backup

import android.content.Context
import app.zendiary.data.settings.Keys
import app.zendiary.data.settings.SettingsStore
import app.zendiary.io.ExportService
import app.zendiary.io.ImportOutcome
import app.zendiary.io.ImportService
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveAuth(private val ctx: Context) {
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata"))).build()

    suspend fun authorize(): AuthorizationResult = Identity.getAuthorizationClient(ctx).authorize(request).await()
}

sealed class BackupOutcome {
    data object Success : BackupOutcome()
    data object NeedsConsent : BackupOutcome()
    data class Failed(val message: String) : BackupOutcome()
}

class DriveBackupManager(
    private val auth: DriveAuth,
    private val api: DriveApi,
    private val exportService: ExportService,
    private val importService: ImportService,
    private val settings: SettingsStore,
    private val cacheDir: File,
) {
    private val keep = 5
    private val prefix = "zendiary-backup-"

    suspend fun backupNow(): BackupOutcome = withContext(Dispatchers.IO) {
        try {
            val token = tokenOrNull() ?: run {
                settings.set(Keys.LAST_ERROR, "Google Drive needs to be reconnected")
                return@withContext BackupOutcome.NeedsConsent
            }
            val file = exportService.buildBackupFile()
            try {
                val name = prefix + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zdb"
                api.upload(token, name, file)
            } finally { file.delete() }
            api.list(token).filter { it.name.startsWith(prefix) }.drop(keep).forEach { runCatching { api.delete(token, it.id) } }
            settings.set(Keys.LAST_BACKUP, System.currentTimeMillis())
            settings.set(Keys.LAST_ERROR, "")
            BackupOutcome.Success
        } catch (e: Exception) {
            settings.set(Keys.LAST_ERROR, e.message ?: "Backup failed")
            BackupOutcome.Failed(e.message ?: "Backup failed")
        }
    }

    suspend fun listBackups(): List<DriveFile> = withContext(Dispatchers.IO) {
        val token = tokenOrNull() ?: error("Reconnect Google Drive first")
        api.list(token).filter { it.name.startsWith(prefix) }
    }

    suspend fun restore(file: DriveFile, password: String?): ImportOutcome = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("restore", ".zdb", cacheDir)
        try {
            val token = tokenOrNull() ?: return@withContext ImportOutcome.Failed("Reconnect Google Drive first")
            api.download(token, file.id, tmp)
            importService.importFile(tmp, file.name, password)
        } catch (e: Exception) {
            ImportOutcome.Failed(e.message ?: "Restore failed")
        } finally { tmp.delete() }
    }

    private suspend fun tokenOrNull(): String? {
        val r = auth.authorize()
        return if (r.hasResolution()) null else r.accessToken
    }
}
