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

  /** Which server the AI features talk to: OpenAI 官方 or a self-hosted
   *  OpenAI-compatible endpoint. Raw values match the iOS APIProvider enum. */
  private val _apiProvider = MutableStateFlow(prefs.getString(KEY_PROVIDER, PROVIDER_OPENAI)!!)
  val apiProvider: StateFlow<String> = _apiProvider

  private val _customTranscriptionUrl =
    MutableStateFlow(prefs.getString(KEY_CUSTOM_TRANSCRIPTION_URL, DEFAULT_CUSTOM_SERVER_URL)!!)
  val customTranscriptionUrl: StateFlow<String> = _customTranscriptionUrl

  private val _customTranscriptionModel =
    MutableStateFlow(prefs.getString(KEY_CUSTOM_TRANSCRIPTION_MODEL, DEFAULT_TRANSCRIPTION_MODEL)!!)
  val customTranscriptionModel: StateFlow<String> = _customTranscriptionModel

  private val _customTranscriptionKey =
    MutableStateFlow(prefs.getString(KEY_CUSTOM_TRANSCRIPTION_KEY, "").orEmpty())
  val customTranscriptionKey: StateFlow<String> = _customTranscriptionKey

  private val _customChatUrl =
    MutableStateFlow(prefs.getString(KEY_CUSTOM_CHAT_URL, DEFAULT_CUSTOM_SERVER_URL)!!)
  val customChatUrl: StateFlow<String> = _customChatUrl

  private val _customChatModel =
    MutableStateFlow(prefs.getString(KEY_CUSTOM_CHAT_MODEL, DEFAULT_CHAT_MODEL)!!)
  val customChatModel: StateFlow<String> = _customChatModel

  private val _customChatKey = MutableStateFlow(prefs.getString(KEY_CUSTOM_CHAT_KEY, "").orEmpty())
  val customChatKey: StateFlow<String> = _customChatKey

  /** When on, chat requests to the custom server send reasoning_effort=none so
   *  local thinking models (qwen3, deepseek-r1, …) skip their reasoning dump. */
  private val _customChatNoThink = MutableStateFlow(prefs.getBoolean(KEY_CUSTOM_CHAT_NO_THINK, false))
  val customChatNoThink: StateFlow<Boolean> = _customChatNoThink

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

  /** Remembered transcript view mode: 0 = original, 1 = original + translation,
   *  2 = translation only. Applied on open only when a matching translation is
   *  already cached (opening never triggers a paid translation). */
  private val _transcriptTranslateMode = MutableStateFlow(prefs.getInt(KEY_TRANSLATE_MODE, 0))
  val transcriptTranslateMode: StateFlow<Int> = _transcriptTranslateMode

  /** Last chosen language filter on the program list ("" = 全部), restored
   *  across launches. */
  private val _programLanguageFilter =
    MutableStateFlow(prefs.getString(KEY_PROGRAM_LANG, "").orEmpty())
  val programLanguageFilter: StateFlow<String> = _programLanguageFilter

  /** Whether the AI actions can run at all — the provider-aware replacement for
   *  "is the API key set". Official needs a key; custom just needs a server URL
   *  (local servers are typically keyless). Mirrors the iOS hasAPIKey. */
  private val _aiConfigured = MutableStateFlow(computeAiConfigured())
  val aiConfigured: StateFlow<Boolean> = _aiConfigured

  private fun computeAiConfigured(): Boolean = when (_apiProvider.value) {
    PROVIDER_CUSTOM -> _customTranscriptionUrl.value.isNotBlank()
    else -> _apiKey.value.isNotBlank()
  }

  fun setApiKey(value: String) {
    _apiKey.value = value
    prefs.edit().putString(KEY_API, value).apply()
    _aiConfigured.value = computeAiConfigured()
  }

  fun setApiProvider(value: String) {
    _apiProvider.value = value
    prefs.edit().putString(KEY_PROVIDER, value).apply()
    _aiConfigured.value = computeAiConfigured()
  }

  fun setCustomTranscriptionUrl(value: String) {
    _customTranscriptionUrl.value = value
    prefs.edit().putString(KEY_CUSTOM_TRANSCRIPTION_URL, value).apply()
    _aiConfigured.value = computeAiConfigured()
  }

  fun setCustomTranscriptionModel(value: String) {
    _customTranscriptionModel.value = value
    prefs.edit().putString(KEY_CUSTOM_TRANSCRIPTION_MODEL, value).apply()
  }

  fun setCustomTranscriptionKey(value: String) {
    _customTranscriptionKey.value = value
    prefs.edit().putString(KEY_CUSTOM_TRANSCRIPTION_KEY, value).apply()
  }

  fun setCustomChatUrl(value: String) {
    _customChatUrl.value = value
    prefs.edit().putString(KEY_CUSTOM_CHAT_URL, value).apply()
  }

  fun setCustomChatModel(value: String) {
    _customChatModel.value = value
    prefs.edit().putString(KEY_CUSTOM_CHAT_MODEL, value).apply()
  }

  fun setCustomChatKey(value: String) {
    _customChatKey.value = value
    prefs.edit().putString(KEY_CUSTOM_CHAT_KEY, value).apply()
  }

  fun setCustomChatNoThink(value: Boolean) {
    _customChatNoThink.value = value
    prefs.edit().putBoolean(KEY_CUSTOM_CHAT_NO_THINK, value).apply()
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

  fun setTranscriptTranslateMode(value: Int) {
    _transcriptTranslateMode.value = value
    prefs.edit().putInt(KEY_TRANSLATE_MODE, value).apply()
  }

  fun setProgramLanguageFilter(value: String) {
    _programLanguageFilter.value = value
    prefs.edit().putString(KEY_PROGRAM_LANG, value).apply()
  }

  /** Model names coerced away from blank for actual API calls. */
  fun chatModelOrDefault() = _chatModel.value.ifBlank { DEFAULT_CHAT_MODEL }
  fun transcriptionModelOrDefault() = _transcriptionModel.value.ifBlank { DEFAULT_TRANSCRIPTION_MODEL }

  /** Endpoint + credentials + model for /audio/transcriptions, resolved from the
   *  active provider. Mirrors the iOS transcriptionConfig. */
  fun transcriptionConfig(): OpenAIService.Config = when (_apiProvider.value) {
    PROVIDER_CUSTOM -> OpenAIService.Config(
      baseUrl = customUrl(_customTranscriptionUrl.value),
      apiKey = customKey(_customTranscriptionKey.value, _customTranscriptionUrl.value),
      model = _customTranscriptionModel.value.ifBlank { DEFAULT_TRANSCRIPTION_MODEL },
      requiresKey = false,
    )
    else -> OpenAIService.Config(
      baseUrl = OpenAIService.OFFICIAL_BASE,
      apiKey = _apiKey.value,
      model = transcriptionModelOrDefault(),
    )
  }

  /** Endpoint + credentials + model for /chat/completions (handout, translation,
   *  sentence segmentation), resolved from the active provider. Mirrors the iOS
   *  chatConfig. */
  fun chatConfig(): OpenAIService.Config = when (_apiProvider.value) {
    PROVIDER_CUSTOM -> OpenAIService.Config(
      baseUrl = customUrl(_customChatUrl.value),
      apiKey = customKey(_customChatKey.value, _customChatUrl.value),
      model = _customChatModel.value.ifBlank { DEFAULT_CHAT_MODEL },
      requiresKey = false,
      disableThinking = _customChatNoThink.value,
    )
    else -> OpenAIService.Config(
      baseUrl = OpenAIService.OFFICIAL_BASE,
      apiKey = _apiKey.value,
      model = chatModelOrDefault(),
    )
  }

  /** A custom base URL normalized for requests; blank falls back to the official
   *  base so a misconfiguration fails with a clear server error, not a crash. */
  private fun customUrl(url: String): String =
    url.trim().trimEnd('/').ifBlank { DEFAULT_CUSTOM_SERVER_URL }

  /** Custom-mode key fallback: an explicit key wins; a blank key reuses the
   *  OpenAI-mode key while the URL still points at the official server, and sends
   *  no key at all for anything else (keyless local servers). */
  private fun customKey(key: String, url: String): String {
    val trimmed = key.trim()
    if (trimmed.isNotEmpty()) return trimmed
    return if (customUrlIsOfficial(url)) _apiKey.value else ""
  }

  companion object {
    const val DEFAULT_CHAT_MODEL = "gpt-4o"
    const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
    const val DEFAULT_TRANSLATION_LANGUAGE = "繁體中文"
    const val DEFAULT_CUSTOM_SERVER_URL = OpenAIService.OFFICIAL_BASE

    /** apiProvider values; raw strings match the iOS APIProvider enum. */
    const val PROVIDER_OPENAI = "openAIOfficial"
    const val PROVIDER_CUSTOM = "custom"

    /** Whether a custom server URL (still) points at the official OpenAI API. */
    fun customUrlIsOfficial(url: String): Boolean =
      url.trim().trimEnd('/').let { it.isEmpty() || it == OpenAIService.OFFICIAL_BASE }

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
    private const val KEY_PROVIDER = "api_provider"
    private const val KEY_CUSTOM_TRANSCRIPTION_URL = "custom_transcription_url"
    private const val KEY_CUSTOM_TRANSCRIPTION_MODEL = "custom_transcription_model"
    private const val KEY_CUSTOM_TRANSCRIPTION_KEY = "custom_transcription_key"
    private const val KEY_CUSTOM_CHAT_URL = "custom_chat_url"
    private const val KEY_CUSTOM_CHAT_MODEL = "custom_chat_model"
    private const val KEY_CUSTOM_CHAT_KEY = "custom_chat_key"
    private const val KEY_CUSTOM_CHAT_NO_THINK = "custom_chat_no_think"
    private const val KEY_CACHE_STREAM = "cache_streamed_audio"
    private const val KEY_SYNC_DRIVE = "sync_to_drive"
    private const val KEY_FONT_SCALE = "transcript_font_scale"
    private const val KEY_TRANSLATION_LANG = "translation_language"
    private const val KEY_SCROLL_ANIM = "transcript_scroll_animated"
    private const val KEY_SHADOW_COUNT = "shadow_loop_count"
    private const val KEY_TRANSLATE_MODE = "transcript_translate_mode"
    private const val KEY_PROGRAM_LANG = "program_language_filter"
  }
}
