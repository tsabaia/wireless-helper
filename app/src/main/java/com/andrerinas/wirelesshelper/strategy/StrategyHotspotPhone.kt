package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket

class StrategyHotspotPhone(context: Context, private val scope: CoroutineScope) : BaseStrategy(context, scope) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private val SERVICE_TYPE = "_aawireless._tcp."
    private val PORT_DISCOVERY = 5289

    override fun start() {
        Log.i(TAG, "Strategy: Hotspot Phone (NSD + TCP 5289 Listener)")
        
        // 1. TCP Listener (The key for phone-hotspot mode!)
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT_DISCOVERY))
                }
                while (isActive) {
                    val client = serverSocket?.accept()
                    val remoteIp = client?.inetAddress?.hostAddress
                    client?.close()
                    
                    if (remoteIp != null) {
                        Log.i(TAG, "TCP Trigger received from Tablet at $remoteIp")
                        launchAndroidAuto(remoteIp, forceFakeNetwork = false)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP Server stopped or error: ${e.message}")
            }
        }

        // 2. NSD Discovery (Backup)
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, err: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        si.host.hostAddress?.let { 
                            Log.i(TAG, "NSD Resolved IP: $it")
                            launchAndroidAuto(it, forceFakeNetwork = false) 
                        }
                    }
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStopped(s: String) {}
            override fun onStartDiscoveryFailed(s: String, e: Int) {}
            override fun onStopDiscoveryFailed(s: String, e: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD Start failed", e)
        }
    }

    override fun stop() {
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        discoveryListener = null
    }
}
