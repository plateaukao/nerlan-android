package com.example.nerlan.data

import android.content.Context
import android.net.Uri
import com.example.nerlan.NerLanApp
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Request

/** Which AI artifact an action refers to; the prefix keys the job map. */
enum class AiKind(val prefix: String) { TRANSCRIPT("transcript"), HANDOUT("handout") }

/**
 * Owns OpenAI-derived study material for episodes: transcripts and AI handouts.
 * Content is saved as plain files under filesDir/ai/ keyed by episode id (no DB,
 * matching the rest of the app), and per-episode job state is published so the
 * action icons can show progress. Jobs run on this store's scope, so they
 * continue even if the player sheet is dismissed. Mirrors the iOS AIContentStore.
 */
class AIContentStore(private val context: Context) {

  sealed interface JobState {
    data class Running(val note: String) : JobState
    data class Failed(val message: String) : JobState
  }

  private val transcriptsDir = File(context.filesDir, "ai/transcripts").apply { mkdirs() }
  private val handoutsDir = File(context.filesDir, "ai/handouts").apply { mkdirs() }
  private val indexFile = File(context.filesDir, "ai/index.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Keyed "transcript:{id}" / "handout:{id}"; absence means idle. */
  private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
  val jobs: StateFlow<Map<String, JobState>> = _jobs

  /** episode id -> record, for every episode that has a transcript or handout.
   *  Powers the AI tab and supplies metadata for sync; backfilled from
   *  downloads/favorites for content generated before the index existed.
   *  Mirrors the iOS AIContentStore.records. */
  private val _records = MutableStateFlow<Map<String, EpisodeRecord>>(emptyMap())
  val records: StateFlow<Map<String, EpisodeRecord>> = _records

  init {
    _records.value = loadAndBackfillIndex()
  }

  /** Bumped whenever saved content changes (e.g. a delete) so the file-based
   *  `hasTranscript`/`hasHandout` views recompose even when no job is involved. */
  private val _revision = MutableStateFlow(0)
  val revision: StateFlow<Int> = _revision

  private fun transcriptFile(id: String) = File(transcriptsDir, "$id.txt")
  private fun handoutFile(id: String) = File(handoutsDir, "$id.html")

  fun hasTranscript(id: String) = transcriptFile(id).exists()
  fun hasHandout(id: String) = handoutFile(id).exists()

  /** Counts of saved content, for the 資料統計 screen. */
  fun transcriptCount(): Int = transcriptsDir.listFiles()?.count { it.isFile } ?: 0
  fun handoutCount(): Int = handoutsDir.listFiles()?.count { it.isFile } ?: 0
  fun transcriptText(id: String): String? = transcriptFile(id).takeIf { it.exists() }?.readText()
  fun handoutHtml(id: String): String? = handoutFile(id).takeIf { it.exists() }?.readText()

  private fun key(kind: AiKind, id: String) = "${kind.prefix}:$id"
  private fun setJob(key: String, state: JobState) = _jobs.update { it + (key to state) }
  private fun clearJob(key: String) = _jobs.update { it - key }

  // MARK: Record index

  /** Records of episodes that currently have a transcript or handout. */
  fun recordsWithContent(): List<EpisodeRecord> =
    _records.value.values.filter { hasTranscript(it.id) || hasHandout(it.id) }

  /** Re-read the index and bump revision; used after a Drive pull brings in new
   *  records and content files. */
  fun reloadIndex() {
    _records.value = loadAndBackfillIndex()
    _revision.value += 1
  }

  private fun ids(): Set<String> = buildSet {
    transcriptsDir.listFiles()?.forEach { add(it.nameWithoutExtension) }
    handoutsDir.listFiles()?.forEach { add(it.nameWithoutExtension) }
  }

  private fun loadAndBackfillIndex(): Map<String, EpisodeRecord> {
    val loaded = runCatching {
      json.decodeFromString<Map<String, EpisodeRecord>>(indexFile.readText())
    }.getOrNull() ?: emptyMap()
    // Older content has files but no index entry; recover records from
    // downloads/favorites (both created before this store in NerLanApp).
    val known = HashMap<String, EpisodeRecord>()
    NerLanApp.instance.downloads.records.value.forEach { known[it.id] = it }
    NerLanApp.instance.favorites.episodes.value.forEach { known[it.id] = it }
    val merged = loaded.toMutableMap()
    var changed = false
    for (id in ids()) if (id !in merged) known[id]?.let { merged[id] = it; changed = true }
    if (changed) persist(merged)
    return merged
  }

  private fun persist(map: Map<String, EpisodeRecord> = _records.value) {
    runCatching { indexFile.writeText(json.encodeToString(map)) }
  }

  /** Record that an episode now has AI content; persist for the AI tab. */
  private fun noteRecord(record: EpisodeRecord) {
    _records.update { it + (record.id to record) }
    persist()
    NerLanApp.instance.drive.requestSync()
  }

  // MARK: Triggers

  fun processTranscript(record: EpisodeRecord) {
    val k = key(AiKind.TRANSCRIPT, record.id)
    if (_jobs.value.containsKey(k) || hasTranscript(record.id)) return
    scope.launch { runTranscript(record) }
  }

  fun processHandout(record: EpisodeRecord) {
    val k = key(AiKind.HANDOUT, record.id)
    if (_jobs.value.containsKey(k) || hasHandout(record.id)) return
    scope.launch { runHandout(record) }
  }

  fun clearAll() {
    transcriptsDir.listFiles()?.forEach { it.delete() }
    handoutsDir.listFiles()?.forEach { it.delete() }
    _records.value = emptyMap()
    persist()
    _jobs.value = emptyMap()
    _revision.value += 1
  }

  /** Delete one episode's saved content of [kind]. */
  fun delete(kind: AiKind, id: String) {
    when (kind) {
      AiKind.TRANSCRIPT -> transcriptFile(id).delete()
      AiKind.HANDOUT -> handoutFile(id).delete()
    }
    clearJob(key(kind, id))
    // Drop the record once the episode has no AI content left.
    if (!hasTranscript(id) && !hasHandout(id)) {
      _records.update { it - id }
      persist()
    }
    _revision.value += 1
  }

  /** Delete the saved content and immediately re-run it with current settings. */
  fun regenerate(kind: AiKind, record: EpisodeRecord) {
    delete(kind, record.id)
    when (kind) {
      AiKind.TRANSCRIPT -> processTranscript(record)
      AiKind.HANDOUT -> processHandout(record)
    }
  }

  // MARK: Work

  /** Transcribe + segment (idempotent). Returns the saved text, or null on failure. */
  private suspend fun runTranscript(record: EpisodeRecord): String? {
    transcriptText(record.id)?.let { return it }
    val k = key(AiKind.TRANSCRIPT, record.id)
    val settings = NerLanApp.instance.settings
    setJob(k, JobState.Running("處理音訊中…"))
    return try {
      val source = audioFile(record) ?: throw Exception("找不到音訊檔")
      setJob(k, JobState.Running("轉錄中…"))
      // Long episodes are split into chunks (the gpt-4o-transcribe models cap
      // input at 1400 s); transcribe each and join.
      val chunks = AudioTranscoder.transcodeChunks(context, record.id, Uri.fromFile(source), source)
      val prompt = OpenAIService.transcriptionPrompt(record.language)
      val raw = try {
        val parts = mutableListOf<String>()
        for ((i, chunk) in chunks.withIndex()) {
          if (chunks.size > 1) setJob(k, JobState.Running("轉錄中…（${i + 1}/${chunks.size}）"))
          parts += OpenAIService.transcribe(
            chunk, settings.transcriptionModelOrDefault(), settings.apiKey.value, prompt = prompt)
        }
        parts.joinToString("\n")
      } finally {
        cleanupChunks(chunks, source)
      }

      // Re-segment into one sentence per line (adds sentence-ending punctuation
      // only, never alters content); keep the raw transcript if that fails.
      setJob(k, JobState.Running("整理句子中…"))
      val text = runCatching {
        OpenAIService.segmentTranscript(raw, settings.chatModelOrDefault(), settings.apiKey.value)
      }.getOrNull() ?: raw

      transcriptFile(record.id).writeText(text)
      noteRecord(record)
      clearJob(k)
      text
    } catch (e: Exception) {
      setJob(k, JobState.Failed(e.message ?: "處理失敗"))
      null
    }
  }

  private suspend fun runHandout(record: EpisodeRecord) {
    val k = key(AiKind.HANDOUT, record.id)
    val settings = NerLanApp.instance.settings
    setJob(k, JobState.Running("準備逐字稿…"))
    try {
      val transcript = runTranscript(record) ?: run {
        val message = (_jobs.value[key(AiKind.TRANSCRIPT, record.id)] as? JobState.Failed)?.message
        throw Exception(message ?: "逐字稿失敗")
      }
      // Episodes longer than ~15 min are split into time-based parts, each its own
      // Part I/II/III handout section; shorter ones stay a single handout.
      val segments = handoutSegments(transcript, record.durationSeconds)
      val fragments = mutableListOf<String>()
      for ((i, segment) in segments.withIndex()) {
        setJob(k, JobState.Running(
          if (segments.size > 1) "生成講義中…（${i + 1}/${segments.size}）" else "生成講義中…"))
        val partTitle = if (segments.size > 1) partTitle(i, segments.size, record.durationSeconds) else null
        fragments += OpenAIService.generateHandout(
          transcript = segment, record = record, partTitle = partTitle,
          model = settings.chatModelOrDefault(), apiKey = settings.apiKey.value)
      }
      handoutFile(record.id).writeText(fragments.joinToString("\n"))
      noteRecord(record)
      clearJob(k)
    } catch (e: Exception) {
      setJob(k, JobState.Failed(e.message ?: "處理失敗"))
    }
  }

  /** Local download if present, otherwise fetch the remote audio to a cache file. */
  private fun audioFile(record: EpisodeRecord): File? {
    NerLanApp.instance.downloads.localPath(record.id)?.let { return File(it) }
    val url = record.audio ?: return null
    val tmp = File(context.cacheDir, "ai-audio-${record.id}.mp3").also { it.delete() }
    val request = Request.Builder().url(url).build()
    ChannelPlusApi.client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
      val body = response.body ?: throw Exception("empty body")
      body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
    }
    return tmp
  }

  /** Delete the transcoded chunk temps and any audio we downloaded to cache, keeping offline downloads. */
  private fun cleanupChunks(chunks: List<File>, source: File) {
    val cache = context.cacheDir
    chunks.forEach { if (it != source && it.parentFile == cache) it.delete() }
    if (source.parentFile == cache) source.delete()
  }

  companion object {
    /** Each handout "Part" covers at most this many seconds of audio (~15 min). */
    private const val HANDOUT_PART_SECONDS = 900

    /**
     * Split the transcript into one segment per ~15-minute part. Returns a single
     * segment when the episode is ≤15 min (or its length is unknown and the
     * transcript is short). Segments are balanced by character count and broken
     * only at line (sentence) boundaries. Mirrors the iOS `handoutSegments`.
     */
    fun handoutSegments(transcript: String, durationSeconds: Int?): List<String> {
      val parts = if (durationSeconds != null && durationSeconds > 0) {
        maxOf(1, ceil(durationSeconds.toDouble() / HANDOUT_PART_SECONDS).toInt())
      } else {
        // Unknown duration: ~3500 chars ≈ 15 min of speech.
        maxOf(1, ceil(transcript.length / 3500.0).toInt())
      }
      if (parts <= 1) return listOf(transcript)

      val target = maxOf(1, transcript.length / parts)
      val segments = mutableListOf<String>()
      val current = StringBuilder()
      var currentChars = 0
      for (line in transcript.split("\n")) {
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
        currentChars += line.length + 1
        if (currentChars >= target && segments.size < parts - 1) {
          segments += current.toString()
          current.setLength(0)
          currentChars = 0
        }
      }
      if (current.isNotEmpty()) segments += current.toString()
      return segments
    }

    /** "Part I（00:00–15:00）" — Roman numeral plus the part's audio time range
     *  (range omitted when the duration is unknown). */
    fun partTitle(index: Int, total: Int, duration: Int?): String {
      val label = "Part ${romanNumeral(index + 1)}"
      if (duration == null || duration <= 0) return label
      val start = index * HANDOUT_PART_SECONDS
      val end = if (index == total - 1) duration else (index + 1) * HANDOUT_PART_SECONDS
      return "$label（${timeStamp(start)}–${timeStamp(end)}）"
    }

    private fun timeStamp(seconds: Int): String {
      val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
      return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun romanNumeral(value: Int): String {
      val table = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
      var n = value
      val sb = StringBuilder()
      for ((v, r) in table) while (n >= v) { sb.append(r); n -= v }
      return sb.toString()
    }
  }
}
