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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PipelineState(
    val isRunning: Boolean = false,
    val lastCue: SocialCue? = null,
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
        _state.value = _state.value.copy(isRunning = true, errorMessage = null)

        processingJob = viewModelScope.launch {
            try {
                cameraStreamProvider.start(lifecycleOwner)
                cameraStreamProvider.frames().collect { frame ->
                    try {
                        val result = inferenceEngine.analyze(frame.image)
                        cueFusionEngine.process(result)
                    } finally {
                        frame.close()
                    }
                }
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
            } catch (throwable: Throwable) {
                Log.e("CueMateViewModel", "Feedback pipeline error", throwable)
                handleStartupFailure("Feedback pipeline stopped", throwable)
            }
        }

        runCatching {
            feedbackEngine.startVoiceCommands { command ->
                when (command.lowercase()) {
                    "start" -> startCurrentSession()
                    "stop" -> stop()
                    "mute" -> setSpeechEnabled(false)
                    "faster speech" -> viewModelScope.launch { settingsRepository.setSpeechRate(1.2f) }
                    "slower speech" -> viewModelScope.launch { settingsRepository.setSpeechRate(0.8f) }
                }
            }
        }.onFailure { throwable ->
            Log.w("CueMateViewModel", "Voice commands unavailable", throwable)
            _state.value = _state.value.copy(
                errorMessage = "Voice commands unavailable: ${throwable.message ?: throwable.javaClass.simpleName}"
            )
        }
    }

    fun stop() {
        viewModelScope.launch {
            cameraStreamProvider.stop()
        }
        feedbackEngine.stopVoiceCommands()
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
        _state.value = _state.value.copy(
            isRunning = false,
            errorMessage = "$prefix: ${throwable.message ?: throwable.javaClass.simpleName}"
        )
        stop()
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
