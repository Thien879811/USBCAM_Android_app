package com.example.usbcam

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: com.example.usbcam.viewmodel.MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)


        val factory = com.example.usbcam.viewmodel.MainViewModelFactory(application)
        viewModel =
                androidx.lifecycle.ViewModelProvider(this, factory)[
                        com.example.usbcam.viewmodel.MainViewModel::class.java]

        // 5. Start Sync Worker
        viewModel.startSyncWorker(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_CODE_PERMISSIONS
            )
        }
    }


    private fun startCamera() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.fragment_container, DemoFragment())
                    .commit()
        }
    }

    private fun allPermissionsGranted() =
            REQUIRED_PERMISSIONS.all {
                androidx.core.content.ContextCompat.checkSelfPermission(baseContext, it) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                android.widget.Toast.makeText(
                                this,
                                "Permissions not granted by the user.",
                                android.widget.Toast.LENGTH_SHORT
                        )
                        .show()
                finish()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
                mutableListOf(
                                android.Manifest.permission.CAMERA,
                                android.Manifest.permission.RECORD_AUDIO
                        )
                        .apply {
                            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P
                            ) {
                                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                        .toTypedArray()
    }
}
