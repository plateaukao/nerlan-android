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

  /** Transcribe an audio file via POST /audio/transcriptions (multipart). */
  suspend fun transcribe(file: File, model: String, apiKey: String): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("model", model)
      .addFormDataPart("response_format", "text")
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
      append("你是一個文字編輯器。你會收到一段語音辨識（ASR）產生的逐字稿，可能缺少標點或斷句。")
      append("請將它重新斷句，每一句一行。規則：")
      append("1. 不可翻譯、改寫、摘要、增刪或更動內容，只能加入適當的標點符號並斷行。")
      append("2. 中文一律使用「台灣繁體中文（正體字）」，絕對不要使用簡體字；若輸入含簡體字，請轉換成繁體字（這是字體轉換，不是翻譯）。")
      append("3. 保留原文中的外語原貌（例如日文、英文、韓文等）：不要翻譯、不要轉寫成拼音或羅馬字，也不要把日文漢字改成中文字。")
      append("4. 只輸出斷句後的逐字稿，每句一行，不要加編號，也不要任何其他說明文字。")
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
