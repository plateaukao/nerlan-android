package com.example.nerlan.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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

  /** One timed unit of ASR output: the segment's text and the audio time (seconds,
   *  relative to the file transcribed) at which it begins. */
  data class Segment(val start: Double, val text: String)

  /** Result of a transcription: the full text plus, when the model supports it, the
   *  per-segment timestamps. [segments] is empty for models that return no timing. */
  data class TranscriptionResult(val text: String, val segments: List<Segment>)

  /** Whether a transcription model returns segment timestamps. Only whisper-1
   *  supports response_format=verbose_json; the gpt-4o-transcribe models accept
   *  json/text only and would reject verbose_json. */
  fun supportsSegments(model: String): Boolean = model.lowercase().contains("whisper")

  /**
   * Transcribe an audio file via POST /audio/transcriptions (multipart). Whisper
   * models are asked for verbose_json so per-segment timestamps come back (used to
   * highlight the playing sentence); other models get response_format=text.
   *
   * [prompt] biases Whisper's output script/vocabulary. These are bilingual
   * teaching programs (Mandarin host + foreign examples); without a prompt Whisper
   * locks onto the dominant language (Chinese) and collapses the foreign speech
   * into Chinese characters. [language] (ISO-639-1, e.g. "ko") forces the spoken
   * language for monolingual sources (podcasts); leave null for bilingual NER
   * content, which relies on [prompt] and per-passage detection instead.
   */
  suspend fun transcribe(
    file: File,
    model: String,
    apiKey: String,
    prompt: String? = null,
    language: String? = null,
  ): TranscriptionResult = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val wantSegments = supportsSegments(model)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
      .addFormDataPart("model", model)
      .addFormDataPart("response_format", if (wantSegments) "verbose_json" else "text")
      .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
      .apply { if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt) }
      .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
      .build()
    val request = Request.Builder()
      .url("$BASE/audio/transcriptions")
      .header("Authorization", "Bearer $apiKey")
      .post(body)
      .build()
    client.newCall(request).execute().use { response ->
      val bodyStr = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw OpenAIException(errorMessage(bodyStr) ?: "OpenAI 請求失敗（HTTP ${response.code}）")
      if (!wantSegments) return@use TranscriptionResult(bodyStr.trim(), emptyList())
      // verbose_json: { "text": "...", "segments": [ { "start": 0.0, "text": "..." }, ... ] }
      val root = runCatching { json.parseToJsonElement(bodyStr).jsonObject }
        .getOrElse { throw OpenAIException("無法解析 OpenAI 回應") }
      val segments = root["segments"]?.jsonArray?.mapNotNull { el ->
        val obj = el.jsonObject
        val start = obj["start"]?.jsonPrimitive?.doubleOrNull
        val segText = obj["text"]?.jsonPrimitive?.contentOrNull
        if (start != null && segText != null) Segment(start, segText) else null
      }.orEmpty()
      val full = root["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
      val text = full.ifEmpty { segments.joinToString("") { it.text }.trim() }
      if (text.isEmpty()) throw OpenAIException("無法解析 OpenAI 回應")
      TranscriptionResult(text, segments)
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
      append("你是一個只負責加上標點與斷句的文字編輯器。你會收到一段語音辨識（ASR）產生的逐字稿，通常缺少標點。")
      append("規則：")
      append("1. 加入適當且必要的標點符號（句號、問號、驚嘆號、逗號等；中文用全形「，。？！」，外語用半形「,.?!」），並在每句結束後換行，每句一行。")
      append("2. 若原文該處已有適當的標點（例如已是「？」或「！」），請保留原樣，不要再額外加上句號或重複的標點。")
      append("3. 絕對不可更動任何原始內容：不可翻譯、改寫、增刪、調整字詞或更改任何字元。")
      append("4. 【最重要】原文若含有非中文文字（韓文諺文 한글、日文假名、英文字母、其他外語等），必須一字不差地原樣保留其原始文字與字母，嚴禁將其翻譯、音譯、或轉寫成中文／漢字。例如「안녕하세요」必須維持為「안녕하세요」，絕不可變成「你好」或任何漢字；簡繁字體也一律維持原樣。")
      append("5. 只輸出處理後的逐字稿，每句一行，不要加編號，也不要任何其他說明文字。")
    }
    val out = StringBuilder()
    for (piece in chunk(raw, 4000)) {
      if (out.isNotEmpty()) out.append('\n')
      // temperature 0: this is a mechanical punctuate-and-split task, so the model
      // must stay faithful and never drift into translating the foreign passages.
      //
      // Retry once on a transient failure, then fall back to the raw piece rather
      // than throwing: a single hiccup must not collapse this piece (or, via the
      // caller's raw fallback, the whole ~20-min chunk) into one unpunctuated
      // run-on line. The kept-raw line still splits on any ASR punctuation in
      // displaySentences.
      var segmented: String? = null
      for (attempt in 0..1) {
        try {
          segmented = chat(system, piece, model, apiKey, temperature = 0.0).trim()
          break
        } catch (e: Exception) {
          if (attempt == 1) segmented = piece
        }
      }
      out.append(segmented ?: piece)
    }
    return out.toString()
  }

  // MARK: Translation

  /**
   * Translate a transcript's display [sentences] into [language] via the chat
   * model, returning one translated sentence per input sentence (same count, same
   * order) so the transcript screen can pair each row with its translation.
   * Sentences are sent in whole-line batches so the line count is preserved; each
   * batch's returned line count is reconciled to its input count (pad/truncate) so
   * a stray dropped/added line never shifts the alignment of everything after it.
   * Mirrors the iOS `translateSentences`.
   *
   * [onPartial], when given, is invoked after each batch with the cumulative
   * translation so far (a growing prefix), so a caller can show the translation
   * filling in instead of waiting for the whole transcript.
   */
  suspend fun translateSentences(
    sentences: List<String>,
    language: String,
    model: String,
    apiKey: String,
    onPartial: ((List<String>) -> Unit)? = null,
  ): List<String> {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    if (sentences.isEmpty()) return emptyList()
    val system = buildString {
      append("你是一位專業翻譯。你會收到一段逐字稿，每行一句（內容可能混合中文與外語）。")
      append("請將每一行都翻譯成「$language」。")
      append("規則：")
      append("1.【最重要】輸出的行數必須與輸入完全相同，每一行對應一句翻譯，順序一致，不可合併或拆分行。")
      append("2. 不要加上編號、引號、原文或任何說明文字，只輸出翻譯後的句子，每句一行。")
      append("3. 若某一行已經是目標語言或無法翻譯（例如僅為標點或語助詞），仍要輸出對應的一行（可原樣保留）。")
    }
    val out = ArrayList<String>(sentences.size)
    for (batch in lineBatches(sentences, maxLines = 40, maxChars = 3000)) {
      // temperature 0: keep the mapping faithful and the line count stable.
      val raw = chat(system, batch.joinToString("\n"), model, apiKey, temperature = 0.0)
      val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
      // Reconcile to the batch's line count so indices never drift.
      while (lines.size > batch.size) lines.removeAt(lines.size - 1)
      while (lines.size < batch.size) lines.add("")
      out += lines
      onPartial?.invoke(out.toList())
    }
    return out
  }

  /** Group whole sentences into batches of at most [maxLines] lines or [maxChars]
   *  characters (whichever comes first), never splitting a sentence, so each
   *  batch's line count equals its sentence count. */
  private fun lineBatches(sentences: List<String>, maxLines: Int, maxChars: Int): List<List<String>> {
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var chars = 0
    for (s in sentences) {
      if (current.isNotEmpty() && (current.size >= maxLines || chars + s.length > maxChars)) {
        batches += current
        current = mutableListOf()
        chars = 0
      }
      current += s
      chars += s.length + 1
    }
    if (current.isNotEmpty()) batches += current
    return batches
  }

  // MARK: Handout (chat completion)

  /**
   * Produce an HTML study-handout *fragment* from a transcript.
   *
   * [partTitle] is set when an episode is split into ~15-minute parts: the
   * fragment is prefixed with a "Part …" heading and its four section headings
   * drop to `h3` (nested under the part), so the document reads Part I →
   * 內容說明/文法重點/例句/單字, Part II → …. When null (≤15 min) the four
   * sections are top-level `h2`.
   */
  suspend fun generateHandout(
    transcript: String,
    record: EpisodeRecord,
    partTitle: String? = null,
    model: String,
    apiKey: String,
  ): String {
    if (apiKey.isBlank()) throw OpenAIException("尚未設定 OpenAI API 金鑰")
    val tag = if (partTitle == null) "h2" else "h3"
    val partNote = if (partTitle == null) ""
      else "你收到的是整集節目其中一段（約 15 分鐘）的逐字稿，請只根據這一段的內容製作講義。"
    val system = buildString {
      append("你是一位專業的語言老師，正在為「${record.language}」語言學習教材製作複習講義。")
      append(partNote)
      append("你會收到一段廣播節目的逐字稿，請根據內容整理出一份適合學生複習的講義。")
      append("說明文字一律使用「台灣繁體中文（正體字）」，絕對不要使用簡體字；例句與單字中的外語請保留原貌（不要翻譯或改成中文字）。")
      append("並使用 HTML 格式輸出，依序分成四個區塊：")
      append("<$tag>內容說明</$tag>（用幾句話說明這段內容的主題與大意）、")
      append("<$tag>文法重點</$tag>（列出出現的文法句型，附簡短解說）、")
      append("<$tag>例句</$tag>（從內容中挑選實用例句，逐句附上中文翻譯）、")
      append("<$tag>單字</$tag>（重要單字表，含發音或拼音與中文意思，建議用表格呈現）。")
      append("只輸出 HTML 內容片段（可使用 h2、h3、h4、p、ul、ol、li、table、tr、th、td、strong、em、ruby 等標籤），")
      append("不要輸出 <html>、<head>、<body> 標籤，也不要使用 Markdown 或程式碼圍欄。")
    }
    val user = "節目：${record.programName}\n單集：${record.title}\n\n逐字稿：\n$transcript"
    val fragment = stripCodeFence(chat(system, user, model, apiKey))
    return if (partTitle == null) fragment else "<h2>$partTitle</h2>\n$fragment"
  }

  // MARK: Helpers

  /** One round-trip to POST /chat/completions, returning the message content.
   *  [temperature] is sent only when provided (0 for faithful, mechanical tasks
   *  like sentence segmentation; omitted to keep the model's default elsewhere). */
  private suspend fun chat(
    system: String,
    user: String,
    model: String,
    apiKey: String,
    temperature: Double? = null,
  ): String =
    withContext(Dispatchers.IO) {
      val payload = buildJsonObject {
        put("model", model)
        if (temperature != null) put("temperature", temperature)
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
