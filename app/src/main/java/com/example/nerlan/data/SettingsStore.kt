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

  /** When on (and signed in), favorites and AI content sync to the user's Google
   *  Drive appDataFolder. Off by default. */
  private val _syncToDrive = MutableStateFlow(prefs.getBoolean(KEY_SYNC_DRIVE, false))
  val syncToDrive: StateFlow<Boolean> = _syncToDrive

  /** Transcript reading font size, remembered across transcript screens:
   *  0 = default, 1 = larger, 2 = largest (see TRANSCRIPT_FONT_SIZES). */
  private val _transcriptFontScale = MutableStateFlow(prefs.getInt(KEY_FONT_SCALE, 0))
  val transcriptFontScale: StateFlow<Int> = _transcriptFontScale

  /** Language the transcript "translate" button renders into. */
  private val _translationLanguage =
    MutableStateFlow(prefs.getString(KEY_TRANSLATION_LANG, DEFAULT_TRANSLATION_LANGUAGE)!!)
  val translationLanguage: StateFlow<String> = _translationLanguage

  /** When on, the transcript smoothly animates the spoken sentence to the center
   *  of the screen; off jumps to it instantly. On by default — turn off on e-ink
   *  devices, where the scroll animation smears/ghosts. */
  private val _transcriptScrollAnimated = MutableStateFlow(prefs.getBoolean(KEY_SCROLL_ANIM, true))
  val transcriptScrollAnimated: StateFlow<Boolean> = _transcriptScrollAnimated

  /** Shadowing repeat count per sentence: 0 = loop forever, else play N times then
   *  stop (and auto-record). Remembered across transcript screens. */
  private val _shadowLoopCount = MutableStateFlow(prefs.getInt(KEY_SHADOW_COUNT, 0))
  val shadowLoopCount: StateFlow<Int> = _shadowLoopCount

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

  fun setSyncToDrive(value: Boolean) {
    _syncToDrive.value = value
    prefs.edit().putBoolean(KEY_SYNC_DRIVE, value).apply()
  }

  fun setTranscriptFontScale(value: Int) {
    val v = value.coerceIn(0, TRANSCRIPT_FONT_SIZES.size - 1)
    _transcriptFontScale.value = v
    prefs.edit().putInt(KEY_FONT_SCALE, v).apply()
  }

  fun setTranslationLanguage(value: String) {
    _translationLanguage.value = value
    prefs.edit().putString(KEY_TRANSLATION_LANG, value).apply()
  }

  fun setTranscriptScrollAnimated(value: Boolean) {
    _transcriptScrollAnimated.value = value
    prefs.edit().putBoolean(KEY_SCROLL_ANIM, value).apply()
  }

  fun setShadowLoopCount(value: Int) {
    _shadowLoopCount.value = value
    prefs.edit().putInt(KEY_SHADOW_COUNT, value).apply()
  }

  /** Model names coerced away from blank for actual API calls. */
  fun chatModelOrDefault() = _chatModel.value.ifBlank { DEFAULT_CHAT_MODEL }
  fun transcriptionModelOrDefault() = _transcriptionModel.value.ifBlank { DEFAULT_TRANSCRIPTION_MODEL }

  companion object {
    const val DEFAULT_CHAT_MODEL = "gpt-4o"
    const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
    const val DEFAULT_TRANSLATION_LANGUAGE = "繁體中文"

    /** Selectable transcription models. whisper-1 collapses bilingual audio into
     *  the dominant language; the gpt-4o-transcribe models handle code-switching
     *  far better (see OpenAIService.transcribe). */
    val TRANSCRIPTION_MODELS = listOf("whisper-1", "gpt-4o-mini-transcribe", "gpt-4o-transcribe")

    /** Languages the transcript "translate" button can render into. Display names
     *  go straight into the translation prompt, written as a native reader expects. */
    val TRANSLATION_LANGUAGES = listOf(
      "繁體中文", "English", "日本語", "한국어", "Español",
      "Français", "Deutsch", "Tiếng Việt", "Bahasa Indonesia", "ภาษาไทย",
    )

    /** Transcript reading font sizes (sp) for the three font-scale steps. */
    val TRANSCRIPT_FONT_SIZES = listOf(17, 21, 26)

    private const val KEY_API = "openai_api_key"
    private const val KEY_CHAT = "openai_chat_model"
    private const val KEY_TRANSCRIPTION = "openai_transcription_model"
    private const val KEY_CACHE_STREAM = "cache_streamed_audio"
    private const val KEY_SYNC_DRIVE = "sync_to_drive"
    private const val KEY_FONT_SCALE = "transcript_font_scale"
    private const val KEY_TRANSLATION_LANG = "translation_language"
    private const val KEY_SCROLL_ANIM = "transcript_scroll_animated"
    private const val KEY_SHADOW_COUNT = "shadow_loop_count"
  }
}
