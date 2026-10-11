package app.zendiary.io

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.zendiary.data.CycleRepository
import app.zendiary.data.DiaryRepository
import app.zendiary.data.ImageStore
import app.zendiary.data.crypto.BackupCrypto
import app.zendiary.data.crypto.WrongPasswordException
import app.zendiary.data.settings.SettingsStore
import app.zendiary.domain.ExportData
import app.zendiary.io.export.ExportContext
import app.zendiary.io.export.Exporter
import app.zendiary.io.export.ExporterRegistry
import app.zendiary.io.export.ZenBackupExporter
import app.zendiary.io.importer.ImporterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExportService(
    private val ctx: Context,
    private val diary: DiaryRepository,
    private val cycle: CycleRepository,
    private val images: ImageStore,
    private val settings: SettingsStore,
    val registry: ExporterRegistry,
) {
    private suspend fun collect() = ExportData(diary.exportAll(), cycle.all())

    suspend fun exportTo(uri: Uri, exporter: Exporter, includeImages: Boolean) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("export", ".tmp", ctx.cacheDir)
        try {
            tmp.outputStream().buffered().use { exporter.export(ExportContext(ctx, collect(), images, includeImages), it) }
            val pw = if (exporter.supportsPassword) settings.backupPassword() else null
            ctx.contentResolver.openOutputStream(uri, "w")!!.buffered().use { out ->
                tmp.inputStream().use { if (pw != null) BackupCrypto.encrypt(it, out, pw) else it.copyTo(out) }
            }
        } finally { tmp.delete() }
    }

    /** Builds a .zdb (encrypted if a backup password is set) for cloud backup. Caller deletes the file. */
    suspend fun buildBackupFile(): File = withContext(Dispatchers.IO) {
        val raw = File.createTempFile("backup", ".raw", ctx.cacheDir)
        try {
            raw.outputStream().buffered().use { ZenBackupExporter().export(ExportContext(ctx, collect(), images, true), it) }
            val pw = settings.backupPassword() ?: return@withContext raw.copyTo(File.createTempFile("backup", ".zdb", ctx.cacheDir), true)
            val enc = File.createTempFile("backup", ".zdb", ctx.cacheDir)
            raw.inputStream().use { i -> enc.outputStream().buffered().use { BackupCrypto.encrypt(i, it, pw) } }
            enc
        } finally { raw.delete() }
    }
}

sealed class ImportOutcome {
    data class Done(val result: ImportResult, val format: String) : ImportOutcome()
    data class NeedsPassword(val wrong: Boolean) : ImportOutcome()
    data class Failed(val message: String) : ImportOutcome()
}

class ImportService(
    private val ctx: Context,
    private val importers: ImporterRegistry,
    private val diary: DiaryRepository,
    private val cycle: CycleRepository,
) {
    suspend fun importUri(uri: Uri, password: String?): ImportOutcome = withContext(Dispatchers.IO) {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "import"
        val tmp = File.createTempFile("import", ".tmp", ctx.cacheDir)
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            importFile(tmp, name, password)
        } catch (e: Exception) {
            ImportOutcome.Failed(e.message ?: "Could not read this file")
        } finally { tmp.delete() }
    }

    suspend fun importFile(file: File, name: String, password: String?): ImportOutcome = withContext(Dispatchers.IO) {
        var work = file
        var decrypted: File? = null
        try {
            if (BackupCrypto.isEncrypted(file)) {
                if (password == null) return@withContext ImportOutcome.NeedsPassword(false)
                decrypted = File.createTempFile("dec", ".tmp", ctx.cacheDir)
                try {
                    file.inputStream().buffered().use { i -> decrypted.outputStream().buffered().use { BackupCrypto.decrypt(i, it, password) } }
                } catch (e: WrongPasswordException) { return@withContext ImportOutcome.NeedsPassword(true) }
                work = decrypted
            }
            val importer = importers.detect(work, name)
                ?: return@withContext ImportOutcome.Failed("Unrecognised file. Supported: ${importers.supportedLabels}.")
            importer.read(work).use { batch ->
                val result = diary.importBatch(batch)
                if (batch.cycle.isNotEmpty()) cycle.importDays(batch.cycle)
                ImportOutcome.Done(result, importer.label)
            }
        } catch (e: Exception) {
            ImportOutcome.Failed(e.message ?: "Import failed")
        } finally { decrypted?.delete() }
    }
}
