package com.matiasnl.hakiosk.data.device.mqtt

import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observes the device's default network so the MQTT connection can react to network changes promptly. */
fun interface NetworkMonitor {
    /**
     * Opaque id of the current default network, or null while there is none. A change from one id to
     * another means the default network switched (e.g. Wi-Fi reconnected) and open sockets are stale.
     */
    fun defaultNetwork(): Flow<Long?>
}

/** [NetworkMonitor] over [ConnectivityManager.registerDefaultNetworkCallback]; registered only while collected. */
class ConnectivityNetworkMonitor(private val connectivityManager: ConnectivityManager) : NetworkMonitor {
    override fun defaultNetwork(): Flow<Long?> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network.networkHandle)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        trySend(connectivityManager.activeNetwork?.networkHandle)
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (e: RuntimeException) {
            // Too many callbacks registered by the process: degrade to "always online"; keepalive still
            // detects dead connections, just later.
            trySend(connectivityManager.activeNetwork?.networkHandle ?: UNKNOWN_NETWORK)
            awaitClose { }
            return@callbackFlow
        }
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private companion object {
        const val UNKNOWN_NETWORK = -1L
    }
}
