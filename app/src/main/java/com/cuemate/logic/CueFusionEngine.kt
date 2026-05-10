package com.cuemate.logic

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
        val cue = selectCue(inferenceResult) ?: return
        recentCues.addLast(cue)
        while (recentCues.size > PipelineConfig.DEBOUNCE_FRAMES) {
            recentCues.removeFirst()
        }
        val stableCue = recentCues.takeIf { it.size == PipelineConfig.DEBOUNCE_FRAMES }?.let { frames ->
            val first = frames.first()
            if (frames.all { it.type == first.type && it.direction == first.direction }) first else null
        }
        if (stableCue != null) {
            cueFlow.emit(stableCue)
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
            ?.takeIf { it.confidence >= PipelineConfig.CONFIDENCE_THRESHOLD }
        val handCue = handDetection?.let { detection ->
            val cueType = when (detection.gestureLabel.lowercase()) {
                    "wave" -> CueType.WAVE
                    "point" -> CueType.POINT
                    "thumb_up", "thumbs_up", "thumbs up" -> CueType.THUMBS_UP
                    "open_palm", "open palm" -> CueType.HANDSHAKE_REACH
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
        val type = when {
            face.smileScore >= 0.65f -> CueType.SMILE
            face.surpriseScore >= 0.65f -> CueType.SURPRISE
            face.frownScore >= 0.65f -> CueType.FROWN
            else -> CueType.NEUTRAL
        }
        return SocialCue(type, direction(face.normalizedCenterX), face.confidence, System.currentTimeMillis())
    }

    private fun direction(centerX: Float): Direction = when {
        centerX <= 0.33f -> Direction.LEFT
        centerX <= 0.66f -> Direction.CENTER
        else -> Direction.RIGHT
    }
}
