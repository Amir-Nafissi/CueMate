package com.cuemate.feedback

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.cuemate.core.model.CueType
import com.cuemate.core.model.FeedbackEngine
import com.cuemate.core.model.PipelineConfig
import com.cuemate.core.model.SocialCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AccessibilityFeedbackManager(
    private val context: Context,
) : FeedbackEngine, TextToSpeech.OnInitListener {

    private val speechTimestampMs = AtomicLong(0L)
    private val vibratorManager = context.getSystemService(VibratorManager::class.java)
    private var textToSpeech: TextToSpeech? = TextToSpeech(context, this)
    private var speechRecognizer: SpeechRecognizer? = null
    private val speechEnabled = AtomicBoolean(true)
    private val hapticsEnabled = AtomicBoolean(true)
    @Volatile
    private var lastSpokenCue: SocialCue? = null
    @Volatile
    private var ttsReady = false

    override suspend fun provideFeedback(cue: SocialCue) = withContext(Dispatchers.Main) {
        if (cue == lastSpokenCue) {
            return@withContext
        }
        val now = System.currentTimeMillis()
        val last = speechTimestampMs.get()
        if (now - last < PipelineConfig.MIN_SPEECH_INTERVAL_MS) {
            return@withContext
        }
        speechTimestampMs.set(now)
        lastSpokenCue = cue
        if (hapticsEnabled.get()) {
            vibrateForCue(cue)
        }
        if (speechEnabled.get()) {
            speakForCue(cue)
        }
    }

    fun setSpeechEnabled(enabled: Boolean) {
        speechEnabled.set(enabled)
        if (!enabled) {
            textToSpeech?.stop()
        }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        hapticsEnabled.set(enabled)
    }

    fun setSpeechRate(rate: Float) {
        textToSpeech?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setHapticIntensity(intensity: Float) {
        hapticIntensity = intensity.coerceIn(0.0f, 1.0f)
    }

    fun startVoiceCommands(onCommand: (String) -> Unit) {
        val microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!microphoneGranted) {
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = Unit
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    matches.firstOrNull()?.let(onCommand)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            startListening(intent)
        }
    }

    fun stopVoiceCommands() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun speakForCue(cue: SocialCue) {
        val message = when (cue.type) {
            CueType.SMILE -> "Person ${directionText(cue)} is smiling"
            CueType.FROWN -> "Person ${directionText(cue)} looks upset"
            CueType.SURPRISE -> "Person ${directionText(cue)} looks surprised"
            CueType.NEUTRAL -> "Person ${directionText(cue)} is neutral"
            CueType.WAVE -> "Someone waving ahead"
            CueType.POINT -> "Someone pointing ahead"
            CueType.THUMBS_UP -> "Someone gave a thumbs up"
            CueType.HANDSHAKE_REACH -> "Someone reaching for a handshake"
        }
        val tts = textToSpeech ?: return
        if (!ttsReady) return
        tts.stop()
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "cue_${cue.timestamp}")
    }

    private fun directionText(cue: SocialCue): String = when (cue.direction) {
        com.cuemate.core.model.Direction.LEFT -> "left"
        com.cuemate.core.model.Direction.CENTER -> "ahead"
        com.cuemate.core.model.Direction.RIGHT -> "right"
    }

    private fun vibrateForCue(cue: SocialCue) {
        val vibrator = vibratorManager?.defaultVibrator ?: return
        val amplitude = when (hapticIntensity) {
            in 0.0f..0.25f -> 64
            in 0.25f..0.5f -> 128
            in 0.5f..0.75f -> 192
            else -> 255
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (cue.type) {
                CueType.WAVE, CueType.POINT, CueType.THUMBS_UP, CueType.HANDSHAKE_REACH ->
                    VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 40), intArrayOf(0, amplitude, 0, amplitude), -1)
                else -> VibrationEffect.createOneShot(90, amplitude)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(90)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            ttsReady = true
        }
    }

    fun shutdown() {
        stopVoiceCommands()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
