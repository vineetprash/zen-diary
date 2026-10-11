package app.zendiary.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.zendiary.data.crypto.KeyVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("zen_settings")

data class AppSettings(
    val themeMode: Int = 0,               // 0 system, 1 light, 2 dark
    val lockEnabled: Boolean = false,
    val lockTimeoutSec: Int = 30,
    val hideInRecents: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 21 * 60,
    val breathing: Boolean = true,
    val cycleEnabled: Boolean = false,
    val graceStreak: Boolean = true,
    val driveConnected: Boolean = false,
    val autoBackupFreq: Int = 0,          // 0 off, 1 daily, 2 weekly, 3 monthly
    val backupWifiOnly: Boolean = true,
    val hasBackupPassword: Boolean = false,
    val lastBackupAt: Long = 0,
    val lastBackupError: String = "",
    val summaryDismissed: String = "",
)

object Keys {
    val THEME = intPreferencesKey("theme")
    val LOCK = booleanPreferencesKey("lock")
    val LOCK_TIMEOUT = intPreferencesKey("lock_timeout")
    val HIDE_RECENTS = booleanPreferencesKey("hide_recents")
    val REMINDER_ON = booleanPreferencesKey("reminder_on")
    val REMINDER_MIN = intPreferencesKey("reminder_min")
    val BREATHING = booleanPreferencesKey("breathing")
    val CYCLE = booleanPreferencesKey("cycle")
    val GRACE = booleanPreferencesKey("grace")
    val DRIVE = booleanPreferencesKey("drive")
    val BACKUP_FREQ = intPreferencesKey("backup_freq")
    val BACKUP_WIFI = booleanPreferencesKey("backup_wifi")
    val BACKUP_PW = stringPreferencesKey("backup_pw")
    val LAST_BACKUP = longPreferencesKey("last_backup")
    val LAST_ERROR = stringPreferencesKey("last_error")
    val SUMMARY_DISMISSED = stringPreferencesKey("summary_dismissed")
}

class SettingsStore(private val ctx: Context, private val vault: KeyVault) {
    val flow: Flow<AppSettings> = ctx.ds.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME] ?: 0,
            lockEnabled = p[Keys.LOCK] ?: false,
            lockTimeoutSec = p[Keys.LOCK_TIMEOUT] ?: 30,
            hideInRecents = p[Keys.HIDE_RECENTS] ?: true,
            reminderEnabled = p[Keys.REMINDER_ON] ?: false,
            reminderMinutes = p[Keys.REMINDER_MIN] ?: (21 * 60),
            breathing = p[Keys.BREATHING] ?: true,
            cycleEnabled = p[Keys.CYCLE] ?: false,
            graceStreak = p[Keys.GRACE] ?: true,
            driveConnected = p[Keys.DRIVE] ?: false,
            autoBackupFreq = p[Keys.BACKUP_FREQ] ?: 0,
            backupWifiOnly = p[Keys.BACKUP_WIFI] ?: true,
            hasBackupPassword = !p[Keys.BACKUP_PW].isNullOrEmpty(),
            lastBackupAt = p[Keys.LAST_BACKUP] ?: 0,
            lastBackupError = p[Keys.LAST_ERROR] ?: "",
            summaryDismissed = p[Keys.SUMMARY_DISMISSED] ?: "",
        )
    }

    suspend fun current(): AppSettings = flow.first()
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ctx.ds.edit { it[key] = value } }

    suspend fun setBackupPassword(pw: String?) {
        ctx.ds.edit { if (pw.isNullOrEmpty()) it.remove(Keys.BACKUP_PW) else it[Keys.BACKUP_PW] = vault.encryptString(pw) }
    }

    suspend fun backupPassword(): String? =
        ctx.ds.data.first()[Keys.BACKUP_PW]?.let { vault.decryptString(it) }
}
