package com.andrerinas.wirelesshelper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleService: Button
    private lateinit var layoutConnectionMode: View
    private lateinit var tvConnectionModeValue: TextView
    private var isServiceRunning = false

    private val connectionModes by lazy {
        arrayOf(
            getString(R.string.mode_network_discovery),
            getString(R.string.mode_wifi_direct),
            getString(R.string.mode_hotspot_phone),
            getString(R.string.mode_hotspot_headunit)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WirelessHelper)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        layoutConnectionMode = findViewById(R.id.layoutConnectionMode)
        tvConnectionModeValue = findViewById(R.id.tvConnectionModeValue)

        setupConnectionModeSetting()
        restoreServiceState()

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopLauncherService()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    private fun setupConnectionModeSetting() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val savedMode = prefs.getInt("connection_mode", 0)
        
        tvConnectionModeValue.text = connectionModes[savedMode]

        layoutConnectionMode.setOnClickListener {
            val currentMode = prefs.getInt("connection_mode", 0)
            
            MaterialAlertDialogBuilder(this, R.style.Theme_WirelessHelper)
                .setTitle(R.string.connection_mode_label)
                .setSingleChoiceItems(connectionModes, currentMode) { dialog, which ->
                    prefs.edit().putInt("connection_mode", which).apply()
                    tvConnectionModeValue.text = connectionModes[which]
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun restoreServiceState() {
        updateButtonState(false)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
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
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonState(true)
    }

    private fun stopLauncherService() {
        val intent = Intent(this, WirelessLauncherService::class.java).apply {
            action = WirelessLauncherService.ACTION_STOP
        }
        startService(intent)
        updateButtonState(false)
    }

    private fun updateButtonState(running: Boolean) {
        isServiceRunning = running
        btnToggleService.text = if (running) getString(R.string.stop_service) else getString(R.string.start_service)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startLauncherService()
        }
    }
}