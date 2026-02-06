package com.andrerinas.wirelesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andrerinas.wirelesshelper.strategy.*
import kotlinx.coroutines.*

class WirelessHelperService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var currentStrategy: ConnectionStrategy? = null

    companion object {
        private const val TAG = "HUREV_WIFI"
        private const val CHANNEL_ID = "WirelessHelperChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        var isRunning = false
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        setupMulticastLock()
        createNotificationChannel()
        startForeground(1, createNotification("Searching for Headunit..."))
        
        // Pick Strategy based on settings
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("connection_mode", 0)
        
        currentStrategy = when (mode) {
            0 -> StrategySharedNetwork(this, serviceScope)
            1 -> StrategyHotspotPhone(this, serviceScope)
            2 -> StrategyHotspotTablet(this, serviceScope)
            3 -> StrategyWifiDirect(this, serviceScope)
            else -> StrategySharedNetwork(this, serviceScope)
        }
        
        Log.i(TAG, "Starting strategy for mode $mode")
        currentStrategy?.start()
    }

    private fun setupMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("WirelessHelperLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
        } catch (e: Exception) { Log.e(TAG, "MulticastLock failed") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        currentStrategy?.stop()
        try { multicastLock?.release() } catch (e: Exception) {}
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, WirelessHelperService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Helper").setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Wireless Helper", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?) = null
}