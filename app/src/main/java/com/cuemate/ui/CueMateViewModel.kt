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
                cameraStreamProvider.start(lifecycleOwner)
                _state.value = _state.value.copy(debugText = "Camera active, waiting for frames...")
                cameraStreamProvider.frames().collect { frame ->
                    try {
                        val result = inferenceEngine.analyze(frame.image)
                        _state.value = _state.value.copy(debugText = summarizeInference(result))
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
            val dominantFaceCue = when {
                face.smileScore >= 0.40f -> "smile"
                face.surpriseScore >= 0.55f -> "surprise"
                face.frownScore >= 0.97f -> "frown"
                else -> "neutral"
            }
            "Face $dominantFaceCue ${(face.confidence * 100).toInt()}%"
        } ?: "No face cue"
        return "$handSummary | $faceSummary"
    }

    fun setSpeechEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSpeechEnabled(enabled)
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticsEnabled(enabled)
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
