package com.andrerinas.wirelesshelper

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AutoStartReceiver : BroadcastReceiver() {
    private val TAG = "HUREV_AUTOSTART"

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val autoStartMode = prefs.getInt("auto_start_mode", 0) // 0=Off, 1=BT, 2=Wifi

        if (autoStartMode == 0) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (autoStartMode == 1) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val targetMac = prefs.getString("auto_start_bt_mac", null)
                    
                    Log.i(TAG, "BT Connected: ${device?.name} (${device?.address})")
                    
                    if (device?.address == targetMac) {
                        Log.i(TAG, "Target BT device detected! Starting WirelessHelperService...")
                        startService(context)
                    }
                }
            }
            "android.net.wifi.STATE_CHANGE" -> {
                // Wifi logic could be added here if needed, but BT is usually more reliable for cars
            }
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-start service", e)
        }
    }
}
