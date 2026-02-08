package com.andrerinas.wirelesshelper.strategy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import com.andrerinas.wirelesshelper.connection.AapProxy

abstract class BaseStrategy(protected val context: Context, private val scope: CoroutineScope) : ConnectionStrategy {

    interface StateListener {
        fun onProxyConnected()
        fun onProxyDisconnected()
    }

    protected val TAG = "HUREV_WIFI"
    private val carConnectionUri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()
    private var activeProxy: AapProxy? = null
    var stateListener: StateListener? = null

    companion object {
        val isLaunching = AtomicBoolean(false)
    }

    protected fun launchAndroidAuto(hostIp: String, forceFakeNetwork: Boolean = false) {
        if (isLaunching.get()) return
        if (!isLaunching.compareAndSet(false, true)) return
        
        Log.i(TAG, "Strategy triggering PROXY launch for $hostIp")
        
        stop()

        scope.launch {
            try {
                // 1. Start the Proxy Server with a listener
                val proxy = AapProxy(hostIp, listener = object : AapProxy.Listener {
                    override fun onConnected() {
                        Log.i(TAG, "AA is now flowing through proxy")
                        stateListener?.onProxyConnected()
                    }
                    override fun onDisconnected() {
                        Log.i(TAG, "AA proxy connection lost")
                        stateListener?.onProxyDisconnected()
                    }
                })
                
                activeProxy = proxy
                val localPort = proxy.start()

                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null

                val wifiInfo: Parcelable? = try {
                    val clazz = Class.forName("android.net.wifi.WifiInfo")
                    val constructor = clazz.getDeclaredConstructor()
                    constructor.isAccessible = true
                    constructor.newInstance() as Parcelable
                } catch (e: Exception) { null }

                val intent = Intent().apply {
                    setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                    putExtra("PARAM_SERVICE_PORT", localPort)
                    activeNetwork?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) }
                    wifiInfo?.let { putExtra("wifi_info", it) }
                }

                Log.i(TAG, "Firing Proxy Intent. LocalPort=$localPort")
                context.startActivity(intent)

                // The lock stays active until proxy confirms connection or timeout
                delay(15000) 
                isLaunching.set(false)

            } catch (e: Exception) {
                Log.e(TAG, "Proxy Launch failed: ${e.message}")
                activeProxy?.stop()
                activeProxy = null
                isLaunching.set(false)
            }
        }
    }

    override fun stop() {}

    fun cleanup() {
        activeProxy?.stop()
        activeProxy = null
    }
}