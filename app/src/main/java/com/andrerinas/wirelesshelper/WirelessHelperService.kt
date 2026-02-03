package com.andrerinas.wirelesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.andrerinas.headunitrevived.utils.AppLog

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

    // Car Connection Monitoring
    private val carConnectionUri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()
    private val carConnectionObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            checkCarConnection()
        }
    }

    companion object {
        private const val TAG = "HUREV_WIFI"
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
        
        try {
            contentResolver.registerContentObserver(carConnectionUri, true, carConnectionObserver)
            checkCarConnection()
        } catch (e: Exception) {
            Log.w(TAG, "Car connection content provider not available")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLauncher()
            ACTION_STOP -> stopLauncher()
        }
        return START_STICKY
    }

    private fun checkCarConnection() {
        serviceScope.launch {
            val status = getCarConnectionState()
            // 0 = Disconnected, 1 = Projection, 2 = Native
            val connected = status > 0
            
            if (connected != isCurrentlyConnected) {
                isCurrentlyConnected = connected
                if (connected) {
                    AppLog.i("Android Auto connection detected (State: $status)")
                    updateNotification("Android Auto is active")
                } else {
                    AppLog.i("Android Auto disconnected")
                    updateNotification("Searching for Headunit...")
                    if (isRunning) {
                        startLauncher() // Re-trigger discovery if service is supposed to be running
                    }
                }
            }
        }
    }

    private fun getCarConnectionState(): Int {
        return try {
            val cursor: Cursor? = contentResolver.query(carConnectionUri, arrayOf("CarConnectionState"), null, null, null)
            cursor?.use {
                if (it.moveToNext()) {
                    val index = it.getColumnIndex("CarConnectionState")
                    if (index >= 0) it.getInt(index) else 0
                } else 0
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun startLauncher() {
        if (isRunning) return
        isRunning = true
        
        val notification = createNotification("Checking connection status...")
        startForeground(NOTIFICATION_ID, notification)
        
        serviceScope.launch {
            val status = getCarConnectionState()
            AppLog.i("State: $status");
            isCurrentlyConnected = status > 0
            
            if (isCurrentlyConnected) {
                AppLog.i("AA is already connected (State: $status). Skipping initial scan.")
                updateNotification("Android Auto is active")
                return@launch
            }
            
            updateNotification("Searching for Headunit...")
            AppLog.i("Service started")

            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val mode = prefs.getInt("connection_mode", 0)

            when (mode) {
                3 -> { // Hotspot on Headunit
                    AppLog.i("Mode: Hotspot on Headunit (Passive Wait)")
                    AppLog.i("Listening on TCP port $PORT_AA_WIFI_DISCOVERY")
                    startTcpServer()
                }
                else -> { // Network Discovery / Active Scan / Phone Hotspot
                    AppLog.i("Mode: Active Discovery (Scanning)")
                    
                    AppLog.i("Starting NSD discovery for $SERVICE_TYPE")
                    startNsdDiscovery()
                    
                    AppLog.i("Starting Active Network Scan")
                    startActiveScan()
                }
            }
        }
    }

    private fun startActiveScan() {
        if (networkDiscovery == null) {
            networkDiscovery = NetworkDiscovery(this, object : NetworkDiscovery.Listener {
                override fun onServiceFound(ip: String, port: Int) {
                    if (isCurrentlyConnected) return
                    
                    AppLog.i("Active Scan found service at $ip:$port. Launching...")
                    launchAndroidAuto(ip)
                }

                override fun onScanFinished() {
                    if (isRunning && !isCurrentlyConnected) {
                         serviceScope.launch {
                             delay(10000)
                             if (isRunning && !isCurrentlyConnected) {
                                 AppLog.i("Restarting Active Scan...")
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

    private fun startTcpServer() {
        serviceScope.launch {
            try {
                if (serverSocket != null) return@launch
                
                AppLog.i("Binding TCP server to port $PORT_AA_WIFI_DISCOVERY")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_AA_WIFI_DISCOVERY))
                    soTimeout = 0 
                }
                AppLog.i("TCP server bound successfully")

                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        
                        clientSocket?.use { socket ->
                            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
                            
                            if (remoteIp != null) {
                                if (isCurrentlyConnected) {
                                    AppLog.i("Ignored connection from $remoteIp (already connected)")
                                    return@use
                                }

                                AppLog.i("Headunit connected via TCP: $remoteIp. Launching...")
                                launchAndroidAuto(remoteIp)
                                
                                // Short sleep to let the intent process
                                delay(2000)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(1000)
                    }
                }
            } catch (e: Exception) {
                AppLog.i("Server error: ${e.message}")
            } finally {
                serverSocket?.close()
                serverSocket = null
            }
        }
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startNsdDiscovery() {
        if (discoveryListener != null) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                AppLog.i("NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isCurrentlyConnected) return
                if (service.serviceType.contains("aawireless")) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsdDiscovery() }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            AppLog.i("Failed to start NSD: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (isCurrentlyConnected) return
                val hostIp = serviceInfo.host.hostAddress
                if (hostIp != null) {
                    AppLog.i("NSD Resolved IP: $hostIp. Launching...")
                    launchAndroidAuto(hostIp)
                }
            }
        })
    }

    private fun stopLauncher() {
        isRunning = false
        monitoringJob?.cancel()
        monitoringJob = null
        
        networkDiscovery?.stop()
        networkDiscovery = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        stopNsdDiscovery()
        
        serviceJob.cancelChildren()
        AppLog.i("Launcher stopped")
        stopForeground(true)
    }

    private fun stopNsdDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) { }
            discoveryListener = null
        }
    }

    private fun checkIfHeadunitIsBusy(ip: String): Boolean {
        return try {
            AppLog.i("Checking if Headunit at $ip is busy...")
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, PORT_AA_WIFI_SERVICE), 1000)
            socket.soTimeout = 500 // Short read timeout
            
            val input = socket.getInputStream()
            val byte = input.read()
            socket.close()
            
            // If read returns -1, server closed connection -> BUSY (HURev rejects new connections)
            if (byte == -1) {
                AppLog.i("Headunit closed connection immediately -> BUSY")
                true
            } else {
                // Should not happen as HURev doesn't send data on connect
                true
            }
        } catch (e: SocketTimeoutException) {
            // Read timed out -> Server kept connection open -> READY (HURev waiting for handshake)
            AppLog.i("Headunit holding connection -> READY")
            false
        } catch (e: Exception) {
            AppLog.i("Check failed (${e.javaClass.simpleName}) -> Assuming READY")
            false
        }
    }

    private fun launchAndroidAuto(hostIp: String) {
        serviceScope.launch {
            // Pre-flight check: Is Headunit already busy?
            if (checkIfHeadunitIsBusy(hostIp)) {
                AppLog.i("Headunit is BUSY (Already connected). Skipping launch.")
                isCurrentlyConnected = true
                updateNotification("Android Auto is active")
                return@launch
            }

            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network: Network? = connectivityManager.activeNetwork
                
                if (network == null) {
                    AppLog.i("Error: No active network found!")
                    return@launch
                }

                // Create minimal WifiInfo via reflection
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

                AppLog.i("Sending Intent to Android Auto for $hostIp...")
                startActivity(intent)
                
                // Set connected state immediately to avoid double firing
                isCurrentlyConnected = true
                updateNotification("Android Auto is active")
                
            } catch (e: Exception) {
                AppLog.i("Failed to start AA: ${e.message}")
            }
        }
    }

    private fun createNotification(content: String): Notification {
        val stopIntent = Intent(this, WirelessHelperService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Helper Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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
        contentResolver.unregisterContentObserver(carConnectionObserver)
        stopLauncher()
        super.onDestroy()
    }
}
