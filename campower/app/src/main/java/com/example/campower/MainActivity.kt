package com.example.campower

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("CamPower", "hello!")

        val permissions = mutableListOf(
            Manifest.permission.CAMERA
        )

        // Add FOREGROUND_SERVICE_CAMERA permission only on API > 30
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) { // API 30 corresponds to Build.VERSION_CODES.R
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            Log.d("CamPower", "request permissions")
            requestPermissions(missing.toTypedArray(), 0)
        }
        else
        {
            Log.d("CamPower", "Start service")
            startTestService()
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startTestService()
        } else {
            // Handle permission denial appropriately.
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startTestService() {
        val intent = Intent(this, TestService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("CamPowerMDB", "start service1")
            startForegroundService(intent)
        } else {
            Log.d("CamPowerMDB", "start service2")
            startService(intent)
        }
        finish() // close activity since UI is not needed
    }
}
