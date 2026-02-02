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

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress

import com.andrerinas.wirelesshelper.connection.NetworkDiscovery

class WirelessHelperService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkDiscovery: NetworkDiscovery? = null
    private var isRunning = false
    private var isCurrentlyConnected = false
    private var monitoringJob: Job? = null

    companion object {
        private const val TAG = "WirelessHelper"
        private const val PORT_AA_WIFI_DISCOVERY = 5289
        private const val PORT_AA_WIFI_SERVICE = 5288
        private const val CHANNEL_ID = "WirelessHelperChannel"
        private const val NOTIFICATION_ID = 1
        private const val SERVICE_TYPE = "_aawireless._tcp."

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_LOG = "ACTION_LOG"
        const val EXTRA_LOG_MESSAGE = "EXTRA_LOG_MESSAGE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
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
        
        sendUiLog("Service started")

        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("connection_mode", 0)

        when (mode) {
            3 -> { // Hotspot on Headunit
                sendUiLog("Mode: Hotspot on Headunit (Passive Wait)")
                sendUiLog("Listening on TCP port $PORT_AA_WIFI_DISCOVERY")
                startTcpServer()
            }
            else -> { // Network Discovery / Active Scan / Wifi Direct / Phone Hotspot
                sendUiLog("Mode: Active Discovery (Scanning)")
                // Do NOT start TCP Server here to avoid confusing HUR logic
                
                sendUiLog("Starting NSD discovery for $SERVICE_TYPE")
                startNsdDiscovery()
                
                sendUiLog("Starting Active Network Scan")
                startActiveScan()
            }
        }
    }

    private fun startActiveScan() {
        if (networkDiscovery == null) {
            networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
                override fun onServiceFound(ip: String, port: Int) {
                    if (isCurrentlyConnected) return
                    
                    sendUiLog("Active Scan found service at $ip:$port")
                    isCurrentlyConnected = true
                    updateNotification("Connected to Headunit ($ip)")
                    launchAndroidAuto(ip)
                    startMonitoring(ip, PORT_AA_WIFI_SERVICE)
                }

                override fun onScanFinished() {
                    if (isRunning && !isCurrentlyConnected) {
                         serviceScope.launch {
                             delay(10000)
                             if (isRunning && !isCurrentlyConnected) {
                                 sendUiLog("Restarting Active Scan...")
                                 networkDiscovery?.startScan()
                             }
                         }
                    }
                }
            })
        }
        if (!isCurrentlyConnected) {
            networkDiscovery?.startScan()
        }
    }

    private fun startMonitoring(ip: String, port: Int) {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            delay(5000)
            while (isActive && isRunning && isCurrentlyConnected) {
                if (!checkPort(ip, port)) {
                    delay(1000)
                    if (!checkPort(ip, port)) {
                        sendUiLog("Headunit Service Port ($port) unreachable at $ip")
                        isCurrentlyConnected = false
                        updateNotification("Searching for Headunit...")
                        
                        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
                        if (prefs.getInt("connection_mode", 0) == 0) {
                            networkDiscovery?.startScan()
                        }
                        break
                    }
                }
                delay(5000)
            }
        }
    }

    private fun checkPort(ip: String, port: Int): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 2000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startTcpServer() {
        serviceScope.launch {
            try {
                sendUiLog("Binding TCP server to port $PORT_AA_WIFI_DISCOVERY")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_AA_WIFI_DISCOVERY))
                    soTimeout = 0 
                }
                sendUiLog("TCP server bound successfully")

                while (isActive && isRunning) {
                    try {
                        sendUiLog("Waiting for incoming connection (accept)...")
                        val clientSocket = serverSocket?.accept()
                        
                        sendUiLog("Connection accepted! Processing...")
                        
                        clientSocket?.use { socket ->
                            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
                            sendUiLog("Incoming connection from IP: $remoteIp")
                            
                            if (remoteIp != null) {
                                if (isCurrentlyConnected) {
                                    sendUiLog("Ignored connection from $remoteIp (already connected)")
                                    return@use
                                }

                                isCurrentlyConnected = true
                                sendUiLog("Headunit connected via TCP: $remoteIp")
                                updateNotification("Connected to Headunit ($remoteIp)")
                                
                                launchAndroidAuto(remoteIp)
                                startMonitoring(remoteIp, PORT_AA_WIFI_SERVICE)

                                try {
                                    val inputStream = socket.getInputStream()
                                    val buffer = ByteArray(1024)
                                    sendUiLog("Entering Keep-Alive loop for $remoteIp")
                                    while (isActive && isRunning && isCurrentlyConnected) {
                                        val read = inputStream.read(buffer)
                                        if (read == -1) {
                                            sendUiLog("Socket EOF (Peer closed connection)")
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                    sendUiLog("Socket error/closed: ${e.message}")
                                }
                                
                                sendUiLog("TCP connection closed. Relying on 5288 monitoring.")
                            } else {
                                sendUiLog("Error: Could not determine remote IP")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) sendUiLog("Error in accept loop: ${e.message}")
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                sendUiLog("Server error: ${e.message}")
            } finally {
                serverSocket?.close()
            }
        }
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startNsdDiscovery() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                sendUiLog("NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isCurrentlyConnected) return
                if (service.serviceType.contains("aawireless")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                sendUiLog("NSD Service lost: ${service.serviceName}")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            sendUiLog("Failed to start NSD: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                sendUiLog("NSD Resolve failed: Error $errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (isCurrentlyConnected) return
                val hostIp = serviceInfo.host.hostAddress
                sendUiLog("NSD Resolved IP: $hostIp")
                if (hostIp != null) {
                    isCurrentlyConnected = true
                    updateNotification("Connected to Headunit ($hostIp)")
                    launchAndroidAuto(hostIp)
                    startMonitoring(hostIp, PORT_AA_WIFI_SERVICE)
                }
            }
        })
    }

    private fun stopLauncher() {
        isRunning = false
        isCurrentlyConnected = false
        monitoringJob?.cancel()
        monitoringJob = null
        
        networkDiscovery?.stop()
        networkDiscovery = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        
        stopNsdDiscovery()
        
        serviceJob.cancelChildren()
        sendUiLog("Service stopped")
        stopForeground(true)
        stopSelf()
    }

    private fun stopNsdDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) { }
            discoveryListener = null
        }
    }

    private fun launchAndroidAuto(hostIp: String) {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = connectivityManager.activeNetwork
            
            if (network == null) {
                sendUiLog("Error: No active network found!")
                return
            }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Helper Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Wireless Helper Service Channel",
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