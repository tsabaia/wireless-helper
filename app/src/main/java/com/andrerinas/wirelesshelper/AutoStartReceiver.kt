package com.andrerinas.wirelesshelper

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat

class AutoStartReceiver : BroadcastReceiver() {
    private val TAG = "HUREV_AUTOSTART"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Broadcast received: $action")

        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val autoStartMode = prefs.getInt("auto_start_mode", 0) // 0=Off, 1=BT, 2=Wifi

        Log.i(TAG, "Current Auto-Start Mode: $autoStartMode")

        if (autoStartMode == 0) {
            Log.i(TAG, "Auto-start is disabled in settings. Ignoring.")
            return
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (autoStartMode == 1) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val targetMac = prefs.getString("auto_start_bt_mac", null)
                    Log.i(TAG, "BT Device connected: ${device?.name} (${device?.address})")
                    if (device?.address == targetMac) {
                        Log.i(TAG, "MATCH! Starting service...")
                        startService(context)
                    }
                }
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION, "android.net.conn.CONNECTIVITY_CHANGE" -> {
                if (autoStartMode == 2) {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val info = wifiManager.connectionInfo
                    val ssid = info.ssid.removeSurrounding("\"")
                    val targetSsid = prefs.getString("auto_start_wifi_ssid", null)

                    Log.i(TAG, "WiFi State Change. Current SSID: $ssid, Target: $targetSsid")
                    
                    // Check if actually connected
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    val isConnected = networkInfo?.isConnected ?: true // Fallback to true if extra missing
                    
                    Log.i(TAG, "Network isConnected: $isConnected")

                    if (isConnected && ssid == targetSsid && ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                        Log.i(TAG, "MATCH! Starting service...")
                        startService(context)
                    }
                }
            }
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_START
        }
        try {
            Log.i(TAG, "Firing Foreground Service Intent...")
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-start service: ${e.message}", e)
        }
    }
}