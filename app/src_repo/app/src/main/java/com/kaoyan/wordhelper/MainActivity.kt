package com.kaoyan.wordhelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.kaoyan.wordhelper.ui.navigation.AppNavigation
import com.kaoyan.wordhelper.ui.screen.StartupScreen
import com.kaoyan.wordhelper.ui.theme.KaoyanWordTheme
import com.kaoyan.wordhelper.data.repository.DarkMode
import com.kaoyan.wordhelper.data.repository.UserSettings
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 自动同步用：只在自己进程里跑，不阻塞界面，也不碰用户别的服务。 */
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastAutoSyncAt = 0L

    /**
     * 回到前台顺手把学习记录推到电脑（1.4.5）。
     *
     * 以前只能进「我的 → 数据管理」手点；现在只要填过电脑地址、且 5 分钟内没同步过，
     * 就静默跑一次。没填地址 / 电脑没开时 syncOnce 会很快返回，不弹任何东西。
     */
    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt < AUTO_SYNC_MIN_INTERVAL_MS) return
        lastAutoSyncAt = now
        val app = application as KaoyanWordApp
        autoSyncScope.launch {
            runCatching { app.syncRepository.syncOnce() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides this@MainActivity) {
                val settingsRepository = (application as KaoyanWordApp).settingsRepository
                val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                    lifecycleOwner = this@MainActivity,
                    initialValue = UserSettings()
                )
                val darkTheme = when (settings.darkMode) {
                    DarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
                    DarkMode.LIGHT -> false
                    DarkMode.DARK -> true
                }
                KaoyanWordTheme(darkTheme = darkTheme, fontScale = settings.fontScale) {
                    var startupVisible by rememberSaveable { mutableStateOf(true) }
                    LaunchedEffect(Unit) {
                        delay(650)
                        startupVisible = false
                    }
                    if (startupVisible) {
                        StartupScreen()
                    } else {
                        AppNavigation(
                            immersiveFullscreenEnabled = settings.immersiveFullscreenEnabled
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** 自动同步最短间隔：5 分钟。切几次页就同步几次没必要，队列本身是持久的。 */
        const val AUTO_SYNC_MIN_INTERVAL_MS = 5 * 60_000L
    }
}
