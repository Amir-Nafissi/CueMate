package com.cuemate.app

import android.content.Context
import com.cuemate.camera.CameraStreamProvider
import com.cuemate.feedback.AccessibilityFeedbackManager
import com.cuemate.inference.MediaPipeInferenceEngine
import com.cuemate.logic.CueFusionEngine
import com.cuemate.settings.UserSettingsRepository
import com.cuemate.ui.CueMateViewModelFactory

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val cameraStreamProvider = CameraStreamProvider(appContext)
    val inferenceEngine = MediaPipeInferenceEngine(appContext)
    val feedbackManager = AccessibilityFeedbackManager(appContext)
    val fusionEngine = CueFusionEngine()
    val settingsRepository = UserSettingsRepository(appContext)

    val viewModelFactory = CueMateViewModelFactory(
        cameraStreamProvider = cameraStreamProvider,
        cueFusionEngine = fusionEngine,
        feedbackEngine = feedbackManager,
        inferenceEngine = inferenceEngine,
        settingsRepository = settingsRepository
    )
}
