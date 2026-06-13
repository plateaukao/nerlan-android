package com.example.nerlan.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Stateless client for the OpenAI REST API: transcribe an episode's audio and
 * turn that transcript into a study handout. Mirrors the iOS `OpenAIService`.
 * Uses a long-timeout client because transcribing a ~30-min episode (and
 * generating a handout from a long transcript) can take minutes server-side.
 */
object OpenAIService {
  private const val BASE = "https://api.openai.com/v1"

  private val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(300, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .callTimeout(1800, TimeUnit.SECONDS)
    .build()

  private val json = Json { ignoreUnknownKeys = true }

  class OpenAIException(message: String) : Exception(message)

  // MARK: Transcription

  /**
   * Transcribe an audio file via POST /audio/transcriptions (multipart).
   *
   * [prompt] biases Whisper's output script/vocabulary. These are bilingual
   * teaching programs (Mandarin host + foreign examples); without a prompt
   * Whisper locks onto the dominant language (Chinese) and collapses the foreign
   * speech into Chinese characters. Priming it with Traditional Chinese plus a
   * native-script sample of the target language keeps both intact — build one
   * with [transcriptionPrompt].
   */
  suspend fun transcribe(file: File, model: String, apiKey: String, prompt: String? = null): String =
    withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("model", model)
      .addFormDataPart("response_format", "text")
      .apply { if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt) }
      .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
      .build()
    val request = Request.Builder()
      .url("$BASE/audio/transcriptions")
      .header("Authorization", "Bearer $apiKey")
      .post(body)
      .build()
    client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw OpenAIException(errorMessage(text) ?: "OpenAI 請求失敗（HTTP ${response.code}）")
      text.trim()
    }
  }

  /**
   * A Whisper `prompt` for a program's target [language] (the Chinese name from
   * `EpisodeRecord.language`, e.g. 日語/英語/韓語). Whisper treats the prompt as
   * preceding context, not an instruction, so we prime it with actual mixed text:
   * a Traditional-Chinese teaching sentence plus a short native-script sample of
   * the language, which nudges the decoder to keep 正體中文 for the host and the
   * original script for the foreign words.
   */
  fun transcriptionPrompt(language: String): String {
    val base = "這是一段以臺灣繁體中文（正體字）講解的語言教學廣播節目，主持人會穿插示範外語。"
    val sample = when {
      language.contains("日") -> "日語例句：おはようございます。ありがとうございます。よろしくお願いします。"
      language.contains("英") -> "English examples: Good morning. How are you today? Thank you very much."
      language.contains("韓") -> "韓語例句：안녕하세요. 감사합니다. 맛있어요."
      language.contains("法") -> "Exemples en français : Bonjour. Comment allez-vous ? Merci beaucoup."
      language.contains("德") -> "Beispiele auf Deutsch: Guten Morgen. Wie geht es Ihnen? Danke schön."
      language.contains("西") -> "Ejemplos en español: Buenos días. ¿Cómo está usted? Muchas gracias."
      language.contains("越") -> "Ví dụ tiếng Việt: Xin chào. Bạn có khỏe không? Cảm ơn rất nhiều."
      language.contains("印尼") -> "Contoh bahasa Indonesia: Selamat pagi. Apa kabar? Terima kasih banyak."
      language.contains("泰") -> "ตัวอย่างภาษาไทย: สวัสดีครับ สบายดีไหม ขอบคุณมากครับ"
      else -> return base + "節目中會穿插「$language」教學，請保留該語言文字的原始樣貌。"
    }
    return base + sample
  }

  // MARK: Sentence segmentation

  /**
   * Re-segment a raw ASR transcript into one sentence per line using the chat
   * model, without altering the wording — speech recognition (especially CJK)
   * often returns text with little punctuation. Long transcripts are chunked so
   * no single response is truncated.
   */
  suspend fun segmentTranscript(raw: String, model: String, apiKey: String): String {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val system = buildString {
      append("你是一個只負責加上句尾標點與斷句的文字編輯器。你會收到一段語音辨識（ASR）產生的逐字稿，通常缺少標點。")
      append("規則：")
      append("1. 只能加入句尾的句號（中文用「。」，外語用「.」）並在每句後換行，每句一行。")
      append("2. 絕對不可更動任何原始內容：不可翻譯、改寫、增刪、調整字詞或更改任何字元；簡繁字體與外語（日文、英文、韓文等）原文都必須原封不動保留。")
      append("3. 只輸出處理後的逐字稿，每句一行，不要加編號，也不要任何其他說明文字。")
    }
    val out = StringBuilder()
    for (piece in chunk(raw, 4000)) {
      if (out.isNotEmpty()) out.append('\n')
      out.append(chat(system, piece, model, apiKey).trim())
    }
    return out.toString()
  }

  // MARK: Handout (chat completion)

  /** Produce an HTML study-handout *fragment* from a transcript. */
  suspend fun generateHandout(transcript: String, record: EpisodeRecord, model: String, apiKey: String): String {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val system = buildString {
      append("你是一位專業的語言老師，正在為「${record.language}」語言學習教材製作複習講義。")
      append("你會收到一集廣播節目的逐字稿，請根據內容整理出一份適合學生複習的講義。")
      append("說明文字一律使用「台灣繁體中文（正體字）」，絕對不要使用簡體字；例句與單字中的外語請保留原貌（不要翻譯或改成中文字）。")
      append("並使用 HTML 格式輸出，分成三個區塊：")
      append("<h2>文法重點</h2>（列出本集出現的文法句型，附簡短解說）、")
      append("<h2>例句</h2>（從內容中挑選實用例句，逐句附上中文翻譯）、")
      append("<h2>單字</h2>（重要單字表，含發音或拼音與中文意思，建議用表格呈現）。")
      append("只輸出 HTML 內容片段（可使用 h2、h3、p、ul、ol、li、table、tr、th、td、strong、em、ruby 等標籤），")
      append("不要輸出 <html>、<head>、<body> 標籤，也不要使用 Markdown 或程式碼圍欄。")
    }
    val user = "節目：${record.programName}\n單集：${record.title}\n\n逐字稿：\n$transcript"
    return stripCodeFence(chat(system, user, model, apiKey))
  }

  // MARK: Helpers

  /** One round-trip to POST /chat/completions, returning the message content. */
  private suspend fun chat(system: String, user: String, model: String, apiKey: String): String =
    withContext(Dispatchers.IO) {
      val payload = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
          addJsonObject { put("role", "system"); put("content", system) }
          addJsonObject { put("role", "user"); put("content", user) }
        }
      }
      val request = Request.Builder()
        .url("$BASE/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
      client.newCall(request).execute().use { response ->
        val bodyStr = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw OpenAIException(errorMessage(bodyStr) ?: "OpenAI 請求失敗（HTTP ${response.code}）")
        runCatching {
          json.parseToJsonElement(bodyStr).jsonObject["choices"]!!.jsonArray
            .first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        }.getOrElse { throw OpenAIException("無法解析 OpenAI 回應") }
      }
    }

  private fun errorMessage(bodyStr: String): String? = runCatching {
    json.parseToJsonElement(bodyStr).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
  }.getOrNull()

  /** Models sometimes wrap HTML in ```html fences despite instructions. */
  private fun stripCodeFence(s: String): String {
    var text = s.trim()
    if (text.startsWith("```")) {
      val firstNewline = text.indexOf('\n')
      if (firstNewline >= 0) text = text.substring(firstNewline + 1)
      if (text.endsWith("```")) text = text.dropLast(3)
    }
    return text.trim()
  }

  /** Split text into <= maxChars pieces, backing up to a nearby sentence break. */
  private fun chunk(text: String, maxChars: Int): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val breaks = charArrayOf('。', '！', '？', '.', '!', '?', '\n', '、', '，', ',', ' ')
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
      var end = minOf(start + maxChars, text.length)
      if (end < text.length) {
        var b = end
        val floor = start + maxChars / 2
        while (b > floor && text[b - 1] !in breaks) b--
        if (b > floor) end = b
      }
      chunks.add(text.substring(start, end))
      start = end
    }
    return chunks
  }
}
