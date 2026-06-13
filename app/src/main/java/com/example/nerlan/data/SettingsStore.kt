package com.example.nerlan.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * OpenAI credentials and model choices, persisted in app-private SharedPreferences
 * and exposed as StateFlows so the AI action icons react to the key being set.
 */
class SettingsStore(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  private val _apiKey = MutableStateFlow(prefs.getString(KEY_API, "").orEmpty())
  val apiKey: StateFlow<String> = _apiKey

  private val _chatModel = MutableStateFlow(prefs.getString(KEY_CHAT, DEFAULT_CHAT_MODEL)!!)
  val chatModel: StateFlow<String> = _chatModel

  private val _transcriptionModel =
    MutableStateFlow(prefs.getString(KEY_TRANSCRIPTION, DEFAULT_TRANSCRIPTION_MODEL)!!)
  val transcriptionModel: StateFlow<String> = _transcriptionModel

  /** When on, an episode streamed to completion is cached for offline replay.
   *  Off by default so it never silently uses data or storage unasked. */
  private val _cacheStreamedAudio = MutableStateFlow(prefs.getBoolean(KEY_CACHE_STREAM, false))
  val cacheStreamedAudio: StateFlow<Boolean> = _cacheStreamedAudio

  fun setApiKey(value: String) {
    _apiKey.value = value
    prefs.edit().putString(KEY_API, value).apply()
  }

  fun setChatModel(value: String) {
    _chatModel.value = value
    prefs.edit().putString(KEY_CHAT, value).apply()
  }

  fun setTranscriptionModel(value: String) {
    _transcriptionModel.value = value
    prefs.edit().putString(KEY_TRANSCRIPTION, value).apply()
  }

  fun setCacheStreamedAudio(value: Boolean) {
    _cacheStreamedAudio.value = value
    prefs.edit().putBoolean(KEY_CACHE_STREAM, value).apply()
  }

  /** Model names coerced away from blank for actual API calls. */
  fun chatModelOrDefault() = _chatModel.value.ifBlank { DEFAULT_CHAT_MODEL }
  fun transcriptionModelOrDefault() = _transcriptionModel.value.ifBlank { DEFAULT_TRANSCRIPTION_MODEL }

  companion object {
    const val DEFAULT_CHAT_MODEL = "gpt-4o"
    const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
    private const val KEY_API = "openai_api_key"
    private const val KEY_CHAT = "openai_chat_model"
    private const val KEY_TRANSCRIPTION = "openai_transcription_model"
    private const val KEY_CACHE_STREAM = "cache_streamed_audio"
  }
}
