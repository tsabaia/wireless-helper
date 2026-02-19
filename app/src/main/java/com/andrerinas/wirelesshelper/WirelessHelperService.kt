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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.andrerinas.wirelesshelper.strategy.*
import kotlinx.coroutines.*

class WirelessHelperService : Service(), BaseStrategy.StateListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentStrategy: ConnectionStrategy? = null

    companion object {
        private const val TAG = "HUREV_WIFI"
        private const val CHANNEL_ID = "WirelessHelperChannel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        var isRunning = false
            internal set
        var isConnected = false
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isConnected = false
        setupLocks()
        createNotificationChannel()
        startForeground(1, createNotification(getString(R.string.notif_searching)))
        
        startSelectedStrategy()
    }

    private fun setupLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("WirelessHelperLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
            
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WirelessHelper:WakeLock")
            wakeLock?.acquire(3600000) // 1 hour max
            
            Log.i(TAG, "Locks acquired")
        } catch (e: Exception) { Log.e(TAG, "Failed to acquire locks: ${e.message}") }
    }

    private fun startSelectedStrategy() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("connection_mode", 0)
        
        currentStrategy = when (mode) {
            0 -> StrategySharedNetwork(this, serviceScope)
            1 -> StrategyHotspotPhone(this, serviceScope)
            2 -> StrategyHotspotTablet(this, serviceScope)
            3 -> StrategyWifiDirect(this, serviceScope)
            else -> StrategySharedNetwork(this, serviceScope)
        }
        
        if (currentStrategy is BaseStrategy) {
            (currentStrategy as BaseStrategy).stateListener = this
        }
        
        Log.i(TAG, "Starting strategy for mode $mode")
        currentStrategy?.start()
    }

    override fun onProxyConnected() {
        isConnected = true
        updateNotification(getString(R.string.notif_connected))
    }

    override fun onProxyDisconnected() {
        isConnected = false
        Log.i(TAG, "Proxy disconnected. Job done, stopping service.")
        updateNotification(getString(R.string.notif_disconnected))
        
        serviceScope.launch {
            delay(3000)
            if (isRunning) {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isConnected = false
        currentStrategy?.stop()
        if (currentStrategy is BaseStrategy) {
            (currentStrategy as BaseStrategy).cleanup()
        }
        try { 
            multicastLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, WirelessHelperService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name)).setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?) = null
}
