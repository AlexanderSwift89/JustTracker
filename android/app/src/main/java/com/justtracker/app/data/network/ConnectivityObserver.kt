package com.justtracker.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.justtracker.app.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Is the internet reachable right now?" as a hot flow. Uses the default-network callback and the
 * VALIDATED capability (captive portals and Wi-Fi without uplink count as offline). Needs only
 * ACCESS_NETWORK_STATE. Never fails: without a ConnectivityManager it reports offline.
 *
 * State is derived from the callback arguments, not from `activeNetwork`: during `onLost` the
 * manager may still report the vanishing network as active, which would leave the flow stuck online.
 */
class ConnectivityObserver(context: Context) {
    private val manager: ConnectivityManager? = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(current())

    /** True while a validated network with internet is the default one. */
    val online: StateFlow<Boolean> = _online

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = set(manager?.getNetworkCapabilities(network).isInternet())
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(caps.isInternet())
        override fun onLost(network: Network) = set(false)
        override fun onUnavailable() = set(false)
    }

    /** Call once per process (Application); the callback lives as long as the process. */
    fun start() {
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
            .onFailure { AppLog.w("Connectivity callback unavailable", it) }
        set(current())
    }

    private fun set(online: Boolean) {
        if (_online.value != online) AppLog.d("Internet reachable: $online")
        _online.value = online
    }

    private fun current(): Boolean {
        val m = manager ?: return false
        return runCatching { m.getNetworkCapabilities(m.activeNetwork) }.getOrNull().isInternet()
    }

    private fun NetworkCapabilities?.isInternet(): Boolean =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
