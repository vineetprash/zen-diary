package app.zendiary.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import app.zendiary.MainActivity
import app.zendiary.R
import app.zendiary.ZenApp
import app.zendiary.backup.BackupOutcome
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Notifications {
    const val REMINDER = "reminder"
    const val BACKUP = "backup"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REMINDER, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(BACKUP, "Backups", NotificationManager.IMPORTANCE_LOW))
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

class WorkScheduler(private val ctx: Context) {
    private val wm get() = WorkManager.getInstance(ctx)

    fun scheduleReminder(enabled: Boolean, minutes: Int, append: Boolean = false) {
        if (!enabled) { wm.cancelUniqueWork("reminder"); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork("reminder", if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, req)
    }

    /** freq: 0 off, 1 daily, 2 weekly, 3 monthly */
    fun scheduleBackup(freq: Int, wifiOnly: Boolean) {
        if (freq == 0) { wm.cancelUniqueWork("auto_backup"); return }
        val days = when (freq) { 1 -> 1L; 2 -> 7L; else -> 30L }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()
        val req = PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val messages = listOf(
        "A quiet minute for yourself? Jot down how today felt.",
        "How was your day? Even one line counts.",
        "Take a breath. What's one thing you noticed today?",
        "Your diary is here whenever you're ready.",
    )

    override suspend fun doWork(): Result {
        val c = (applicationContext as ZenApp).container
        val s = c.settings.current()
        if (!s.reminderEnabled) return Result.success()
        val hasToday = c.diary.forDate(LocalDate.now()).isNotEmpty()
        if (!hasToday) Notifications.show(applicationContext, Notifications.REMINDER, 1, "Zen Diary", messages.random())
        c.scheduler.scheduleReminder(true, s.reminderMinutes, append = true)
        return Result.success()
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ZenApp).container
        if (!c.settings.current().driveConnected) return Result.success()
        return when (val r = c.drive.backupNow()) {
            BackupOutcome.Success -> Result.success()
            BackupOutcome.NeedsConsent -> {
                Notifications.show(applicationContext, Notifications.BACKUP, 2, "Backup paused", "Reconnect Google Drive in Settings to resume automatic backups.")
                Result.failure()
            }
            is BackupOutcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
