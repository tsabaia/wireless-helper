package com.andrerinas.wirelesshelper.connection

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class NetworkDiscovery(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onServiceFound(ip: String, port: Int)
        fun onScanFinished()
    }

    private var scanJob: Job? = null
    private val TAG = "NetworkDiscovery"

    fun startScan() {
        if (scanJob?.isActive == true) return

        Log.i(TAG, "Starting scan...")

        scanJob = CoroutineScope(Dispatchers.IO).launch {
            // 1. Quick Scan: Check likely Gateways first
            Log.i(TAG, "Step 1 - Quick Gateway Scan")
            val gatewayFound = scanGateways()
            
            if (gatewayFound) {
                 Log.i(TAG, "Gateway found service, skipping subnet scan.")
                 withContext(Dispatchers.Main) {
                    listener.onScanFinished()
                }
                return@launch
            }

            // 2. Deep Scan: Check entire Subnet
            Log.i(TAG, "Step 2 - Full Subnet Scan")
            scanSubnet()

            withContext(Dispatchers.Main) {
                listener.onScanFinished()
            }
        }
    }

    private suspend fun scanGateways(): Boolean {
        var foundAny = false
        try {
            val suspects = mutableSetOf<String>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val lp = cm.getLinkProperties(activeNet)
                    lp?.routes?.forEach { route ->
                        if (route.isDefaultRoute && route.gateway is Inet4Address) {
                            route.gateway?.hostAddress?.let { suspects.add(it) }
                        }
                    }
                }
            }
            // Always try heuristics (X.X.X.1) for all interfaces
            collectInterfaceSuspects(suspects)

            if (suspects.isNotEmpty()) {
                Log.i(TAG, "Checking suspects: $suspects")
                for (ip in suspects) {
                    if (checkAndReport(ip)) {
                        foundAny = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gateway scan error", e)
        }
        return foundAny
    }

    private suspend fun scanSubnet() {
        val subnet = getSubnet()
        if (subnet == null) {
            Log.e(TAG, "Could not determine subnet for deep scan")
            return
        }

        Log.i(TAG, "Scanning subnet: $subnet.*")

        val tasks = mutableListOf<Deferred<Boolean>>()

        // Scan range 1..254 (excluding gateway potentially, but checkAndReport handles duplicates if called)
        for (i in 1..254) {
            val ip = "$subnet.$i"
            tasks.add(CoroutineScope(Dispatchers.IO).async {
                checkAndReport(ip)
            })
        }

        tasks.awaitAll()
    }

    private suspend fun checkAndReport(ip: String): Boolean {
        // Check Port 5289 (Wifi Launcher) - prioritizing this
        if (checkPort(ip, 5289, timeout = 300)) {
            Log.i(TAG, "Found Wifi Launcher on $ip:5289")
            // Notify on Main thread? Or Listener handles it? 
            // The original code uses withContext(Main). Listener implementation needs to be thread safe or UI aware.
            // But here the listener might call launchAndroidAuto which touches UI/Main Looper.
            withContext(Dispatchers.Main) {
                listener.onServiceFound(ip, 5289)
            }
            return true
        }
        
        // Check Port 5277 (Standard Headunit)
        if (checkPort(ip, 5277, timeout = 300)) {
            Log.i(TAG, "Found Headunit Server on $ip:5277")
            withContext(Dispatchers.Main) {
                listener.onServiceFound(ip, 5277)
            }
            return true
        }
        
        return false
    }

    private fun checkPort(ip: String, port: Int, timeout: Int = 500): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun collectInterfaceSuspects(suspects: MutableSet<String>) {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        // Heuristic: Gateway is usually .1 in the same subnet
                        val ipBytes = addr.address
                        ipBytes[3] = 1
                        val suspectIp = InetAddress.getByAddress(ipBytes).hostAddress
                        // Only add if it's not our own IP (though checking own IP is fast anyway)
                        suspects.add(suspectIp)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interface collection failed", e)
        }
    }

    private fun getSubnet(): String? {
        // Reuse similar logic to collectInterfaceSuspects but return subnet string
        try {
             val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
             for (networkInterface in interfaces) {
                 if (!networkInterface.isUp || networkInterface.isLoopback) continue
                 
                 for (addr in Collections.list(networkInterface.inetAddresses)) {
                     if (addr is Inet4Address) {
                         val host = addr.hostAddress
                         val lastDot = host.lastIndexOf('.')
                         if (lastDot > 0) {
                             return host.substring(0, lastDot)
                         }
                     }
                 }
             }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get subnet", e)
        }
        return null
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
    }
}