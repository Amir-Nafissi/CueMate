package com.cuemate.logic

import android.util.Log
import com.cuemate.core.model.CueType
import com.cuemate.core.model.Direction
import com.cuemate.core.model.InferenceResult
import com.cuemate.core.model.PipelineConfig
import com.cuemate.core.model.SocialCue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import java.util.ArrayDeque

class CueFusionEngine {
    private val cueFlow = MutableSharedFlow<SocialCue>(extraBufferCapacity = 1)
    private val recentCues = ArrayDeque<SocialCue>(PipelineConfig.DEBOUNCE_FRAMES)

    fun cues(): Flow<SocialCue> = cueFlow.asSharedFlow()

    suspend fun process(inferenceResult: InferenceResult) {
        val cue = selectCue(inferenceResult)
        if (cue != null) {
            Log.d("CueFusionEngine", "Detected cue: ${cue.type}")
            if (isHandCue(cue.type)) {
                Log.d("CueFusionEngine", "Emitting hand cue immediately: ${cue.type}")
                cueFlow.emit(cue)
                recentCues.clear()
                return
            }
            recentCues.addLast(cue)
            while (recentCues.size > PipelineConfig.DEBOUNCE_FRAMES) {
                recentCues.removeFirst()
            }
            val stableCue = recentCues.takeIf { it.size == PipelineConfig.DEBOUNCE_FRAMES }?.let { frames ->
                val first = frames.first()
                if (frames.all { it.type == first.type && it.direction == first.direction }) first else null
            }
            if (stableCue != null) {
                Log.d("CueFusionEngine", "Emitting stable cue: ${stableCue.type}")
                cueFlow.emit(stableCue)
            } else {
                Log.d("CueFusionEngine", "Waiting for debounce stability... ${recentCues.size}/${PipelineConfig.DEBOUNCE_FRAMES}")
            }
        }
    }

    fun stabilize(source: Flow<InferenceResult>): Flow<SocialCue> {
        return source
            .mapNotNull { selectCue(it) }
            .distinctUntilChanged()
            .onEach { cueFlow.tryEmit(it) }
    }

    private fun selectCue(result: InferenceResult): SocialCue? {
        val handDetection = result.handDetections
            .maxByOrNull { it.confidence }
            ?.takeIf { it.confidence >= PipelineConfig.HAND_CONFIDENCE_THRESHOLD }
        val handCue = handDetection?.let { detection ->
            val normalizedLabel = normalizeGestureLabel(detection.gestureLabel)
            val cueType = when (normalizedLabel) {
                "wave" -> CueType.WAVE
                "point" -> CueType.POINT
                "thumbup", "thumbsup" -> CueType.THUMBS_UP
                "thumbdown", "thumbsdown" -> CueType.THUMBS_DOWN
                "openpalm" -> CueType.WAVE
                "closedfist" -> null
                "fistbump" -> CueType.FIST_BUMP
                else -> null
            }
            cueType?.let { type -> SocialCue(type, direction(detection.normalizedCenterX), detection.confidence, System.currentTimeMillis()) }
        }
        if (handCue != null) {
            return handCue
        }
        val face = result.faceDetections.maxByOrNull { it.confidence } ?: return null
        if (face.confidence < PipelineConfig.CONFIDENCE_THRESHOLD) {
            return SocialCue(CueType.NEUTRAL, direction(face.normalizedCenterX), face.confidence, System.currentTimeMillis())
        }
        // TEMPORARILY DISABLED: Face emotion detection (smile/frown/surprise) to focus on gesture testing.
        // All face detections return NEUTRAL cue. Hand gestures (wave, thumbs, fist bump) still active.
        return SocialCue(CueType.NEUTRAL, direction(face.normalizedCenterX), face.confidence, System.currentTimeMillis())
    }

    private fun normalizeGestureLabel(label: String): String {
        return label.lowercase().replace(Regex("[^a-z0-9]+"), "")
    }

    private fun isHandCue(type: CueType): Boolean {
        return type == CueType.WAVE || type == CueType.POINT || type == CueType.THUMBS_UP || type == CueType.THUMBS_DOWN || type == CueType.FIST_BUMP
    }

    private fun direction(centerX: Float): Direction = when {
        centerX <= 0.33f -> Direction.LEFT
        centerX <= 0.66f -> Direction.CENTER
        else -> Direction.RIGHT
    }
}
