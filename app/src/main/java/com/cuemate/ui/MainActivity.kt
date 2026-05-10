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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
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
                    onSpeechToggle = { viewModel.setSpeechEnabled(it) },
                    onHapticsToggle = { viewModel.setHapticsEnabled(it) },
                    onCameraToggle = { viewModel.toggleCameraFacing() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestCameraPermissionAndStart()
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    private fun requestCameraPermissionAndStart() {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            viewModel.start(this)
        } else {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
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
    onSpeechToggle: (Boolean) -> Unit,
    onHapticsToggle: (Boolean) -> Unit,
    onCameraToggle: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
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

            state.lastCue?.let { cue ->
                Text(
                    text = "Detected: ${cue.type}\nDirection: ${cue.direction}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(8.dp)
                )
            }

            Text(
                text = state.debugText,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 164.dp)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(8.dp)
                    .semantics { contentDescription = "Model debug text" }
            )

            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(156.dp)
                    .background(Color(0xFFF0E3FF).copy(alpha = 0.98f))
            ) {
                val halfWidth = maxWidth / 2
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FeedbackToggleButton(
                            label = if (state.hapticsEnabled) "Haptics\nON" else "Haptics\nOFF",
                            modifier = Modifier
                                .width(halfWidth - 15.dp)
                                .fillMaxHeight(),
                            enabled = state.hapticsEnabled,
                            onClick = { onHapticsToggle(!state.hapticsEnabled) }
                        )

                        FeedbackToggleButton(
                            label = if (state.speechEnabled) "Sound\nON" else "Sound\nOFF",
                            modifier = Modifier
                                .width(halfWidth - 15.dp)
                                .fillMaxHeight(),
                            enabled = state.speechEnabled,
                            onClick = { onSpeechToggle(!state.speechEnabled) }
                        )
                    }

                    Divider(
                        color = Color(0xFF6B5A7A).copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(vertical = 18.dp)
                            .width(1.dp)
                    )
                }
            }

            state.errorMessage?.let {
                Text(
                    text = "Error: $it",
                    color = Color.Red,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Error message" }
                )
            }

            Button(
                onClick = onCameraToggle,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111111).copy(alpha = 0.82f)),
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.8f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .width(48.dp)
                    .height(48.dp)
                    .semantics {
                        contentDescription = if (state.isFrontCamera) "Switch to rear camera" else "Switch to front camera"
                    }
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun FeedbackToggleButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val background = if (enabled) Color(0xFF2E7D32) else Color(0xFF7A6D8A)
    val borderColor = if (enabled) Color(0xFFA5D6A7) else Color(0xFFD0C2E1)
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = background),
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .semantics { contentDescription = label.replace("\n", " ") },
        shape = RoundedCornerShape(22.dp)
    ) {
        Text(
            text = label,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
