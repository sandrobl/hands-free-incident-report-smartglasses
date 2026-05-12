package com.unisg.hands_free_incident_report_smartglasses

import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private fun getSystemPermissions(): Array<String> {
        val perms = mutableListOf(*requiredPermissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return perms.toTypedArray()
    }

    private val permissionCheckLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //Check all the permissions and request if needed
        if (!hasPermissions()) {
            permissionCheckLauncher.launch(getSystemPermissions())
        }

        setContentView(R.layout.activity_main)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // This is crucial for HomeFragment to see the new action
    }

    override fun onStart() {
        super.onStart()
        // First, ensure the app has necessary Android permissions
        permissionCheckLauncher.launch(getSystemPermissions())
    }

    private fun hasPermissions(): Boolean {
        return getSystemPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

}