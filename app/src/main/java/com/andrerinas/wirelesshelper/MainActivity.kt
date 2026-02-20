package com.andrerinas.wirelesshelper

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.andrerinas.wirelesshelper.WifiJobService

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

    private lateinit var tvVersionValue: TextView
    private lateinit var layoutAbout: View

    private var isServiceRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    
    private val statusPoller = object : Runnable {
        override fun run() {
            val running = WirelessHelperService.isRunning
            val connected = WirelessHelperService.isConnected
            updateButtonState(running, connected)
            handler.postDelayed(this, 1000)
        }
    }

    private val connectionModes by lazy {
        arrayOf(
            getString(R.string.mode_nsd),
            getString(R.string.mode_hotspot_phone),
            getString(R.string.mode_passive),
            getString(R.string.mode_wifi_direct)
        )
    }

    private val autoStartModes by lazy {
        arrayOf(
            getString(R.string.auto_start_no),
            getString(R.string.auto_start_bt),
            "On Specific WiFi connection"
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

        tvVersionValue = findViewById(R.id.tvVersionValue)
        layoutAbout = findViewById(R.id.layoutAbout)
        
        tvVersionValue.text = BuildConfig.VERSION_NAME
    }

    private fun setupListeners() {
        layoutAbout.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.about)
                .setMessage("Wireless Helper is a trigger app for Headunit Revived.\n\nDeveloped by André Rinas\n© 2026")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        btnToggleService.setOnClickListener {
            if (isServiceRunning) stopLauncherService() else checkPermissionsAndStart()
        }

        layoutConnectionMode.setOnClickListener {
            val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
            val currentMode = prefs.getInt("connection_mode", 0)
            
            MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                .setTitle(R.string.connection_mode_label)
                .setSingleChoiceItems(connectionModes, currentMode) { dialog, which ->
                    prefs.edit { putInt("connection_mode", which) }
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
                    prefs.edit { putInt("auto_start_mode", which) }
                    updateAutoStartUI(which)
                    
                    if (which == 2) {
                        WifiJobService.schedule(this@MainActivity)
                    } else {
                        WifiJobService.cancel(this@MainActivity)
                    }
                    
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        layoutBluetoothDevice.setOnClickListener {
            showBluetoothDeviceSelector()
        }

        layoutWifiNetwork.setOnClickListener {
            showWifiSelector()
        }
    }

    private fun showWifiSelector() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        val savedSsid = prefs.getString("auto_start_wifi_ssid", "")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ssid = wifiManager.connectionInfo.ssid
        val currentSsid = ssid?.replace("\"", "") ?: ""
        
        val displaySsid = if (currentSsid.isNotEmpty() && currentSsid != "<unknown ssid>") currentSsid else savedSsid

        val input = android.widget.EditText(this).apply {
            setHint("Enter WiFi SSID")
            setText(displaySsid)
            setTextColor(ContextCompat.getColor(context, R.color.text_title))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_subtitle))
        }

        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(64, 24, 64, 24)
        container.addView(input, params)

        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle("Auto-start WiFi")
            .setMessage("Service will start automatically when connected to this network:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newSsid = input.text.toString().trim()
                if (newSsid.isNotEmpty()) {
                    prefs.edit { putString("auto_start_wifi_ssid", newSsid) }
                    tvWifiNetworkValue.text = newSsid
                    WifiJobService.schedule(this@MainActivity)
                    
                    if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        MaterialAlertDialogBuilder(this@MainActivity, R.style.DarkAlertDialog)
                            .setTitle("Background Location Needed")
                            .setMessage("To detect WiFi while the app is closed, please set Location permission to 'Allow all the time' in Android settings.")
                            .setPositiveButton("Settings") { _, _ ->
                                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            }
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Auto-start linked to $newSsid", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Request Permissions") { _, _ ->
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 102)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBluetoothDeviceSelector() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), 101)
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        val bondedDevices = adapter.bondedDevices.toList()

        if (bondedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_LONG).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle("Select Bluetooth Device")
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
                prefs.edit { 
                    putString("auto_start_bt_mac", device.address)
                    putString("auto_start_bt_name", device.name)
                }
                tvBluetoothDeviceValue.text = device.name
                Toast.makeText(this, "Auto-start linked to ${device.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreState() {
        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)
        
        val connMode = prefs.getInt("connection_mode", 0)
        tvConnectionModeValue.text = connectionModes.getOrElse(connMode) { connectionModes[0] }

        val autoMode = prefs.getInt("auto_start_mode", 0)
        updateAutoStartUI(autoMode)
        
        tvBluetoothDeviceValue.text = prefs.getString("auto_start_bt_name", getString(R.string.not_set))
        tvWifiNetworkValue.text = prefs.getString("auto_start_wifi_ssid", getString(R.string.not_set))

        updateButtonState(WirelessHelperService.isRunning, WirelessHelperService.isConnected)
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
            2 -> { // WiFi
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
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add("android.permission.BLUETOOTH_CONNECT")
        }
        
        // Needed for WiFi SSID access
        permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        
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
        updateButtonState(true, false)
    }

    private fun stopLauncherService() {
        val intent = Intent(this, WirelessHelperService::class.java).apply {
            action = WirelessHelperService.ACTION_STOP
        }
        startService(intent)
        updateButtonState(false, false)
    }

    private fun updateButtonState(running: Boolean, connected: Boolean = false) {
        isServiceRunning = running
        
        val colorTeal = ContextCompat.getColor(this, R.color.brand_teal)
        val colorGreen = ContextCompat.getColor(this, android.R.color.holo_green_dark)
        
        if (connected) {
            btnToggleService.text = "Connected"
            btnToggleService.background.setTint(colorGreen)
            startPulseAnimation(1500) // Slow pulse
        } else if (running) {
            btnToggleService.text = getString(R.string.stop_service)
            btnToggleService.background.setTint(colorTeal)
            startPulseAnimation(800) // Fast pulse
        } else {
            btnToggleService.text = getString(R.string.start_service)
            btnToggleService.background.setTint(colorTeal)
            stopPulseAnimation()
        }
    }

    private fun startPulseAnimation(duration: Long) {
        if (pulseAnimator != null && pulseAnimator!!.isRunning && pulseAnimator!!.duration == duration) {
            return
        }
        stopPulseAnimation()
        
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            btnToggleService,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f, 1.0f)
        ).apply {
            this.duration = duration
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        btnToggleService.scaleX = 1.0f
        btnToggleService.scaleY = 1.0f
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusPoller)
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
                    .setTitle("Battery Optimization detected")
                    .setMessage("To allow Auto-start on WiFi/Bluetooth, please set this app to 'Unrestricted' in battery settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (requestCode == 100) startLauncherService()
            if (requestCode == 101) showBluetoothDeviceSelector()
            if (requestCode == 102) showWifiSelector()
        }
    }
}
