package com.andrerinas.wirelesshelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.PermissionChecker

class WifiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("HUREV_WIFI", "WifiReceiver: Received intent ${intent.action ?: "PendingIntent Callback"}")
        
        // ConnectivityManager PendingIntents might not have a specific action
        checkWifiAndStart(context)
    }

    private fun checkWifiAndStart(context: Context) {
        WifiJobService.checkWifiAndStart(context)
    }
}