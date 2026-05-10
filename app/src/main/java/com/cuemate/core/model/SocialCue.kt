package com.cuemate.core.model

data class SocialCue(
    val type: CueType,
    val direction: Direction,
    val confidence: Float,
    val timestamp: Long
)

enum class CueType {
    SMILE,
    FROWN,
    SURPRISE,
    NEUTRAL,
    WAVE,
    POINT,
    THUMBS_UP,
    THUMBS_DOWN,
    HANDSHAKE_REACH,
    FIST_BUMP
}

enum class Direction {
    LEFT,
    CENTER,
    RIGHT
}

interface FeedbackEngine {
    suspend fun provideFeedback(cue: SocialCue)
}

interface CueObserver {
    fun onCueDetected(cue: SocialCue)
}

object PipelineConfig {
    const val TARGET_FPS = 12
    const val CONFIDENCE_THRESHOLD = 0.65f
    const val HAND_CONFIDENCE_THRESHOLD = 0.45f
    const val DEBOUNCE_FRAMES = 3
    const val MIN_SPEECH_INTERVAL_MS = 1500L
}
