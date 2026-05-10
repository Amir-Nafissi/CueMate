package com.cuemate.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cuemate.CueMateApplication
import com.cuemate.ui.theme.CueMateTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CueMateViewModel by viewModels {
        (application as CueMateApplication).container.viewModelFactory
    }
    private lateinit var previewView: PreviewView

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        if (cameraGranted) viewModel.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContent {
            CueMateTheme {
                val state by viewModel.state.collectAsState()
                CueMateScreen(
                    state = state,
                    viewModel = viewModel,
                    previewView = previewView,
                    onStart = { requestCameraPermissionAndStart() },
                    onStop = { viewModel.stop() },
                    onSpeechToggle = { viewModel.setSpeechEnabled(it) },
                    onHapticsToggle = { viewModel.setHapticsEnabled(it) }
                )
            }
        }
    }

    private fun requestCameraPermissionAndStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            viewModel.start(this)
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                )
            )
        }
    }
}

@Composable
private fun CueMateScreen(
    state: PipelineState,
    viewModel: CueMateViewModel,
    previewView: PreviewView,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSpeechToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Camera Preview - Takes up 70% of screen when running
            if (state.isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.7f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Camera preview" },
                        update = {
                            viewModel.setPreviewView(it)
                        }
                    )
                    
                    // Debug overlay with smile detection info
                    state.lastCue?.let { cue ->
                        Text(
                            text = "Detected: ${cue.type}\nDirection: ${cue.direction}",
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(8.dp)
                        )
                    }
                }
            }
            
            // Control Panel - Takes up 30% of screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (state.isRunning) "Scanning" else "Idle",
                    modifier = Modifier.semantics { contentDescription = "Pipeline status" }
                )
                Button(
                    onClick = if (state.isRunning) onStop else onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = if (state.isRunning) "Stop scanning" else "Start scanning"
                        }
                ) {
                    Text(if (state.isRunning) "Stop" else "Start")
                }
                Column {
                    Text(
                        text = "Speech feedback",
                        modifier = Modifier.semantics { contentDescription = "Speech feedback label" }
                    )
                    Switch(
                        checked = state.speechEnabled,
                        onCheckedChange = onSpeechToggle,
                        modifier = Modifier.semantics { contentDescription = "Toggle speech feedback" }
                    )
                }
                Column {
                    Text(
                        text = "Haptic feedback",
                        modifier = Modifier.semantics { contentDescription = "Haptic feedback label" }
                    )
                    Switch(
                        checked = state.hapticsEnabled,
                        onCheckedChange = onHapticsToggle,
                        modifier = Modifier.semantics { contentDescription = "Toggle haptic feedback" }
                    )
                }
                state.errorMessage?.let {
                    Text(
                        text = "Error: $it",
                        color = Color.Red,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { contentDescription = "Error message" }
                    )
                }
            }
        }
    }
}
