package com.andrerinas.wirelesshelper.strategy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope

class StrategySharedNetwork(context: Context, scope: CoroutineScope) : BaseStrategy(context, scope) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val SERVICE_TYPE = "_aawireless._tcp" // Standard without dot

    override fun start() {
        Log.i(TAG, "Strategy: Shared Network (NSD)")
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started")
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "NSD Service found: ${service.serviceName}")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, err: Int) {
                        Log.e(TAG, "NSD Resolve failed: $err")
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val ip = si.host.hostAddress
                        Log.i(TAG, "NSD Resolved IP: $ip")
                        ip?.let { 
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
        try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        discoveryListener = null
    }
}