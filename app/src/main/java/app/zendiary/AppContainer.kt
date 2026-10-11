package app.zendiary

import android.app.Application
import app.zendiary.backup.DriveApi
import app.zendiary.backup.DriveAuth
import app.zendiary.backup.DriveBackupManager
import app.zendiary.data.CycleRepository
import app.zendiary.data.DiaryRepository
import app.zendiary.data.ImageStore
import app.zendiary.data.crypto.KeyVault
import app.zendiary.data.db.ZenDatabase
import app.zendiary.data.settings.SettingsStore
import app.zendiary.domain.StreakCalculator
import app.zendiary.domain.StreakInfo
import app.zendiary.io.ExportService
import app.zendiary.io.ImportService
import app.zendiary.io.export.*
import app.zendiary.io.importer.*
import app.zendiary.core.toLocalDate
import app.zendiary.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Tiny manual DI container - everything is wired here, so swapping/adding implementations is one line. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keyVault = KeyVault(app)
    val settings = SettingsStore(app, keyVault)
    val db: ZenDatabase by lazy { ZenDatabase.create(app, keyVault) }
    val imageStore = ImageStore(app, keyVault)
    val diary by lazy { DiaryRepository(db, imageStore) }
    val cycle by lazy { CycleRepository(db) }
    val scheduler = WorkScheduler(app)

    // Add a format = add one line here.
    val exporters = ExporterRegistry(listOf(ZenBackupExporter(), CsvExporter(), TxtExporter(), PdfExporter()))
    val importers by lazy {
        ImporterRegistry(
            listOf(
                ZenBackupImporter(), DaylioBackupImporter(), DaylioCsvImporter(),
                ZenCsvImporter(), ZenTxtImporter(), ZenPdfImporter(app.cacheDir),
            )
        )
    }
    val exportService by lazy { ExportService(app, diary, cycle, imageStore, settings, exporters) }
    val importService by lazy { ImportService(app, importers, diary, cycle) }

    val driveAuth = DriveAuth(app)
    val drive by lazy { DriveBackupManager(driveAuth, DriveApi(), exportService, importService, settings, app.cacheDir) }

    val streaks: Flow<StreakInfo> by lazy {
        combine(diary.observeMoodRows(), settings.flow.map { it.graceStreak }.distinctUntilChanged()) { rows, grace ->
            val byDay = rows.groupBy { it.date.toLocalDate() }.mapValues { (_, l) -> l.map { it.mood }.average().toFloat() }
            StreakCalculator.compute(byDay.keys, LocalDate.now(), grace, byDay)
        }.flowOn(Dispatchers.Default)
    }
}
