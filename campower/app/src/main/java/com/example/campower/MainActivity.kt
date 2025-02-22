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
import androidx.core.app.ActivityCompat
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("CamPower", "hello!")

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        val isCameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        Log.d("CamPower", "CAMERA permission granted: $isCameraGranted")

        if (missing.isNotEmpty()) {
            Log.d("CamPower", "request permissions")
            requestPermissions(missing.toTypedArray(), 0)
        }
        else
        {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }

            Log.d("CamPower", "Start service")
            startTestService()
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Log.d("CamPower", "Permission granted")
            startTestService()
        } else {
            Log.d("CamPower", "Permission denied!")
            val shouldExplain = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)
            Log.d("CamPower", "Should show rationale: $shouldExplain")

            // Handle permission denial appropriately.
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun startTestService() {
        val intent = Intent(this, TestService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("CamPowerMDB", "startforegroundservice1")
            startForegroundService(intent)
        } else {
            Log.d("CamPowerMDB", "start service1")
            startService(intent)
        }
        finish() // close activity since UI is not needed
    }
}
