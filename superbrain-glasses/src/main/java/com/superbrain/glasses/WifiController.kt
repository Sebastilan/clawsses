package com.superbrain.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log

/**
 * WiFi controller: add and connect to WiFi networks.
 * Uses WifiNetworkSpecifier on Android 10+ for app-level connection,
 * or falls back to WifiConfiguration on older APIs.
 */
class WifiController(private val context: Context) {

    companion object {
        private const val TAG = "WifiController"
    }

    private var currentCallback: ConnectivityManager.NetworkCallback? = null

    fun connectToWifi(ssid: String, password: String, onResult: (success: Boolean, message: String) -> Unit) {
        Log.i(TAG, "Connecting to WiFi: $ssid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithSpecifier(ssid, password, onResult)
        } else {
            connectWithConfiguration(ssid, password, onResult)
        }
    }

    private fun connectWithSpecifier(ssid: String, password: String, onResult: (Boolean, String) -> Unit) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Remove previous callback if any
        currentCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WiFi connected: $ssid")
                connectivityManager.bindProcessToNetwork(network)
                onResult(true, "Connected to $ssid")
            }

            override fun onUnavailable() {
                Log.e(TAG, "WiFi unavailable: $ssid")
                onResult(false, "Failed to connect to $ssid")
            }
        }
        currentCallback = callback

        try {
            connectivityManager.requestNetwork(request, callback)
            Log.i(TAG, "WiFi connection request sent for $ssid")
        } catch (e: Exception) {
            Log.e(TAG, "WiFi request failed", e)
            onResult(false, "WiFi request failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun connectWithConfiguration(ssid: String, password: String, onResult: (Boolean, String) -> Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
        }

        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            onResult(false, "Failed to add network $ssid")
            return
        }

        wifiManager.disconnect()
        val enabled = wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()

        if (enabled) {
            Log.i(TAG, "WiFi enabled: $ssid (netId=$netId)")
            onResult(true, "Connecting to $ssid...")
        } else {
            Log.e(TAG, "Failed to enable network: $ssid")
            onResult(false, "Failed to enable $ssid")
        }
    }

    fun getWifiStatus(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        return buildString {
            append("WiFi enabled=${wifiManager.isWifiEnabled}")
            @Suppress("DEPRECATION")
            append(", SSID=${info?.ssid ?: "none"}")
            append(", RSSI=${info?.rssi ?: 0}dBm")
            append(", hasInternet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true}")
        }
    }

    fun cleanup() {
        currentCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        currentCallback = null
    }
}
