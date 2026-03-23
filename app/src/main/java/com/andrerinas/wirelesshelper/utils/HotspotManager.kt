package com.andrerinas.wirelesshelper.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

object HotspotManager {
    private const val TAG = "HUREV_WIFI"

    /**
     * Attempts to enable the WiFi Hotspot.
     */
    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        Log.i(TAG, "[HotspotManager] Attempting to set hotspot enabled: $enabled")
        
        // Method 1: Legacy reflection (Android < 8.0)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            val result = method.invoke(wifiManager, null, enabled) as Boolean
            Log.d(TAG, "[HotspotManager] Legacy setWifiApEnabled returned: $result")
            return result
        } catch (e: Exception) {
            Log.d(TAG, "[HotspotManager] Legacy setWifiApEnabled failed: ${e.message}")
        }

        // Method 2: ConnectivityManager.startTethering (Android 8.0 - 10.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val methods = connectivityManager.javaClass.declaredMethods
                val startTethering = methods.find { it.name == "startTethering" }
                val stopTethering = methods.find { it.name == "stopTethering" }

                if (enabled && startTethering != null) {
                    Log.d(TAG, "[HotspotManager] Calling startTethering via reflection")
                    startTethering.invoke(connectivityManager, 0, false, null)
                    return true
                } else if (!enabled && stopTethering != null) {
                    Log.d(TAG, "[HotspotManager] Calling stopTethering via reflection")
                    stopTethering.invoke(connectivityManager, 0)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "[HotspotManager] ConnectivityManager tethering call failed: ${e.message}")
            }
        }

        Log.w(TAG, "[HotspotManager] Could not enable hotspot automatically. System restrictions apply.")
        return false
    }
}
