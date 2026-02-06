package com.andrerinas.wirelesshelper.strategy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Parcel
import android.util.Log
import com.andrerinas.headunitrevived.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

abstract class BaseStrategy(protected val context: Context, private val scope: CoroutineScope) : ConnectionStrategy {

    protected val TAG = "HUREV_WIFI"
    private val PORT_AA_WIFI_SERVICE = 5288
    private val carConnectionUri = Uri.Builder().scheme("content").authority("androidx.car.app.connection").build()

    companion object {
        // Global lock across all strategy instances
        val isLaunching = AtomicBoolean(false)
    }

    protected fun launchAndroidAuto(hostIp: String, forceFakeNetwork: Boolean = false) {
        if (isLaunching.get()) {
            Log.d(TAG, "Launch already in progress or AA active. Skipping $hostIp")
            return
        }

        // Check if already connected via CarConnection API
        if (isAlreadyConnected()) {
            Log.i(TAG, "Android Auto already active. No need to launch.")
            isLaunching.set(true) // Keep locked
            stop()
            return
        }

        if (!isLaunching.compareAndSet(false, true)) return
        
        Log.i(TAG, "Strategy triggering launch for $hostIp (FakeNet: $forceFakeNetwork)")
        
        // STOP discovery immediately to prevent race conditions and multiple intents
        stop()

        scope.launch {
            try {
                var finalIp = hostIp
                if (finalIp == "127.0.0.1" && (Build.MODEL.contains("Emulator") || Build.HARDWARE.contains("ranchu"))) {
                    finalIp = "10.0.2.2"
                }

                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.activeNetwork else null
                
                var networkToUse: Network? = null
                var isWifi = false
                
                if (activeNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                    if (isWifi) {
                        networkToUse = activeNetwork
                    }
                }
                
                // If forceFakeNetwork is true, override logic (used for explicit testing)
                if (forceFakeNetwork && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    networkToUse = createFakeNetwork()
                }

                // Create Blank WifiInfo via reflection
                val wifiInfo: WifiInfo? = try {
                    val clazz = Class.forName("android.net.wifi.WifiInfo")
                    val constructor = clazz.getDeclaredConstructor()
                    constructor.isAccessible = true
                    constructor.newInstance() as WifiInfo
                } catch (e: Exception) { null }

                AppLog.d("Ip: $finalIp, Net: $networkToUse, IsWifi: $isWifi")

                val intent = Intent().apply {
                    setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("PARAM_HOST_ADDRESS", finalIp)
                    putExtra("PARAM_SERVICE_PORT", PORT_AA_WIFI_SERVICE)
                    
                    // Only pass network if it is explicitly WiFi or Fake. Otherwise let AA decide.
                    if (networkToUse != null) {
                        putExtra("PARAM_SERVICE_WIFI_NETWORK", networkToUse)
                    } else {
                        Log.i(TAG, "No WiFi network object passed to AA (Hotspot mode)")
                    }
                    
                    putExtra("wifi_info", wifiInfo)
                }

                Log.i(TAG, "Sending Intent. IP=$finalIp, Net=$networkToUse, IsWifi=$isWifi")
                context.startActivity(intent)

                // Long cooldown (30s) to let Android Auto establish the connection
                delay(30000)
                isLaunching.set(false)
                
                // If we are still not connected after 30s, the service will restart discovery via CarConnection observer in WirelessHelperService
                if (context is Service && !isAlreadyConnected()) {
                    Log.w(TAG, "Launch timeout reached and no connection detected.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Launch failed: ${e.message}")
                isLaunching.set(false)
            }
        }
    }

    private fun isAlreadyConnected(): Boolean {
        return try {
            val cursor: Cursor? = context.contentResolver.query(carConnectionUri, arrayOf("CarConnectionState"), null, null, null)
            val status = cursor?.use {
                if (it.moveToNext()) {
                    val index = it.getColumnIndex("CarConnectionState")
                    if (index >= 0) it.getInt(index) else 0
                } else 0
            } ?: 0
            status > 0
        } catch (e: Exception) { false }
    }

    private fun createFakeNetwork(): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val parcel = Parcel.obtain()
            parcel.writeInt(9999)
            parcel.setDataPosition(0)
            val network = Network.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            network
        } catch (e: Exception) { null }
    }
}