package com.andrerinas.wirelesshelper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleService: Button
    private lateinit var tvLogs: TextView
    private var isServiceRunning = false

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(WirelessLauncherService.EXTRA_LOG_MESSAGE)
            if (message != null) {
                appendLog(message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WirelessHelper)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        tvLogs = findViewById(R.id.tvLogs)

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopLauncherService()
            } else {
                checkPermissionsAndStart()
            }
        }

        val filter = IntentFilter(WirelessLauncherService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) { // TIRAMISU
            permissions.add("android.permission.POST_NOTIFICATIONS")
        }
        
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        } else {
            startLauncherService()
        }
    }

    private fun startLauncherService() {
        val intent = Intent(this, WirelessLauncherService::class.java).apply {
            action = WirelessLauncherService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= 26) { // O
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
        btnToggleService.text = "Stop Service"
        appendLog("Starting service...")
    }

    private fun stopLauncherService() {
        val intent = Intent(this, WirelessLauncherService::class.java).apply {
            action = WirelessLauncherService.ACTION_STOP
        }
        startService(intent)
        isServiceRunning = false
        btnToggleService.text = "Start Service"
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLogs.append("[" + timestamp + "] " + message + "\n")
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLauncherService()
        } else {
            appendLog("Permissions denied. Service cannot start.")
        }
    }
}
