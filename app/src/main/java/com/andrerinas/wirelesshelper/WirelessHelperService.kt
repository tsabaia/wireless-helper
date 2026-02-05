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
import java.util.concurrent.atomic.AtomicBoolean

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

import com.andrerinas.wirelesshelper.connection.NetworkDiscovery
import java.net.Socket

class WirelessHelperService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var networkDiscovery: NetworkDiscovery? = null
    private val isCurrentlyConnected = AtomicBoolean(false)
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

        var isRunning = false
            internal set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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
            val connected = status > 0
            
            if (connected != isCurrentlyConnected.get()) {
                isCurrentlyConnected.set(connected)
                if (connected) {
                    Log.i(TAG, "Android Auto connection detected (State: $status)")
                    updateNotification("Android Auto is active")
                } else {
                    Log.i(TAG, "Android Auto disconnected")
                    updateNotification("Searching for Headunit...")
                    if (isRunning) {
                        startLauncher() 
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
        isRunning = true
        
        val notification = createNotification("Checking connection status...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        serviceScope.launch {
            val status = getCarConnectionState()
            isCurrentlyConnected.set(status > 0)
            
            if (isCurrentlyConnected.get()) {
                Log.i(TAG, "AA is already connected. Skipping initial scan.")
                updateNotification("Android Auto is active")
                return@launch
            }
            
            updateNotification("Searching for Headunit...")
            Log.i(TAG, "Service started")

            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val mode = prefs.getInt("connection_mode", 0) // 0=Auto, 1=NSD, 2=Passive

            if (mode == 0 || mode == 1) {
                Log.i(TAG, "Starting NSD Discovery...")
                startNsdDiscovery()
            }
            if (mode == 0 || mode == 2) {
                Log.i(TAG, "Starting TCP Trigger Server...")
                startTcpServer()
            }
        }
    }

    private fun startTcpServer() {
        serviceScope.launch {
            try {
                if (serverSocket != null) return@launch
                
                Log.i(TAG, "Binding TCP server to port $PORT_AA_WIFI_DISCOVERY")
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_AA_WIFI_DISCOVERY))
                    soTimeout = 0 
                }
                Log.i(TAG, "TCP server bound successfully")

                while (isActive && isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        val remoteIp = clientSocket?.remoteSocketAddress?.let { (it as InetSocketAddress).address.hostAddress }
                        
                        try { clientSocket?.close() } catch (e: Exception) {}
                        
                        if (remoteIp != null) {
                            if (isCurrentlyConnected.get()) {
                                Log.i(TAG, "Ignored trigger from $remoteIp (already connected)")
                            } else {
                                Log.i(TAG, "Trigger received from $remoteIp. Launching...")
                                launchAndroidAuto(remoteIp)
                                delay(5000)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) delay(1000)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "Server error: ${e.message}")
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
                Log.i(TAG, "NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (isCurrentlyConnected.get()) return
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
            Log.i(TAG, "Failed to start NSD: ${e.message}")
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (isCurrentlyConnected.get()) return
                val hostIp = serviceInfo.host.hostAddress
                if (hostIp != null) {
                    Log.i(TAG, "NSD Resolved IP: $hostIp. Launching...")
                    launchAndroidAuto(hostIp)
                }
            }
        })
    }

    private fun stopLauncher() {
        isRunning = false
        isCurrentlyConnected.set(false)
        monitoringJob?.cancel()
        monitoringJob = null
        
        stopDiscoveryListeners()
        
        serviceJob.cancelChildren()
        Log.i(TAG, "Launcher stopped")
        stopForeground(true)
    }

    private fun stopDiscoveryListeners() {
        networkDiscovery?.stop()
        networkDiscovery = null

        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverSocket = null
        
        stopNsdDiscovery()
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
        var finalIp = hostIp
        if ((finalIp == "127.0.0.1" || finalIp == "0:0:0:0:0:0:0:1") && isEmulator()) {
            Log.i(TAG, "Emulator & localhost detected. Redirecting to 10.0.2.2")
            finalIp = "10.0.2.2"
        }

        if (!isCurrentlyConnected.compareAndSet(false, true)) {
            Log.i(TAG, "Already connected or launching. Ignoring request for $finalIp")
            return
        }

        // IMPORTANT: Stop all other listeners immediately to avoid race conditions
        stopDiscoveryListeners()

        serviceScope.launch {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network: Network? = connectivityManager.activeNetwork
                
                if (network == null) {
                    Log.i(TAG, "Error: No active network found!")
                    isCurrentlyConnected.set(false)
                    return@launch
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
                    putExtra("PARAM_HOST_ADDRESS", finalIp)
                    putExtra("PARAM_SERVICE_PORT", PORT_AA_WIFI_SERVICE)
                    putExtra("PARAM_SERVICE_WIFI_NETWORK", network)
                    putExtra("wifi_info", wifiInfo)
                }

                Log.i(TAG, "Sending Intent to Android Auto for $finalIp...")
                startActivity(intent)
                
                verifyConnection(finalIp)
                
            } catch (e: Exception) {
                Log.i(TAG, "Failed to start AA: ${e.message}")
                isCurrentlyConnected.set(false)
            }
        }
    }

    private fun verifyConnection(ip: String) {
        serviceScope.launch {
            Log.i(TAG, "Verification: Waiting 10s for Android Auto to settle...")
            delay(10000)
            
            if (!isRunning) return@launch

            val isBusy = checkIfHeadunitIsBusy(ip)
            if (isBusy) {
                Log.i(TAG, "Verification SUCCESS: Headunit is busy, AA is running. Auto-stopping helper service.")
                isCurrentlyConnected.set(true)
                delay(2000)
                stopLauncher() 
            } else {
                Log.i(TAG, "Verification FAILED: Headunit is still IDLE. Resuming search...")
                isCurrentlyConnected.set(false)
                updateNotification("Searching for Headunit...")
                startLauncher() // Retry
            }
        }
    }

    private fun checkIfHeadunitIsBusy(ip: String): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, PORT_AA_WIFI_SERVICE), 2000)
            socket.soTimeout = 500
            val input = socket.getInputStream()
            val byte = input.read()
            socket.close()
            byte == -1 
        } catch (e: Exception) {
            false
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
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
        isRunning = false
        contentResolver.unregisterContentObserver(carConnectionObserver)
        stopLauncher()
        super.onDestroy()
    }
}
