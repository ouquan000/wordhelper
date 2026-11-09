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

    /**
     * 网络可用性变化。冷流：每次 collect 注册一次系统回调，collect 结束（awaitClose）自动注销。
     * 先发一次当前状态，之后只在真的变化时发（distinctUntilChanged），
     * 系统连发多次回调时用 conflate 丢掉中间态。
     */
    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            trySend(true)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(isOnline(context))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        // 没有 ACCESS_NETWORK_STATE 时这里会抛 SecurityException（已加权限，兜底防 ROM 差异）。
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        trySend(isOnline(context))
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.conflate().distinctUntilChanged()
}
