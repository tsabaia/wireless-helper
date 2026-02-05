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
        val action = intent.action
        Log.i(TAG, "Broadcast received: $action")

        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val autoStartMode = prefs.getInt("auto_start_mode", 0) // 0=Off, 1=BT

        if (autoStartMode == 0) return

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val targetMac = prefs.getString("auto_start_bt_mac", null)
            
            Log.i(TAG, "BT Device connected: ${device?.name} (${device?.address})")
            
            if (device?.address == targetMac) {
                Log.i(TAG, "MATCH! Starting service...")
                startService(context)
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
