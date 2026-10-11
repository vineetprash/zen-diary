package app.zendiary.ui.settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.zendiary.AppContainer
import app.zendiary.core.*
import app.zendiary.ui.components.*
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.fragment.app.FragmentActivity
import app.zendiary.backup.BackupOutcome
import app.zendiary.backup.DriveFile
import app.zendiary.data.settings.AppSettings
import app.zendiary.data.settings.Keys
import app.zendiary.io.ImportOutcome
import app.zendiary.lock.AppLock
import com.google.android.gms.auth.api.identity.Identity
import java.text.DateFormat
import java.util.Date

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val tags = c.diary.observeTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)
    val pendingImport = MutableStateFlow<android.net.Uri?>(null)
    val pendingRestore = MutableStateFlow<DriveFile?>(null)
    val passwordWrong = MutableStateFlow(false)
    val driveBackups = MutableStateFlow<List<DriveFile>?>(null)

    fun <T> set(key: Preferences.Key<T>, v: T) = viewModelScope.launch { c.settings.set(key, v) }
    fun setReminder(on: Boolean, minutes: Int) = viewModelScope.launch {
        c.settings.set(Keys.REMINDER_ON, on); c.settings.set(Keys.REMINDER_MIN, minutes); c.scheduler.scheduleReminder(on, minutes)
    }
    fun setAutoBackup(freq: Int, wifi: Boolean) = viewModelScope.launch {
        c.settings.set(Keys.BACKUP_FREQ, freq); c.settings.set(Keys.BACKUP_WIFI, wifi); c.scheduler.scheduleBackup(freq, wifi)
    }
    fun disconnectDrive() = viewModelScope.launch {
        c.settings.set(Keys.DRIVE, false); c.settings.set(Keys.BACKUP_FREQ, 0); c.scheduler.scheduleBackup(0, true)
    }
    fun setBackupPassword(pw: String?) = viewModelScope.launch { c.settings.setBackupPassword(pw) }

    private fun <T> work(label: String, block: suspend () -> T, done: (T) -> String) {
        c.scope.launch {
            busy.value = label
            message.value = try { done(block()) } catch (e: Exception) { "Failed: ${e.message}" }
            busy.value = null
        }
    }

    fun export(uri: android.net.Uri, exporterId: String, images: Boolean) =
        work("Exporting…", { c.exportService.exportTo(uri, c.exporters.byId(exporterId), images) }) { "Export finished." }

    fun import(uri: android.net.Uri, password: String?) {
        c.scope.launch {
            busy.value = "Importing…"
            message.value = when (val r = c.importService.importUri(uri, password)) {
                is ImportOutcome.Done -> { pendingImport.value = null; summary(r) }
                is ImportOutcome.NeedsPassword -> { pendingImport.value = uri; passwordWrong.value = r.wrong; null }
                is ImportOutcome.Failed -> { pendingImport.value = null; "Import failed: ${r.message}" }
            }
            busy.value = null
        }
    }

    private fun summary(r: ImportOutcome.Done): String = buildString {
        append("${r.format}: imported ${r.result.imported} entries")
        if (r.result.skipped > 0) append(", skipped ${r.result.skipped} already present")
        if (r.result.images > 0) append(", ${r.result.images} photos")
        if (r.result.imagesMissing > 0) append(" (${r.result.imagesMissing} photos missing)")
        append(".")
        r.result.warnings.take(5).forEach { append("\n• $it") }
    }

    fun backupNow() = work("Backing up to Drive…", { c.drive.backupNow() }) {
        when (it) { BackupOutcome.Success -> "Backup uploaded to your Google Drive."; BackupOutcome.NeedsConsent -> "Please reconnect Google Drive."; is BackupOutcome.Failed -> "Backup failed: ${it.message}" }
    }

    fun loadDriveBackups() = viewModelScope.launch {
        busy.value = "Loading backups…"
        try { driveBackups.value = c.drive.listBackups() } catch (e: Exception) { message.value = "Couldn't list backups: ${e.message}" }
        busy.value = null
    }

    fun restore(f: DriveFile, password: String?) {
        c.scope.launch {
            busy.value = "Restoring…"
            message.value = when (val r = c.drive.restore(f, password)) {
                is ImportOutcome.Done -> { pendingRestore.value = null; summary(r) }
                is ImportOutcome.NeedsPassword -> { pendingRestore.value = f; passwordWrong.value = r.wrong; null }
                is ImportOutcome.Failed -> { pendingRestore.value = null; "Restore failed: ${r.message}" }
            }
            busy.value = null
        }
    }

    fun addTag(n: String) = viewModelScope.launch { c.diary.addTag(n) }
    fun renameTag(id: Long, n: String) = viewModelScope.launch { c.diary.renameTag(id, n) }
    fun deleteTag(id: Long) = viewModelScope.launch { c.diary.deleteTag(id) }
}

@Composable private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    SoftCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
}
@Composable private fun SwitchRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked, onChange, enabled = enabled)
    }
}
@Composable private fun ClickRow(title: String, sub: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title); sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val vm = containerViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    val pwWrong by vm.passwordWrong.collectAsStateWithLifecycle()
    val backups by vm.driveBackups.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showExport by remember { mutableStateOf(false) }
    var exportId by remember { mutableStateOf("zdb") }
    var exportImages by remember { mutableStateOf(true) }
    var showTags by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    var showFreq by remember { mutableStateOf(false) }
    var showTimeout by remember { mutableStateOf(false) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> uri?.let { vm.export(it, exportId, exportImages) } }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it, null) } }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data) }
            .onSuccess { vm.set(Keys.DRIVE, true) }.onFailure { vm.message.value = "Google Drive access wasn't granted." }
    }
    fun connectDrive() = scope.launch {
        try {
            val r = vm.c.driveAuth.authorize()
            if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else vm.set(Keys.DRIVE, true)
        } catch (e: Exception) { vm.message.value = "Couldn't connect to Google: ${e.message}\n(Have you set up the OAuth client? See README.)" }
    }

    ZenScaffold(hasBottomBar = true, topBar = { TopAppBar(title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) }, colors = zenTopBarColors()) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Group("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "Light", "Dark").forEachIndexed { i, l -> FilterChip(s.themeMode == i, { vm.set(Keys.THEME, i) }, { Text(l) }) }
                }
            }
            Group("Privacy & security") {
                SwitchRow("App lock", "Fingerprint / face, or your phone PIN, pattern or password", s.lockEnabled) { on ->
                    val act = ctx as? FragmentActivity ?: return@SwitchRow
                    if (!on) { vm.set(Keys.LOCK, false); return@SwitchRow }
                    when (AppLock.canAuthenticate(ctx)) {
                        BiometricManager.BIOMETRIC_SUCCESS ->
                            AppLock.prompt(act, "Confirm to enable lock", null, { vm.set(Keys.LOCK, true) }, { vm.message.value = it })
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                            vm.message.value = "Set up a screen lock or fingerprint on your phone first."
                            runCatching {
                                ctx.startActivity(if (Build.VERSION.SDK_INT >= 30) Intent(AndroidSettings.ACTION_BIOMETRIC_ENROLL).putExtra(AndroidSettings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AppLock.authenticators)
                                else Intent(AndroidSettings.ACTION_SECURITY_SETTINGS))
                            }
                        }
                        else -> vm.message.value = "This device can't provide a secure lock."
                    }
                }
                if (s.lockEnabled) ClickRow("Lock after leaving the app", when (s.lockTimeoutSec) { 0 -> "Immediately"; 30 -> "30 seconds"; 60 -> "1 minute"; else -> "5 minutes" }) { showTimeout = true }
                SwitchRow("Hide in app switcher & block screenshots", null, s.hideInRecents) { vm.set(Keys.HIDE_RECENTS, it) }
                Text("No analytics, ads or trackers. Entries and photos are encrypted on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Group("Journaling") {
                SwitchRow("Daily reminder", if (s.reminderEnabled) "At ${s.reminderMinutes.asClock()}" else "Off", s.reminderEnabled) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    vm.setReminder(on, s.reminderMinutes)
                }
                if (s.reminderEnabled) ClickRow("Reminder time", s.reminderMinutes.asClock()) { showTime = true }
                SwitchRow("Breathing moment after saving", "A calm 1-minute screen", s.breathing) { vm.set(Keys.BREATHING, it) }
                SwitchRow("Grace days for streaks", "One missed day per week won't break your streak", s.graceStreak) { vm.set(Keys.GRACE, it) }
                SwitchRow("Period tracker", "Optional, private, kept on this device", s.cycleEnabled) { vm.set(Keys.CYCLE, it) }
                ClickRow("Manage activities", "${tags.size} activities") { showTags = true }
            }
            Group("Export & import") {
                ClickRow("Export entries", "Backup (.zdb), CSV, text or PDF - with photos") { showExport = true }
                ClickRow("Import entries", "Zen Diary files, Daylio backup (.daylio) or Daylio CSV") { openDoc.launch(arrayOf("*/*")) }
                ClickRow("Backup password", if (s.hasBackupPassword) "Set - backups are encrypted" else "Not set - tap to encrypt backups") { showPw = true }
            }
            Group("Google Drive backup") {
                if (!s.driveConnected) {
                    Text("Backups go straight from your phone to your own Google Drive (a private app folder). No server in between.", style = MaterialTheme.typography.bodySmall)
                    Button({ connectDrive() }, Modifier.padding(top = 8.dp)) { Text("Connect Google Drive") }
                } else {
                    ClickRow("Back up now", if (s.lastBackupAt > 0) "Last: ${DateFormat.getDateTimeInstance().format(Date(s.lastBackupAt))}" else "No backup yet") { vm.backupNow() }
                    if (s.lastBackupError.isNotEmpty()) Text(s.lastBackupError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    ClickRow("Automatic backup", listOf("Off", "Daily", "Weekly", "Monthly")[s.autoBackupFreq]) { showFreq = true }
                    SwitchRow("Wi-Fi only", null, s.backupWifiOnly, s.autoBackupFreq != 0) { vm.setAutoBackup(s.autoBackupFreq, it) }
                    ClickRow("Restore from Drive") { vm.loadDriveBackups() }
                    ClickRow("Disconnect", "Stops automatic backups. Revoke access fully in your Google Account settings.") { vm.disconnectDrive() }
                }
            }
            Group("About") { Text("Zen Diary - offline-first, private by design.", style = MaterialTheme.typography.bodySmall) }
        }
    }

    // ---- dialogs
    if (showExport) AlertDialog({ showExport = false }, title = { Text("Export") }, text = {
        Column {
            vm.c.exporters.all.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { exportId = e.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(exportId == e.id, { exportId = e.id })
                    Column { Text(e.label); Text(e.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (exportId != "zdb") SwitchRow("Include photos", null, exportImages) { exportImages = it }
        }
    }, confirmButton = {
        TextButton({
            showExport = false
            val e = vm.c.exporters.byId(exportId); val imgs = exportImages || exportId == "zdb"
            createDoc.launch("zen-diary-${java.time.LocalDate.now()}.${e.extension(imgs)}")
        }) { Text("Export") }
    }, dismissButton = { TextButton({ showExport = false }) { Text("Cancel") } })

    if (showTime) {
        val st = rememberTimePickerState(s.reminderMinutes / 60, s.reminderMinutes % 60, true)
        AlertDialog({ showTime = false }, title = { Text("Reminder time") }, text = { TimePicker(st) },
            confirmButton = { TextButton({ vm.setReminder(true, st.hour * 60 + st.minute); showTime = false }) { Text("OK") } },
            dismissButton = { TextButton({ showTime = false }) { Text("Cancel") } })
    }
    if (showFreq) AlertDialog({ showFreq = false }, title = { Text("Automatic backup") }, text = {
        Column { listOf("Off", "Daily", "Weekly", "Monthly").forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().clickable { vm.setAutoBackup(i, s.backupWifiOnly); showFreq = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(s.autoBackupFreq == i, null); Spacer(Modifier.width(8.dp)); Text(l) }
        } }
    }, confirmButton = {})
    if (showTimeout) AlertDialog({ showTimeout = false }, title = { Text("Lock after") }, text = {
        Column { listOf(0 to "Immediately", 30 to "30 seconds", 60 to "1 minute", 300 to "5 minutes").forEach { (v, l) ->
            Row(Modifier.fillMaxWidth().clickable { vm.set(Keys.LOCK_TIMEOUT, v); showTimeout = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(s.lockTimeoutSec == v, null); Spacer(Modifier.width(8.dp)); Text(l) }
        } }
    }, confirmButton = {})
    if (showPw) {
        var pw by remember { mutableStateOf("") }
        AlertDialog({ showPw = false }, title = { Text("Backup password") }, text = {
            Column {
                Text("Encrypts .zdb backups and Drive backups. If you forget it, those backups cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pw, { pw = it }, label = { Text("New password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        }, confirmButton = { TextButton({ vm.setBackupPassword(pw.ifBlank { null }); showPw = false }, enabled = pw.length >= 6) { Text("Save") } },
            dismissButton = { Row { if (s.hasBackupPassword) TextButton({ vm.setBackupPassword(null); showPw = false }) { Text("Remove") }; TextButton({ showPw = false }) { Text("Cancel") } } })
    }
    if (showTags) TagsDialog(tags.map { it.id to it.name }, vm) { showTags = false }

    // password prompt for encrypted import / restore
    if (pendingImport != null || pendingRestore != null) {
        var pw by remember { mutableStateOf("") }
        AlertDialog({ vm.pendingImport.value = null; vm.pendingRestore.value = null }, title = { Text("Backup password") }, text = {
            Column {
                if (pwWrong) Text("That password didn't work.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            }
        }, confirmButton = { TextButton({
            pendingImport?.let { vm.import(it, pw) }; pendingRestore?.let { vm.restore(it, pw) }
        }) { Text("Unlock") } }, dismissButton = { TextButton({ vm.pendingImport.value = null; vm.pendingRestore.value = null }) { Text("Cancel") } })
    }
    backups?.let { list ->
        AlertDialog({ vm.driveBackups.value = null }, title = { Text("Restore from Drive") }, text = {
            if (list.isEmpty()) Text("No backups found.")
            else Column(Modifier.verticalScroll(rememberScrollState())) { list.forEach { f -> ClickRow(f.name.removePrefix("zendiary-backup-").removeSuffix(".zdb"), "${f.size / 1024} KB") { vm.driveBackups.value = null; vm.restore(f, null) } } }
        }, confirmButton = { TextButton({ vm.driveBackups.value = null }) { Text("Close") } })
    }
    busy?.let { AlertDialog({}, title = { Text(it) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {}) }
    message?.let { AlertDialog({ vm.message.value = null }, text = { Text(it) }, confirmButton = { TextButton({ vm.message.value = null }) { Text("OK") } }) }
}

@Composable
private fun TagsDialog(tags: List<Pair<Long, String>>, vm: SettingsViewModel, onClose: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    AlertDialog(onClose, title = { Text("Activities") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true, label = { Text("New activity") })
                IconButton({ vm.addTag(newName); newName = "" }) { Icon(Icons.Rounded.Add, "Add") }
            }
            tags.forEach { (id, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    renaming?.takeIf { it.first == id }?.let { r ->
                        OutlinedTextField(r.second, { renaming = id to it }, Modifier.weight(1f), singleLine = true)
                        IconButton({ vm.renameTag(id, r.second); renaming = null }) { Icon(Icons.Rounded.Check, "Save") }
                    } ?: run {
                        Text(name, Modifier.weight(1f))
                        IconButton({ renaming = id to name }) { Icon(Icons.Rounded.Edit, "Rename") }
                        IconButton({ vm.deleteTag(id) }) { Icon(Icons.Rounded.Delete, "Delete") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("Done") } })
}
