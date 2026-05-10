package com.cuemate.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "cue_settings")

data class UserSettings(
    val speechRate: Float = 1.0f,
    val hapticIntensity: Float = 1.0f,
    val speechEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
)

class UserSettingsRepository(private val context: Context) {
    private object Keys {
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val HAPTIC_INTENSITY = floatPreferencesKey("haptic_intensity")
        val SPEECH_ENABLED = booleanPreferencesKey("speech_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    }

    val settings: Flow<UserSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences.toUserSettings()
        }
        .distinctUntilChanged()

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun setHapticIntensity(intensity: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.HAPTIC_INTENSITY] = intensity.coerceIn(0.0f, 1.0f)
        }
    }

    suspend fun setSpeechEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SPEECH_ENABLED] = enabled
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.HAPTICS_ENABLED] = enabled
        }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        return UserSettings(
            speechRate = this[Keys.SPEECH_RATE] ?: 1.0f,
            hapticIntensity = this[Keys.HAPTIC_INTENSITY] ?: 1.0f,
            speechEnabled = this[Keys.SPEECH_ENABLED] ?: true,
            hapticsEnabled = this[Keys.HAPTICS_ENABLED] ?: true,
        )
    }
}
