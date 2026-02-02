package com.andrerinas.wirelesshelper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggleService: Button
    
    // Connection Mode
    private lateinit var layoutConnectionMode: View
    private lateinit var tvConnectionModeValue: TextView

    // Auto Start
    private lateinit var layoutAutoStart: View
    private lateinit var tvAutoStartValue: TextView

    // Conditional Options
    private lateinit var layoutBluetoothDevice: View
    private lateinit var tvBluetoothDeviceValue: TextView

    private lateinit var layoutWifiNetwork: View
    private lateinit var tvWifiNetworkValue: TextView

    private var isServiceRunning = false

    private val connectionModes by lazy {
        arrayOf(
            getString(R.string.mode_network_discovery),
            getString(R.string.mode_wifi_direct),
            getString(R.string.mode_hotspot_phone),
            getString(R.string.mode_hotspot_headunit)
        )
    }

    private val autoStartModes by lazy {
        arrayOf(
            getString(R.string.auto_start_no),
            getString(R.string.auto_start_bt),
            getString(R.string.auto_start_wifi)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_WirelessHelper)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
        restoreState()
    }

    private fun initializeViews() {
        btnToggleService = findViewById(R.id.btnToggleService)
        
        layoutConnectionMode = findViewById(R.id.layoutConnectionMode)
        tvConnectionModeValue = findViewById(R.id.tvConnectionModeValue)

        layoutAutoStart = findViewById(R.id.layoutAutoStart)
        tvAutoStartValue = findViewById(R.id.tvAutoStartValue)

        layoutBluetoothDevice = findViewById(R.id.layoutBluetoothDevice)
        tvBluetoothDeviceValue = findViewById(R.id.tvBluetoothDeviceValue)

        layoutWifiNetwork = findViewById(R.id.layoutWifiNetwork)
        tvWifiNetworkValue = findViewById(R.id.tvWifiNetworkValue)
    }

    private fun setupListeners() {
        btnToggleService.setOnClickListener {
            if (isServiceRunning) stopLauncherService() else checkPermissionsAndStart()
        }

        layoutConnectionMode.setOnClickListener {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("connection_mode", 0)
            
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.connection_mode_label)
                .setSingleChoiceItems(connectionModes, currentMode) { dialog, which ->
                    prefs.edit().putInt("connection_mode", which).apply()
                    tvConnectionModeValue.text = connectionModes[which]
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        layoutAutoStart.setOnClickListener {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("auto_start_mode", 0)

            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.auto_start_label)
                .setSingleChoiceItems(autoStartModes, currentMode) { dialog, which ->
                    prefs.edit().putInt("auto_start_mode", which).apply()
                    updateAutoStartUI(which)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        layoutBluetoothDevice.setOnClickListener {
            Toast.makeText(this, "Select Bluetooth Device (Coming Soon)", Toast.LENGTH_SHORT).show()
        }

        layoutWifiNetwork.setOnClickListener {
            Toast.makeText(this, "Select Wifi Network (Coming Soon)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreState() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        
        val connMode = prefs.getInt("connection_mode", 0)
        tvConnectionModeValue.text = connectionModes.getOrElse(connMode) { connectionModes[0] }

        val autoMode = prefs.getInt("auto_start_mode", 0)
        updateAutoStartUI(autoMode)

        updateButtonState(false)
    }

    private fun updateAutoStartUI(mode: Int) {
        tvAutoStartValue.text = autoStartModes.getOrElse(mode) { autoStartModes[0] }
        
        when (mode) {
            0 -> { // No
                layoutBluetoothDevice.visibility = View.GONE
                layoutWifiNetwork.visibility = View.GONE
                layoutAutoStart.setBackgroundResource(R.drawable.bg_item_bottom)
            }
            1 -> { // Bluetooth
                layoutBluetoothDevice.visibility = View.VISIBLE
                layoutWifiNetwork.visibility = View.GONE
                layoutAutoStart.setBackgroundResource(R.drawable.bg_item_middle)
                layoutBluetoothDevice.setBackgroundResource(R.drawable.bg_item_bottom)
            }
            2 -> { // Wifi
                layoutBluetoothDevice.visibility = View.GONE
                layoutWifiNetwork.visibility = View.VISIBLE
                layoutAutoStart.setBackgroundResource(R.drawable.bg_item_middle)
                layoutWifiNetwork.setBackgroundResource(R.drawable.bg_item_bottom)
            }
        }
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
        val intent = Intent(this, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateButtonState(true)
    }

    private fun stopLauncherService() {
        val intent = Intent(this, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_STOP
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