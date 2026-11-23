package com.kaoyan.wordhelper.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 网络可用性（1.4.6.1）：只服务于「网络恢复后自动补发一次同步」。
 *
 * 用系统回调而不是定时轮询 —— 断网时一直轮询既费电又没必要；App 被杀后回调自然消失，
 * 下次打开时的启动同步会补上，不需要后台常驻。
 */
object NetworkAvailability {

    /**
     * 当前有没有可用的默认网络。
     * 注意用 NET_CAPABILITY_INTERNET 而不是 VALIDATED：家里的 Wi-Fi 就算没连外网，
     * 手机和电脑仍在同一个局域网里，卡背/重写照样能通。
     * 系统服务拿不到时按「有网」处理，宁可在请求里失败，也别把能连的情况判成断网。
     */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 默认网络身份发生变化时触发补同步，包含蜂窝和 Wi-Fi 间的直接切换。 */
    fun observe(context: Context): Flow<Network?> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        fun refresh() {
            trySend(manager.activeNetwork?.takeIf {
                manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            })
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        refresh()
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}
