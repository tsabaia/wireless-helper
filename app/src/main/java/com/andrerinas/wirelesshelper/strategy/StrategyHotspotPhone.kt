package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.util.Log
import com.andrerinas.wirelesshelper.utils.HotspotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket

class StrategyHotspotPhone(context: Context, private val scope: CoroutineScope) : BaseStrategy(context, scope) {

    private var serverSocket: ServerSocket? = null
    private val PORT_DISCOVERY = 5289
    
    private val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)

    private var hotspotEnabledByUs: Boolean
        get() = prefs.getBoolean("hotspot_enabled_by_us", false)
        set(value) = prefs.edit().putBoolean("hotspot_enabled_by_us", value).apply()

    override fun start() {
        Log.i(TAG, "Strategy: Hotspot Phone (TCP 5289 Trigger Listener)")
        
        // Only claim ownership if hotspot was known-off before we enabled it (see HotspotManager.isWifiHotspotActive).
        val priorActive = HotspotManager.isWifiHotspotActive(context)
        val success = HotspotManager.setHotspotEnabled(context, true)
        
        // Update persistent ownership
        if (success && priorActive == false) {
            hotspotEnabledByUs = true
        }
        
        Log.i(TAG, "Auto-enable hotspot: priorActive=$priorActive success=$success hotspotEnabledByUs=$hotspotEnabledByUs")
        
        getStrategyScope().launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_DISCOVERY))
                }
                while (isActive) {
                    val client = serverSocket?.accept()
                    val remoteIp = client?.inetAddress?.hostAddress
                    client?.close()
                    
                    if (remoteIp != null) {
                        Log.i(TAG, "TCP Trigger received from Tablet at $remoteIp")
                        // Use forceFakeNetwork = true to bypass cellular routing in hotspot mode
                        launchAndroidAuto(remoteIp)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Hotspot TCP Server stopped or error: ${e.message}")
            }
        }
    }

    private fun stopListener() {
        Log.d(TAG, "Stopping Hotspot TCP Listener")
        super.stop()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    override fun stopForLaunch() {
        stopListener()
    }

    override fun stop() {
        stopListener()

        val forceStop = prefs.getBoolean("force_stop_hotspot", false)

        // Disable hotspot if we enabled it OR if force stop is enabled
        if (hotspotEnabledByUs || forceStop) {
            Log.i(TAG, "Disabling hotspot (enabledByUs=$hotspotEnabledByUs, forceStop=$forceStop)")
            HotspotManager.setHotspotEnabled(context, false)
            hotspotEnabledByUs = false
        }
    }
}
