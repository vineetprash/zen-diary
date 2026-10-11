package app.zendiary

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.zendiary.core.LocalContainer
import app.zendiary.data.settings.AppSettings
import app.zendiary.lock.AppLock
import app.zendiary.ui.nav.ZenNav
import app.zendiary.ui.theme.ZenTheme

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var lastStop = 0L
    private var lockEnabled = false
    private var timeoutMs = 30_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ZenApp).container
        setContent {
            val s by container.settings.flow.collectAsState(initial = null as AppSettings?)
            val cfg = s
            LaunchedEffect(cfg) {
                if (cfg != null) {
                    lockEnabled = cfg.lockEnabled; timeoutMs = cfg.lockTimeoutSec * 1000L
                    if (!cfg.lockEnabled) locked = false
                    if (cfg.hideInRecents) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            val dark = when (cfg?.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            ZenTheme(dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (cfg != null) {
                        val showLock = cfg.lockEnabled && locked
                        CompositionLocalProvider(LocalContainer provides container) {
                            Box(if (showLock) Modifier.clearAndSetSemantics { } else Modifier) { ZenNav() }
                        }
                        if (showLock) LockOverlay(onUnlocked = { locked = false })
                    }
                }
            }
        }
    }

    @Composable
    private fun LockOverlay(onUnlocked: () -> Unit) {
        fun ask() = AppLock.prompt(this, "Unlock Zen Diary", null, onUnlocked, { })
        LaunchedEffect(Unit) {
            // If the phone has no secure lock any more there is nothing to authenticate against.
            if (!AppLock.isAvailable(this@MainActivity)) onUnlocked() else ask()
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(enabled = false) {},
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Zen Diary is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(24.dp))
            Button(::ask) { Text("Unlock") }
        }
    }

    override fun onStop() { super.onStop(); lastStop = SystemClock.elapsedRealtime() }
    override fun onStart() {
        super.onStart()
        if (lockEnabled && lastStop != 0L && SystemClock.elapsedRealtime() - lastStop >= timeoutMs) locked = true
    }
}
