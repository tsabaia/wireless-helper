package com.andrerinas.wirelesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class WirelessLauncherService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    companion object {
        private const val TAG = "WirelessLauncher"
        private const val PORT_AA_WIFI_DISCOVERY = 5289
        private const val PORT_AA_WIFI_SERVICE = 5288
        private const val CHANNEL_ID = "WirelessLauncherChannel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_LOG = "ACTION_LOG"
        const val EXTRA_LOG_MESSAGE = "EXTRA_LOG_MESSAGE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLauncher()
            ACTION_STOP -> stopLauncher()
        }
        return START_STICKY
    }

    private fun startLauncher() {
        if (isRunning) return
        isRunning = true
        
        val notification = createNotification("Searching for Headunit...")
        startForeground(NOTIFICATION_ID, notification)
        
        sendUiLog("Service started, listening on port $PORT_AA_WIFI_DISCOVERY")

        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_AA_WIFI_DISCOVERY))
                    soTimeout = 5000
                }

                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.use { socket ->
                            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
                            if (remoteIp != null) {
                                sendUiLog("Headunit found at: $remoteIp")
                                launchAndroidAuto(remoteIp)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // Just timeout, continue loop
                    } catch (e: Exception) {
                        if (isRunning) sendUiLog("Error accepting connection: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                sendUiLog("Server error: ${e.message}")
            } finally {
                stopLauncher()
            }
        }
    }

    private fun stopLauncher() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        serviceJob.cancelChildren()
        sendUiLog("Service stopped")
        stopForeground(true)
        stopSelf()
    }

    private fun launchAndroidAuto(hostIp: String) {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = connectivityManager.activeNetwork
            
            if (network == null) {
                sendUiLog("Error: No active network found!")
                return
            }

            // Create fake WifiInfo if possible, as seen in original code
            val wifiInfo: WifiInfo? = try {
                val clazz = Class.forName("android.net.wifi.WifiInfo")
                val constructor = clazz.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance() as WifiInfo
            } catch (e: Exception) {
                null
            }

            val intent = Intent().apply {
                setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", hostIp)
                putExtra("PARAM_SERVICE_PORT", PORT_AA_WIFI_SERVICE)
                putExtra("PARAM_SERVICE_WIFI_NETWORK", network)
                putExtra("wifi_info", wifiInfo)
            }

            sendUiLog("Sending Intent to Android Auto (Gearhead)...")
            startActivity(intent)
        } catch (e: Exception) {
            sendUiLog("Failed to start AA: ${e.message}")
            Log.e(TAG, "Launch error", e)
        }
    }

    private fun sendUiLog(message: String) {
        Log.d(TAG, message)
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Launcher Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Wireless Launcher Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLauncher()
        super.onDestroy()
    }
}