package com.cuemate.ui

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.LifecycleOwner
import com.cuemate.camera.CameraStreamProvider
import com.cuemate.camera.CameraFrame
import com.cuemate.core.model.SocialCue
import com.cuemate.feedback.AccessibilityFeedbackManager
import com.cuemate.inference.MediaPipeInferenceEngine
import com.cuemate.logic.CueFusionEngine
import com.cuemate.settings.UserSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PipelineState(
    val isRunning: Boolean = false,
    val lastCue: SocialCue? = null,
    val debugText: String = "Idle",
    val errorMessage: String? = null,
    val speechEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true
    ,
    val debugOverlayEnabled: Boolean = false,
    val isFrontCamera: Boolean = false,
    val fps: Int = 0,
    val inferenceTimeMs: Long = 0,
    val faceCount: Int = 0,
    val handCount: Int = 0,
    val activeCues: List<String> = emptyList(),
    val handLabels: List<String> = emptyList(),
    val lastDx: Float? = null,
    val lastDy: Float? = null,
    val lastSmile: Float? = null,
    val faceLandmarks: List<List<com.cuemate.core.model.NormalizedPoint>> = emptyList(),
    val handLandmarks: List<List<com.cuemate.core.model.NormalizedPoint>> = emptyList()
)

class CueMateViewModel(
    private val cameraStreamProvider: CameraStreamProvider,
    private val cueFusionEngine: CueFusionEngine,
    private val feedbackEngine: AccessibilityFeedbackManager,
    private val inferenceEngine: MediaPipeInferenceEngine,
    private val settingsRepository: UserSettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()
    private var processingJob: Job? = null
    private var feedbackJob: Job? = null
    private var settingsJob: Job? = null
    private var activeLifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    init {
        settingsJob = viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.value = _state.value.copy(
                    speechEnabled = settings.speechEnabled,
                    hapticsEnabled = settings.hapticsEnabled
                )
                feedbackEngine.setSpeechEnabled(settings.speechEnabled)
                feedbackEngine.setHapticsEnabled(settings.hapticsEnabled)
                feedbackEngine.setSpeechRate(settings.speechRate)
                feedbackEngine.setHapticIntensity(settings.hapticIntensity)
            }
        }
    }

    fun setPreviewView(view: PreviewView) {
        previewView = view
        cameraStreamProvider.previewView = view
    }

    fun start(lifecycleOwner: LifecycleOwner) {
        if (_state.value.isRunning) return
        activeLifecycleOwner = lifecycleOwner
        feedbackEngine.resetSession()
        _state.value = _state.value.copy(
            isRunning = true,
            errorMessage = null,
            debugText = "Starting camera..."
        )

        processingJob = viewModelScope.launch {
            try {
                cameraStreamProvider.start(lifecycleOwner, _state.value.isFrontCamera)
                _state.value = _state.value.copy(debugText = "Camera active, waiting for frames...")
                cameraStreamProvider.frames().collect { frame ->
                    try {
                        val startMs = SystemClock.elapsedRealtime()
                        val result = inferenceEngine.analyze(frame.image)
                        val inferenceMs = SystemClock.elapsedRealtime() - startMs
                        val fps = (1000 / (inferenceMs + 1)).toInt()

                        // Prepare landmarks for overlay
                        val facePoints = result.faceDetections.map { it.landmarks }
                        val handPoints = result.handDetections.map { it.landmarks }

                        // derive simple metrics
                        val faceCount = result.faceDetections.size
                        val handCount = result.handDetections.size
                        val handLabels = result.handDetections.map { it.gestureLabel }
                        val activeCues = result.handDetections.mapNotNull { det ->
                            if (det.confidence >= com.cuemate.core.model.PipelineConfig.HAND_CONFIDENCE_THRESHOLD) det.gestureLabel else null
                        } + result.faceDetections.mapNotNull { f ->
                            if (f.confidence >= com.cuemate.core.model.PipelineConfig.CONFIDENCE_THRESHOLD) {
                                when {
                                    f.smileScore >= 0.50f -> "smile"
                                    f.frownScore >= 0.40f -> "frown"
                                    f.surpriseScore >= 0.55f -> "surprise"
                                    else -> null
                                }
                            } else null
                        }

                        var lastDx: Float? = null
                        var lastDy: Float? = null
                        if (handPoints.firstOrNull()?.size ?: 0 >= 5) {
                            val firstHand = handPoints.first()
                            val thumbTip = firstHand.getOrNull(4)
                            val thumbMcp = firstHand.getOrNull(2)
                            if (thumbTip != null && thumbMcp != null) {
                                lastDx = thumbTip.x - thumbMcp.x
                                lastDy = thumbTip.y - thumbMcp.y
                            }
                        }

                        val lastSmile = result.faceDetections.firstOrNull()?.smileScore

                        _state.value = _state.value.copy(
                            debugText = summarizeInference(result),
                            fps = fps,
                            inferenceTimeMs = inferenceMs,
                            faceCount = faceCount,
                            handCount = handCount,
                            activeCues = activeCues,
                            handLabels = handLabels,
                            lastDx = lastDx,
                            lastDy = lastDy,
                            lastSmile = lastSmile,
                            faceLandmarks = facePoints,
                            handLandmarks = handPoints
                        )

                        cueFusionEngine.process(result)
                    } finally {
                        frame.close()
                    }
                }
            } catch (cancel: CancellationException) {
                Log.d("CueMateViewModel", "Camera pipeline cancelled")
                return@launch
            } catch (throwable: Throwable) {
                Log.e("CueMateViewModel", "Camera pipeline error", throwable)
                handleStartupFailure("Unable to start camera pipeline", throwable)
            }
        }

        feedbackJob = viewModelScope.launch {
            try {
                cueFusionEngine.cues().collect { cue ->
                    Log.d("CueMateViewModel", "Cue detected: ${cue.type} from ${cue.direction}")
                    _state.value = _state.value.copy(lastCue = cue)
                    feedbackEngine.provideFeedback(cue)
                }
            } catch (cancel: CancellationException) {
                Log.d("CueMateViewModel", "Feedback pipeline cancelled")
                return@launch
            } catch (throwable: Throwable) {
                Log.e("CueMateViewModel", "Feedback pipeline error", throwable)
                handleStartupFailure("Feedback pipeline stopped", throwable)
            }
        }

    }

    fun stop() {
        viewModelScope.launch {
            cameraStreamProvider.stop()
        }
        processingJob?.cancel()
        feedbackJob?.cancel()
        processingJob = null
        feedbackJob = null
        _state.value = _state.value.copy(isRunning = false)
    }

    private fun startCurrentSession() {
        val lifecycleOwner = activeLifecycleOwner ?: return
        if (_state.value.isRunning) return
        start(lifecycleOwner)
        viewModelScope.launch {
            _state.value = _state.value.copy(errorMessage = null)
        }
    }

    private fun handleStartupFailure(prefix: String, throwable: Throwable) {
        if (throwable is CancellationException) {
            return
        }
        _state.value = _state.value.copy(
            isRunning = false,
            errorMessage = "$prefix: ${throwable.message ?: throwable.javaClass.simpleName}"
        )
        stop()
    }

    private fun summarizeInference(result: com.cuemate.core.model.InferenceResult): String {
        val handSummary = result.handDetections.maxByOrNull { it.confidence }?.let { detection ->
            val label = detection.gestureLabel.lowercase()
            if (label == "none" || detection.confidence < com.cuemate.core.model.PipelineConfig.HAND_CONFIDENCE_THRESHOLD) {
                "No hand cue"
            } else {
                "Hand ${detection.gestureLabel} ${(detection.confidence * 100).toInt()}% @ ${String.format(java.util.Locale.US, "%.2f", detection.normalizedCenterX)}"
            }
        } ?: "No hand cue"
        val faceSummary = result.faceDetections.maxByOrNull { it.confidence }?.let { face ->
            val dominantFaceCue: String
            val displayScore: Float
            when {
                face.smileScore >= 0.50f -> {
                    dominantFaceCue = "happy"
                    displayScore = face.smileScore
                }
                face.frownScore >= 0.40f -> {
                    dominantFaceCue = "upset"
                    displayScore = face.frownScore
                }
                face.surpriseScore >= 0.55f -> {
                    dominantFaceCue = "surprise"
                    displayScore = face.surpriseScore
                }
                else -> {
                    dominantFaceCue = "neutral"
                    displayScore = face.confidence
                }
            }
            "Face $dominantFaceCue ${(displayScore * 100).toInt()}%"
        } ?: "No face cue"
        return "$handSummary | $faceSummary"
    }

    fun setSpeechEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                feedbackEngine.announceStatus(if (enabled) "Sound on" else "Sound off")
            } catch (e: Exception) {
                Log.w("CueMateViewModel", "announceStatus failed", e)
            }
            _state.value = _state.value.copy(speechEnabled = enabled)
            settingsRepository.setSpeechEnabled(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val msg = if (enabled) "Haptics on" else "Haptics off"
                feedbackEngine.announceStatus(msg)
            } catch (e: Exception) {
                Log.w("CueMateViewModel", "announceStatus failed", e)
            }
            _state.value = _state.value.copy(hapticsEnabled = enabled)
            settingsRepository.setHapticsEnabled(enabled)
        }
    }

    fun toggleCameraFacing() {
        viewModelScope.launch {
            val nextIsFrontCamera = !_state.value.isFrontCamera
            _state.value = _state.value.copy(isFrontCamera = nextIsFrontCamera)
            val lifecycleOwner = activeLifecycleOwner ?: return@launch
            if (_state.value.isRunning) {
                cameraStreamProvider.switchCamera(lifecycleOwner, nextIsFrontCamera)
            }
        }
    }

    override fun onCleared() {
        stop()
        cameraStreamProvider.close()
        feedbackEngine.shutdown()
        inferenceEngine.close()
        settingsJob?.cancel()
        super.onCleared()
    }
}

class CueMateViewModelFactory(
    private val cameraStreamProvider: CameraStreamProvider,
    private val cueFusionEngine: CueFusionEngine,
    private val feedbackEngine: AccessibilityFeedbackManager,
    private val inferenceEngine: MediaPipeInferenceEngine,
    private val settingsRepository: UserSettingsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CueMateViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CueMateViewModel(
                cameraStreamProvider,
                cueFusionEngine,
                feedbackEngine,
                inferenceEngine,
                settingsRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
