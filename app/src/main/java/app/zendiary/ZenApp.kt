package app.zendiary

import android.app.Application
import app.zendiary.data.EncryptedImage
import app.zendiary.data.EncryptedImageFetcher
import app.zendiary.data.EncryptedImageKeyer
import app.zendiary.work.Notifications
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.launch

class ZenApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            val s = container.settings.current()
            container.scheduler.scheduleReminder(s.reminderEnabled, s.reminderMinutes)
            container.scheduler.scheduleBackup(if (s.driveConnected) s.autoBackupFreq else 0, s.backupWifiOnly)
        }
    }

    // Images are encrypted on disk, so Coil must never write decoded copies to its own disk cache.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(EncryptedImageKeyer, EncryptedImage::class.java)
            add(EncryptedImageFetcher.Factory(container.imageStore), EncryptedImage::class.java)
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { null }
        .crossfade(true)
        .build()
}
