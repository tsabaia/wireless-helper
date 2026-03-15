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

object WifiNotificationHelper {

    /**
     * Checks if Wi-Fi is enabled. If it is, executes the normal connection flow.
     * If not, shows a notification prompting the user to turn it on.
     * * @param context The application or service context.
     * @param onConnectReady A lambda function containing the normal connection logic.
     */
    fun checkWifiAndConnect(context: Context, onConnectReady: () -> Unit) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Check if Wi-Fi is currently enabled
        if (wifiManager.isWifiEnabled) {
            // Wi-Fi is on, trigger the callback to proceed with the normal app flow
            onConnectReady() 
        } else {
            // Wi-Fi is off, show the notification to the user
            showWifiNotification(context)
        }
    }

    /**
     * Builds and displays a high-priority notification with a button
     * to open the system's Wi-Fi settings panel.
     */
    private fun showWifiNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "wireless_helper_alerts"

        // 1. Create Notification Channel (Required for Android 8.0/Oreo and above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = context.getString(R.string.channel_name_alerts)
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        // 2. Create Intent to open the Wi-Fi settings panel
        // Use Settings.Panel.ACTION_WIFI for Android 10+ (API 29) to show a floating panel
        // Use Settings.ACTION_WIFI_SETTINGS for older versions
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

        // 3. Build the Notification using string resources for multi-language support
        val title = context.getString(R.string.notification_wifi_off_title)
        val text = context.getString(R.string.notification_wifi_off_text)
        val actionText = context.getString(R.string.action_turn_on_wifi)

        val notification = NotificationCompat.Builder(context, channelId)
            // TODO: Replace 'ic_launcher_foreground' with the actual drawable icon of the app
            .setSmallIcon(R.drawable.ic_launcher_foreground) 
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Add the action button that triggers the PendingIntent
            .addAction(android.R.drawable.ic_menu_preferences, actionText, pendingIntent)
            .build()

        // 4. Show the notification
        // Using a fixed ID (1001) so it updates the same notification if triggered multiple times
        notificationManager.notify(1001, notification)
    }
}
