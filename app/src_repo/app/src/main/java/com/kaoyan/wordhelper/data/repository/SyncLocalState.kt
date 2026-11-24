package com.kaoyan.wordhelper.data.repository

import com.kaoyan.wordhelper.data.dao.SyncDao
import com.kaoyan.wordhelper.data.entity.SyncState
import java.util.UUID

internal class SyncLocalState(private val syncDao: SyncDao) {
    suspend fun ensureState(): SyncState {
        val existing = syncDao.state()
        if (existing != null && existing.deviceId.isNotBlank()) return existing
        // 第一次同步才生成设备 id：换手机/清数据会换一个新 id，服务端据此区分来源。
        val deviceId = UUID.randomUUID().toString()
        val state = (existing ?: SyncState()).copy(deviceId = deviceId)
        syncDao.putState(state)
        return state
    }

    /** 1.4.7 验收 B2：换电脑/换地址后必须从头拉，旧游标会静默漏行；保存连接串时立刻清，进程被杀也不会漏。 */
    suspend fun resetCursorForEndpointChange() {
        syncDao.state()?.let { st ->
            if (st.serverRev != 0L) syncDao.putState(st.copy(serverRev = 0L))
        }
    }
}
