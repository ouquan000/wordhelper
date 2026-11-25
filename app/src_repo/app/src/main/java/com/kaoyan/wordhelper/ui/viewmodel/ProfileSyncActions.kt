package com.kaoyan.wordhelper.ui.viewmodel

import android.app.Application
import com.kaoyan.wordhelper.data.repository.SyncStatusStore
import com.kaoyan.wordhelper.sync.BackgroundSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.kaoyan.wordhelper.data.repository.SyncRepository
import com.kaoyan.wordhelper.data.repository.SettingsRepository

internal class ProfileSyncActions(
    private val application: Application,
    private val scope: CoroutineScope,
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository
) {
    private val _syncPending = MutableStateFlow(0)

    /** 还没发出去的进度条数；0 = 手机就是最新的。 */
    val syncPending: StateFlow<Int> = _syncPending.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    fun refreshSyncPending() {
        scope.launch {
            _syncPending.value = runCatching { syncRepository.pendingCount() }.getOrDefault(0)
        }
    }

    /** 手动同步一次：先拉后推再拉；冲突按最后复习时间判，不弹窗。 */
    fun syncLearningRecords() {
        if (_syncing.value) return
        _syncing.value = true
        scope.launch {
            val result = syncRepository.syncOnce()
            _syncMessage.value = result.getOrElse { it.message ?: "同步失败，稍后再试" }
            _syncPending.value = runCatching { syncRepository.pendingCount() }.getOrDefault(0)
            _syncing.value = false
        }
    }

    /** 卡背结构地址（1.4.2）；留空就只用手机本地结构。 */
    fun updateCardBackUrl(url: String) {
        scope.launch {
            settingsRepository.updateCardBackUrl(url)
            // 换地址后旧地址的状态不该再挂在状态页上。
            SyncStatusStore.reset()
            // 1.4.7 验收 B2：立刻清游标，别等下一次同步（进程随后被杀也不会漏）。
            runCatching { syncRepository.resetCursorForEndpointChange() }
            // 1.4.7.2 W4：保存地址后立刻注册/撤销后台任务，不用等下次冷启动。
            runCatching { BackgroundSync.ensure(application) }
        }
    }

    /** 电脑端口令（1.4.4 安全收口）；空 = 不带头，电脑本机访问才够用。 */
    fun updateCardBackToken(token: String) {
        scope.launch {
            settingsRepository.updateCardBackToken(token)
            SyncStatusStore.reset()
            runCatching { syncRepository.resetCursorForEndpointChange() }
            runCatching { BackgroundSync.ensure(application) }
        }
    }

    /** 1.4.7.2 W4-B：同 WiFi 自动直连开关；开启后下一轮同步才会重新发现。 */
    fun updateCardBackLanAuto(enabled: Boolean) {
        scope.launch {
            settingsRepository.updateCardBackLanAuto(enabled)
            runCatching { syncRepository.resetCursorForEndpointChange() }
        }
    }
}
