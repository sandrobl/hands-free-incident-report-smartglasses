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
import com.meta.wearable.dat.core.Wearables

class MainActivity : AppCompatActivity() {

    private fun getSystemPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
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
        if (allGranted) {
            Log.d("IncidentReportApp", "All system permissions granted. Initializing SDK...")
            initializeWearables()
        } else {
            Log.e("IncidentReportApp", "Not all permissions granted: $permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!hasPermissions()) {
            Log.d("IncidentReportApp", "Requesting system permissions...")
            permissionCheckLauncher.launch(getSystemPermissions())
        } else {
            initializeWearables()
        }
    }

    private fun initializeWearables() {
        Wearables.initialize(this).onFailure { error, _ ->
            Log.e("IncidentReportApp", "Failed to initialize Wearables SDK: ${error.toString()}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun hasPermissions(): Boolean {
        return getSystemPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

}