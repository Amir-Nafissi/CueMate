package com.cuemate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DebugOverlay(state: PipelineState, modifier: Modifier = Modifier) {
    if (!state.debugOverlayEnabled) return

    val fps = state.fps
    if (fps < 10) return // skip drawing if too slow

    val faceColor = Color(0xFF00FF00)
    val leftHandColor = Color(0xFF0088FF)
    val rightHandColor = Color(0xFFFF8800)

    val pointRadiusDp = 4.dp
    val facePointRadiusDp = 2.dp
    val alpha = 0.8f

    val pointRadiusPx = with(LocalDensity.current) { pointRadiusDp.toPx() }
    val facePointRadiusPx = with(LocalDensity.current) { facePointRadiusDp.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // Simplified debug panel while canvas overlay is being stabilized
        androidx.compose.foundation.layout.Column(modifier = Modifier
            .fillMaxSize()
        ) {
            androidx.compose.material3.Text(text = "FPS: ${state.fps}", style = TextStyle(fontSize = 14.sp))
            androidx.compose.material3.Text(text = "Inference: ${state.inferenceTimeMs}ms", style = TextStyle(fontSize = 14.sp))
            androidx.compose.material3.Text(text = "Faces: ${state.faceCount}", style = TextStyle(fontSize = 14.sp))
            androidx.compose.material3.Text(text = "Hands: ${state.handCount}", style = TextStyle(fontSize = 14.sp))
            androidx.compose.material3.Text(text = "Cues: ${state.activeCues.joinToString(",")}", style = TextStyle(fontSize = 14.sp))
        }
    }
}
