package com.lumacore.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.lumacore.camera.ui.CameraScreen
import com.lumacore.camera.ui.CameraViewModel
import com.lumacore.camera.ui.theme.Black
import com.lumacore.camera.ui.theme.LumaCoreTheme

class MainActivity : ComponentActivity() {

    private val cameraViewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            LumaCoreTheme {
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions: Map<String, Boolean> ->
                    val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
                    cameraViewModel.onCameraPermissionResult(cameraGranted)
                }

                LaunchedEffect(Unit) {
                    val cameraPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    val audioPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!cameraPermission || !audioPermission) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    } else {
                        cameraViewModel.onCameraPermissionResult(true)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Black
                ) {
                    CameraScreen(viewModel = cameraViewModel)
                }
            }
        }
    }
}
