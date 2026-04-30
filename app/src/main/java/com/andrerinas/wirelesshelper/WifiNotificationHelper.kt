package com.andrerinas.wirelesshelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WifiNotificationHelper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Checks if Wi-Fi is enabled based on the connection mode.
     * If mode is Phone Hotspot (1), it skips the Wi-Fi check.
     * If it is enabled (or skipped), executes the normal connection flow.
     * If not, shows either a UI Dialog or a System Notification.
     *
     * @param context The application or service context.
     * @param isFromUi Set to true if calling from an Activity to show a Popup Dialog instead of a Notification.
     * @param connectionMode The current connection mode (0=NSD, 1=Hotspot, 2=Passive, 3=Direct).
     * @param onConnectReady A lambda function containing the normal connection logic.
     */
    fun checkWifiAndConnect(
        context: Context, 
        isFromUi: Boolean = false, 
        connectionMode: Int = 0,
        onConnectReady: () -> Unit
    ) {
        // If mode is Phone Hotspot (1), we don't need Wi-Fi to be enabled
        if (connectionMode == 1) {
            onConnectReady()
            return
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Check if Wi-Fi is currently enabled
        if (wifiManager.isWifiEnabled) {
            onConnectReady() 
        } else {
            scope.launch {
                val wifiEnabled = withContext(Dispatchers.IO) {
                    tryEnableWifiWithRoot() && waitForWifiEnabled(wifiManager)
                }

                if (wifiEnabled) {
                    cancelNotification(context)
                    onConnectReady()
                } else if (isFromUi) {
                    showWifiDialog(context)
                } else {
                    showWifiNotification(context)
                }
            }
        }
    }

    private fun tryEnableWifiWithRoot(): Boolean {
        return runRootCommand("cmd wifi set-wifi-enabled enabled") ||
            runRootCommand("svc wifi enable")
    }

    private fun runRootCommand(command: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun waitForWifiEnabled(wifiManager: WifiManager): Boolean {
        repeat(10) {
            if (wifiManager.isWifiEnabled) return true
            delay(500)
        }
        return wifiManager.isWifiEnabled
    }

    private fun showWifiDialog(context: Context) {
        val title = context.getString(R.string.notification_wifi_off_title)
        val message = context.getString(R.string.notification_wifi_off_text)
        val actionText = context.getString(R.string.action_turn_on_wifi)

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(actionText) { _, _ ->
                val intentAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                context.startActivity(intentAction)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWifiNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "wireless_helper_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = context.getString(R.string.channel_name_alerts)
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intentAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intentAction,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.notification_wifi_off_title)
        val text = context.getString(R.string.notification_wifi_off_text)
        val actionText = context.getString(R.string.action_turn_on_wifi)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_wireless_helper) 
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_preferences, actionText, pendingIntent)
            .build()

        notificationManager.notify(1001, notification)
    }

    /**
     * Checks if there was a pending start request that couldn't proceed because Wi-Fi was off.
     * If Wi-Fi is now on and the trigger BT device is still connected, starts the service.
     */
    fun handlePendingWifiStart(context: Context) {
        val prefs = context.getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pending_wifi_start", false)) return

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        
        val targetMacs = prefs.getStringSet("auto_start_bt_macs", emptySet()) ?: emptySet()
        if (targetMacs.isEmpty()) {
            prefs.edit().remove("pending_wifi_start").apply()
            return
        }

        // Check if any of the target devices are currently connected
        // We check A2DP and HEADSET profiles as they are typical for cars
        adapter.getProfileProxy(context, object : android.bluetooth.BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                val connectedDevices = try {
                    proxy.connectedDevices
                } catch (e: SecurityException) {
                    emptyList()
                }
                val isStillConnected = connectedDevices.any { targetMacs.contains(it.address) }
                
                if (isStillConnected) {
                    android.util.Log.i("HUREV_WIFI", "Pending WiFi start: Target BT device is still connected. Starting service.")
                    prefs.edit().remove("pending_wifi_start").apply()
                    cancelNotification(context)
                    
                    val serviceIntent = Intent(context, WirelessHelperService::class.java).apply {
                        action = WirelessHelperService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, android.bluetooth.BluetoothProfile.A2DP)
    }

    /**
     * Dismisses the Wi-Fi alert notification.
     */
    fun cancelNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1001)
    }
}
