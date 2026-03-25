package com.andrerinas.wirelesshelper.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

object HotspotManager {
    private const val TAG = "HUREV_WIFI"

    /**
     * Attempts to enable the WiFi Hotspot.
     */
    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        Log.i(TAG, "[HotspotManager] Attempting to set hotspot enabled: $enabled")

        // Method 1: Modern Reflection (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val callbackClass = try {
                    Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                } catch (e: Exception) {
                    null
                }
                
                if (callbackClass != null) {
                    if (enabled) {
                        // Check if it's an interface (for Proxy) or a class
                        var callbackInstance: Any? = null
                        if (callbackClass.isInterface) {
                            callbackInstance = Proxy.newProxyInstance(
                                callbackClass.classLoader,
                                arrayOf(callbackClass)
                            ) { _, _, _ -> null }
                        } else {
                            Log.d(TAG, "[HotspotManager] Callback is an abstract class. Trying to instantiate default if possible.")
                            // If it's a class, we might be able to find a concrete inner class or just pass null
                            // Some versions of Android (Pixel/Samsung) require a non-null callback.
                            // Emil's launcher uses a pre-compiled helper class here.
                            // Without that, we try null as last resort for the invoke call.
                        }

                        val startTetheringMethod = connectivityManager.javaClass.getDeclaredMethod(
                            "startTethering",
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType,
                            callbackClass,
                            Handler::class.java
                        )
                        
                        startTetheringMethod.invoke(connectivityManager, 0, false, callbackInstance, Handler(Looper.getMainLooper()))
                        Log.d(TAG, "[HotspotManager] startTethering (Android 11+) invoked")
                        return true
                    } else {
                        val stopTetheringMethod = connectivityManager.javaClass.getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
                        stopTetheringMethod.invoke(connectivityManager, 0)
                        Log.d(TAG, "[HotspotManager] stopTethering (Android 11+) invoked")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "[HotspotManager] Modern startTethering failed: ${e.message}")
            }
        }
        
        // Method 2: Legacy reflection (Android < 8.0)
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            val result = method.invoke(wifiManager, null, enabled) as Boolean
            Log.d(TAG, "[HotspotManager] Legacy setWifiApEnabled returned: $result")
            if (result) return true
        } catch (e: Exception) {
            Log.d(TAG, "[HotspotManager] Legacy setWifiApEnabled failed: ${e.message}")
        }

        // Method 3: ConnectivityManager.startTethering (Android 8.0 - 10.0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val methods = connectivityManager.javaClass.declaredMethods
                val startTethering = methods.find { it.name == "startTethering" }
                val stopTethering = methods.find { it.name == "stopTethering" }

                if (enabled && startTethering != null) {
                    Log.d(TAG, "[HotspotManager] Calling startTethering (Legacy O-Q) via reflection")
                    startTethering.invoke(connectivityManager, 0, false, null)
                    return true
                } else if (!enabled && stopTethering != null) {
                    Log.d(TAG, "[HotspotManager] Calling stopTethering (Legacy O-Q) via reflection")
                    stopTethering.invoke(connectivityManager, 0)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "[HotspotManager] ConnectivityManager tethering (O-Q) failed: ${e.message}")
            }
        }

        Log.w(TAG, "[HotspotManager] Could not enable hotspot automatically. System restrictions apply.")
        return false
    }
}
