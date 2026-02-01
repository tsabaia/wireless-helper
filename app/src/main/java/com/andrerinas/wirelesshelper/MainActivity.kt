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

    private lateinit var spinnerConnectionMode: android.widget.Spinner

    private var isServiceRunning = false



    override fun onCreate(savedInstanceState: Bundle?) {

        setTheme(R.style.Theme_WirelessHelper)

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)



        btnToggleService = findViewById(R.id.btnToggleService)

        spinnerConnectionMode = findViewById(R.id.spinnerConnectionMode)



        setupSpinner()

        restoreServiceState()



        btnToggleService.setOnClickListener {

            if (isServiceRunning) {

                stopLauncherService()

            }

            else {

                checkPermissionsAndStart()

            }

        }

    }



    private fun setupSpinner() {

        val modes = arrayOf(

            getString(R.string.mode_network_discovery),

            getString(R.string.mode_wifi_direct),

            getString(R.string.mode_hotspot_phone),

            getString(R.string.mode_hotspot_headunit)

        )

        // Use a simple adapter for now, maybe custom layout later for better "Settings" look

        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)

        spinnerConnectionMode.adapter = adapter



        val prefs = getSharedPreferences("WirelessHelperPrefs", Context.MODE_PRIVATE)

        val savedMode = prefs.getInt("connection_mode", 0)

        spinnerConnectionMode.setSelection(savedMode)



        spinnerConnectionMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {

            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {

                prefs.edit().putInt("connection_mode", position).apply()

            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}

        }

    }



    private fun restoreServiceState() {

        // Here we should ideally check if service is running

        // For now, reset to default state

        updateButtonState(false)

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

        }

        else {

            startLauncherService()

        }

    }



    private fun startLauncherService() {

        val intent = Intent(this, WirelessLauncherService::class.java).apply {

            action = WirelessLauncherService.ACTION_START

        }

        if (Build.VERSION.SDK_INT >= 26) { // O

            startForegroundService(intent)

        }

        else {

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

        if (running) {

            btnToggleService.text = getString(R.string.stop_service)

            // Optional: Change color to Red or something to indicate STOP

        }

        else {

            btnToggleService.text = getString(R.string.start_service)

        }

    }



    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {

            startLauncherService()

        }

    }

}
