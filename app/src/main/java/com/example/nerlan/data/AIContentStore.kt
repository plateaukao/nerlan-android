package com.example.nerlan.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.example.nerlan.NerLanApp
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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

  /** A transcript still being produced, published per ~20-min audio chunk so the
   *  viewer can show the first chunk while later chunks transcribe. [cues] is empty
   *  or 1:1 with [sentences], never partially aligned. In-memory only — never
   *  persisted or synced; the saved file is the source of truth once written. */
  data class PartialTranscript(val sentences: List<String>, val cues: List<TranscriptCue>)

  private val transcriptsDir = File(context.filesDir, "ai/transcripts").apply { mkdirs() }
  private val handoutsDir = File(context.filesDir, "ai/handouts").apply { mkdirs() }
  /** Sidecar timestamp cues for transcripts, keyed by episode id. Synced as
   *  write-once content files alongside the transcript (see DriveSync). */
  private val cuesDir = File(context.filesDir, "ai/cues").apply { mkdirs() }
  /** Per-episode transcript translations (StoredTranslation JSON), synced as a
   *  content file alongside the transcript. */
  private val translationsDir = File(context.filesDir, "ai/translations").apply { mkdirs() }
  private val indexFile = File(context.filesDir, "ai/index.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Keyed "transcript:{id}" / "handout:{id}"; absence means idle. */
  private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
  val jobs: StateFlow<Map<String, JobState>> = _jobs

  /** Estimated overall progress (0–1) of a running transcription, keyed by
   *  episode id, so the action button can show a percentage. Audio seconds
   *  finished plus a wall-clock estimate of the chunk in flight — see
   *  [runTranscript] / [progressTicker]. Mirrors the iOS transcriptProgress. */
  private val _transcriptProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
  val transcriptProgress: StateFlow<Map<String, Float>> = _transcriptProgress

  /** Translation jobs, keyed by episode id; absence means idle. Translation is
   *  triggered from the transcript screen (not the shared AI action buttons), so
   *  it gets its own job map. */
  private val _translationJobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
  val translationJobs: StateFlow<Map<String, JobState>> = _translationJobs

  /** Transcript content streamed while a transcription job runs, keyed by episode
   *  id; absence means use the saved file. See [PartialTranscript]. */
  private val _partialTranscripts = MutableStateFlow<Map<String, PartialTranscript>>(emptyMap())
  val partialTranscripts: StateFlow<Map<String, PartialTranscript>> = _partialTranscripts

  /** Translation streamed per ~40-sentence batch while a translation job runs,
   *  keyed by episode id, so the transcript screen fills in top-down. Carries the
   *  target language so a partial for the wrong language is ignored. */
  private val _partialTranslations = MutableStateFlow<Map<String, StoredTranslation>>(emptyMap())
  val partialTranslations: StateFlow<Map<String, StoredTranslation>> = _partialTranslations

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
  private fun cueFile(id: String) = File(cuesDir, "$id.json")
  private fun translationFile(id: String) = File(translationsDir, "$id.json")

  fun hasTranscript(id: String) = transcriptFile(id).exists()
  fun hasHandout(id: String) = handoutFile(id).exists()

  /** The saved translation for an episode, if any. Carries its target language so
   *  the transcript screen can tell whether it matches the current setting. */
  fun translation(id: String): StoredTranslation? =
    translationFile(id).takeIf { it.exists() }?.let {
      runCatching { json.decodeFromString<StoredTranslation>(it.readText()) }.getOrNull()
    }

  /** Timestamp cues for an episode's transcript, when present. Null for transcripts
   *  made before cues existed (or with a no-timestamp model), which the transcript
   *  screen then renders without highlighting. */
  fun transcriptCues(id: String): List<TranscriptCue>? =
    cueFile(id).takeIf { it.exists() }?.let {
      runCatching { json.decodeFromString<List<TranscriptCue>>(it.readText()) }.getOrNull()
    }

  /** Counts of saved content, for the 資料統計 screen. */
  fun transcriptCount(): Int = transcriptsDir.listFiles()?.count { it.isFile } ?: 0
  fun handoutCount(): Int = handoutsDir.listFiles()?.count { it.isFile } ?: 0
  fun translationCount(): Int = translationsDir.listFiles()?.count { it.isFile } ?: 0
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

  /** Backfill [EpisodeRecord.episodeNo] on index records saved before the field
   *  existed (see [EpisodeNumberBackfill]). */
  fun applyEpisodeNumbers(numbers: Map<String, Int>) {
    var changed = false
    val updated = _records.value.mapValues { (id, r) ->
      if (r.episodeNo != null) r
      else numbers[id]?.let { changed = true; r.copy(episodeNo = it) } ?: r
    }
    if (!changed) return
    _records.value = updated
    persist(updated)
  }

  // MARK: Triggers

  fun processTranscript(record: EpisodeRecord) {
    val k = key(AiKind.TRANSCRIPT, record.id)
    // Only a *running* job blocks a new one — a Failed entry must not, or the
    // error dialog's 重試 button would be a permanent no-op.
    if (_jobs.value[k] is JobState.Running || hasTranscript(record.id)) return
    scope.launch { awaitTranscript(record) }
  }

  fun processHandout(record: EpisodeRecord) {
    val k = key(AiKind.HANDOUT, record.id)
    if (_jobs.value[k] is JobState.Running || hasHandout(record.id)) return
    scope.launch { runHandout(record) }
  }

  /** In-flight transcription per episode. Every path that needs a transcript —
   *  the 逐字稿 button, a handout run, a double tap — goes through
   *  [awaitTranscript] and joins the run already in flight instead of paying
   *  OpenAI for a second transcription of the same audio. */
  private val transcriptRuns = HashMap<String, Deferred<String?>>()

  /** The saved transcript, or the result of the episode's (possibly already
   *  running) transcription job. */
  private suspend fun awaitTranscript(record: EpisodeRecord): String? {
    transcriptText(record.id)?.let { return it }
    return synchronized(transcriptRuns) {
      transcriptRuns.getOrPut(record.id) {
        scope.async { runTranscript(record) }.also { run ->
          run.invokeOnCompletion {
            synchronized(transcriptRuns) {
              if (transcriptRuns[record.id] === run) transcriptRuns.remove(record.id)
            }
          }
        }
      }
    }.await()
  }

  /** Generate (or regenerate, if the language changed) the translation for an
   *  episode's transcript. No-ops while a job is already running for it. */
  fun translate(record: EpisodeRecord) {
    if (_translationJobs.value[record.id] is JobState.Running) return
    scope.launch { runTranslation(record) }
  }

  fun clearAll() {
    transcriptsDir.listFiles()?.forEach { it.delete() }
    handoutsDir.listFiles()?.forEach { it.delete() }
    cuesDir.listFiles()?.forEach { it.delete() }
    translationsDir.listFiles()?.forEach { it.delete() }
    _records.value = emptyMap()
    persist()
    _jobs.value = emptyMap()
    _translationJobs.value = emptyMap()
    _partialTranscripts.value = emptyMap()
    _partialTranslations.value = emptyMap()
    _revision.value += 1
  }

  /** Delete one episode's saved content of [kind]. */
  fun delete(kind: AiKind, id: String) {
    when (kind) {
      // The cue + translation sidecars are derived from this transcript, so they
      // go with it (a regenerated transcript may re-segment differently).
      AiKind.TRANSCRIPT -> { transcriptFile(id).delete(); cueFile(id).delete(); translationFile(id).delete(); _translationJobs.update { it - id }; _partialTranscripts.update { it - id }; _partialTranslations.update { it - id } }
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

  /** Transcribe + segment (idempotent). Returns the saved text, or null on failure.
   *  Callers go through [awaitTranscript] so only one run exists per episode. */
  private suspend fun runTranscript(record: EpisodeRecord): String? {
    transcriptText(record.id)?.let { return it }
    val k = key(AiKind.TRANSCRIPT, record.id)
    val settings = NerLanApp.instance.settings
    Log.i("AIContentStore", "transcription run started for ${record.id}")
    setJob(k, JobState.Running("處理音訊中…"))
    return try {
      // The audio is read in place — a local download if there is one, else the
      // remote URL streamed through the player's data source — so nothing is
      // fetched up front: chunk 0 pulls only its own five minutes, and the first
      // text appears after the same wait whether the episode is 10 min or 2 h.
      val local = NerLanApp.instance.downloads.localPath(record.id)?.let { File(it) }
      val input = local?.let { Uri.fromFile(it) }
        ?: record.audio?.let { Uri.parse(it) }
        ?: throw Exception("找不到音訊檔")
      setJob(k, JobState.Running("轉錄中…"))
      // Long episodes are split into 5-min chunks (the gpt-4o-transcribe models cap
      // input at 1400 s anyway). Each chunk is transcribed, re-segmented and aligned
      // on its own, then appended and published — so the viewer shows the first
      // chunk while later chunks are still in flight, rather than waiting for the
      // whole episode. The transcoder is a flow collected with buffer(), so it runs
      // ahead in its own coroutine: chunk n+1 is transcoded (CPU/codec) while chunk
      // n is uploading and transcribing (network) — the two never wait on each other.
      val durationMs = (record.durationSeconds ?: 0) * 1000L
      val chunks = AudioTranscoder.transcodeChunks(context, record.id, input, durationMs, fallback = local)
      // Progress estimate: the chunk plan gives the overall scale; the per-audio-
      // second processing rate (transcode + upload + transcription + sentence
      // cleanup) is seeded per server+model and refined from each finished chunk.
      val plan = AudioTranscoder.chunkSeconds(durationMs)
      val totalSeconds = plan.sum()
      val showProgress = totalSeconds > 0
      val rateKey = rateKey(settings.transcriptionConfig())
      var rate = savedRate(rateKey, fallback = 0.3)
      var doneSeconds = 0.0
      var ticker: Job? = null
      var chunkStarted = SystemClock.elapsedRealtime()
      fun startTicker(index: Int) {
        ticker?.cancel()
        ticker = if (showProgress && index < plan.size) {
          progressTicker(record.id, done = doneSeconds, chunkSeconds = plan[index], total = totalSeconds, rate = rate)
        } else null
      }
      // A monolingual source (a podcast) carries its locale: force that language and
      // drop the Chinese teaching prompt, which would otherwise bias a foreign-language
      // podcast toward Chinese. NER programs are bilingual (Mandarin host + foreign
      // examples), so they keep the priming prompt and no forced language.
      val locale = record.audioLocale
      val prompt = if (locale == null) OpenAIService.transcriptionPrompt(record.language) else null
      val sentences = mutableListOf<String>()
      val cues = mutableListOf<TranscriptCue>()
      // Cues stay usable only while every chunk so far yields timestamps; once one
      // doesn't (e.g. a non-whisper model), render without highlighting rather than
      // with cues that drift out of alignment.
      var cuesAligned = true
      try {
        startTicker(0)
        chunks.buffer(2).collect { chunk ->
          val i = chunk.index
          val multi = chunk.count > 1
          setJob(k, JobState.Running(if (multi) "轉錄中…（${i + 1}/${chunk.count}）" else "轉錄中…"))
          val t0 = SystemClock.elapsedRealtime()
          val result = OpenAIService.transcribe(
            chunk.file, settings.transcriptionConfig(), prompt = prompt, language = locale)
          Log.i("AIContentStore", "chunk ${i + 1}/${chunk.count}: ${chunk.file.length() / 1024} KB " +
            "transcribed in ${SystemClock.elapsedRealtime() - t0} ms")
          // Each chunk is transcoded 0-based, so shift its timestamps onto the
          // absolute episode timeline by the chunk's start offset.
          val offset = i * AudioTranscoder.MAX_CHUNK_SECONDS.toDouble()
          val chunkSegments = result.segments.map { OpenAIService.Segment(it.start + offset, it.text) }

          // Re-segment just this chunk into one sentence per line (adds punctuation
          // only, never alters content); keep its raw text if that fails so the paid
          // transcription isn't lost. Then align its sentences to its own timestamps.
          setJob(k, JobState.Running(if (multi) "整理句子中…（${i + 1}/${chunk.count}）" else "整理句子中…"))
          val t1 = SystemClock.elapsedRealtime()
          val chunkText = runCatching {
            OpenAIService.segmentTranscript(result.text, settings.chatConfig())
          }.getOrNull() ?: result.text
          Log.i("AIContentStore", "chunk ${i + 1}/${chunk.count}: segmented in ${SystemClock.elapsedRealtime() - t1} ms")
          val chunkSentences = displaySentences(chunkText)
          val chunkCues = alignCues(chunkSentences, chunkSegments)

          // This chunk is done: blend the measured rate in for the next chunk's
          // estimate, snap progress to the chunk boundary, and start the next ticker.
          val chunkAudioSeconds = plan.getOrNull(i) ?: 0.0
          if (chunkAudioSeconds > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - chunkStarted) / 1000.0
            rate = (rate + elapsed / chunkAudioSeconds) / 2
          }
          doneSeconds += chunkAudioSeconds
          if (showProgress) {
            _transcriptProgress.update { it + (record.id to minOf(doneSeconds / totalSeconds, 0.99).toFloat()) }
          }
          chunkStarted = SystemClock.elapsedRealtime()
          startTicker(i + 1)

          sentences += chunkSentences
          if (cuesAligned && chunkCues.size == chunkSentences.size) cues += chunkCues else cuesAligned = false

          // Publish what's ready so an open viewer shows this chunk now. Only attach
          // cues when they still line up 1:1 with the sentences.
          val cuesSoFar = if (cuesAligned && cues.size == sentences.size) cues.toList() else emptyList()
          _partialTranscripts.update { it + (record.id to PartialTranscript(sentences.toList(), cuesSoFar)) }
        }
      } finally {
        ticker?.cancel()
        _transcriptProgress.update { it - record.id }
        cleanupAudio(record.id)
      }
      if (showProgress) recordRate(rateKey, rate)

      val text = sentences.joinToString("\n")
      transcriptFile(record.id).writeText(text)

      // Best effort: no usable timestamps (a non-whisper model) means no cue file and
      // the transcript shows without highlighting.
      val alignedCues = if (cuesAligned && cues.size == sentences.size) cues else emptyList()
      if (alignedCues.isNotEmpty()) cueFile(record.id).writeText(json.encodeToString(alignedCues))
      else cueFile(record.id).delete()

      // The saved file is now the source of truth; drop the streamed partial. Bump
      // revision so the file-based views (panel, caption) re-read the finished text.
      _partialTranscripts.update { it - record.id }
      noteRecord(record)
      _revision.value += 1
      clearJob(k)
      text
    } catch (e: Exception) {
      // Nothing was saved, so drop any streamed partial; the button shows failed.
      _partialTranscripts.update { it - record.id }
      setJob(k, JobState.Failed(e.message ?: "處理失敗"))
      null
    }
  }

  private suspend fun runHandout(record: EpisodeRecord) {
    val k = key(AiKind.HANDOUT, record.id)
    val settings = NerLanApp.instance.settings
    setJob(k, JobState.Running("準備逐字稿…"))
    try {
      val transcript = awaitTranscript(record) ?: run {
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
          config = settings.chatConfig())
      }
      handoutFile(record.id).writeText(fragments.joinToString("\n"))
      noteRecord(record)
      clearJob(k)
    } catch (e: Exception) {
      setJob(k, JobState.Failed(e.message ?: "處理失敗"))
    }
  }

  /** Translate an episode's transcript into the current target language and save
   *  it sentence-aligned. Overwrites any existing translation (e.g. when the target
   *  language changed). Requires the transcript to already exist. */
  private suspend fun runTranslation(record: EpisodeRecord) {
    val id = record.id
    val settings = NerLanApp.instance.settings
    val language = settings.translationLanguage.value
    val text = transcriptText(id) ?: run {
      _translationJobs.update { it + (id to JobState.Failed("找不到逐字稿")) }
      return
    }
    _translationJobs.update { it + (id to JobState.Running("翻譯中…")) }
    try {
      val sentences = displaySentences(text)
      // Publish each finished batch (~40 sentences) so the transcript screen fills
      // in top-down instead of waiting for the whole transcript.
      val translated = OpenAIService.translateSentences(
        sentences, language, settings.chatConfig(),
        onPartial = { soFar -> _partialTranslations.update { it + (id to StoredTranslation(language, soFar)) } })
      translationFile(id).writeText(json.encodeToString(StoredTranslation(language, translated)))
      noteRecord(record)
      // The saved file is now the source of truth; drop the streamed partial.
      _partialTranslations.update { it - id }
      _translationJobs.update { it - id }
      _revision.value += 1
    } catch (e: Exception) {
      _partialTranslations.update { it - id }
      _translationJobs.update { it + (id to JobState.Failed(e.message ?: "翻譯失敗")) }
    }
  }


  /** Delete the transcoded chunk temps and any audio we downloaded to cache, keeping offline downloads. */
  // --- Progress estimate ------------------------------------------------------

  /** Publishes [transcriptProgress] about once a second while one chunk is in
   *  flight: [done] audio-seconds already finished, plus the current chunk scaled
   *  by elapsed wall time against its expected processing time ([rate] seconds of
   *  processing per second of audio). The in-chunk estimate caps at 95% so it
   *  never claims a chunk that hasn't come back, and the total at 99% — only real
   *  completion removes the entry. */
  private fun progressTicker(id: String, done: Double, chunkSeconds: Double, total: Double, rate: Double): Job =
    scope.launch {
      val started = SystemClock.elapsedRealtime()
      while (isActive) {
        val expected = maxOf(chunkSeconds * rate, 5.0)
        val frac = minOf((SystemClock.elapsedRealtime() - started) / 1000.0 / expected, 0.95)
        _transcriptProgress.update { it + (id to minOf((done + frac * chunkSeconds) / total, 0.99).toFloat()) }
        delay(1_000)
      }
    }

  /** Rates vary enormously by server and model (and here by network: the upload
   *  is part of it), so each finished run records its measured rate keyed by
   *  server+model; the hardcoded seed only matters the first time. */
  private fun rateKey(config: OpenAIService.Config): String =
    "${Uri.parse(config.baseUrl).host ?: "?"}|${config.model}"

  private val ratePrefs get() = context.getSharedPreferences("transcription_rates", Context.MODE_PRIVATE)

  private fun savedRate(key: String, fallback: Double): Double =
    ratePrefs.getFloat(key, fallback.toFloat()).toDouble()

  private fun recordRate(key: String, measured: Double) {
    if (measured <= 0 || !measured.isFinite()) return
    // Blend with the previous value so one outlier run doesn't dominate.
    val previous = ratePrefs.getFloat(key, -1f)
    val blended = if (previous > 0) (previous + measured) / 2 else measured
    ratePrefs.edit().putFloat(key, blended.toFloat()).apply()
  }

  /** Delete the transcoded chunks of [id], including any the transcoder produced
   *  ahead of a failure. */
  private fun cleanupAudio(id: String) {
    context.cacheDir.listFiles { f -> f.name.startsWith("ai-speech-$id") }?.forEach { it.delete() }
  }

  companion object {
    /** The display sentences of a stored transcript: one trimmed, non-empty
     *  sentence each. Must match how the transcript UI splits the same text so cues
     *  line up one-to-one with the rendered rows.
     *
     *  We split on newlines *and* on sentence-ending punctuation. The chat segmenter
     *  normally returns one sentence per line, but when it fails (the raw ASR text
     *  is used as-is) or a weaker model returns a run-on paragraph, a whole ~20-min
     *  chunk can arrive as a single line — splitting on terminators too keeps the
     *  transcript from showing a wall of run-together sentences. It is idempotent on
     *  already-one-per-line text, so cue alignment stays 1:1. */
    fun displaySentences(text: String): List<String> =
      text.split("\n").flatMap { splitSentences(it) }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Break one line into sentences at sentence-ending punctuation. Full-width CJK
     *  enders (。！？) always end a sentence; half-width (. ! ?) only when followed by
     *  whitespace or the line end, so decimals (3.14), ellipses (wait...) and the
     *  like stay intact. A run of enders plus any trailing closing marks (」』”）) is
     *  kept with the sentence it closes. */
    private fun splitSentences(line: String): List<String> {
      val cjkEnders = setOf('。', '！', '？')
      val asciiEnders = setOf('.', '!', '?')
      val closers = setOf('」', '』', '”', '’', '）', ')', '"', '\'')
      val result = mutableListOf<String>()
      var start = 0
      var i = 0
      while (i < line.length) {
        val c = line[i]
        val isEnder = when {
          c in cjkEnders -> true
          // End-of-line counts: the default ' ' makes the final char split.
          c in asciiEnders -> (if (i + 1 < line.length) line[i + 1] else ' ').let { it == ' ' || it == '\t' }
          else -> false
        }
        if (isEnder) {
          var j = i + 1
          while (j < line.length && (line[j] in cjkEnders || line[j] in asciiEnders || line[j] in closers)) j++
          result.add(line.substring(start, j))
          start = j
          i = j
        } else {
          i++
        }
      }
      if (start < line.length) result.add(line.substring(start))
      return if (result.isEmpty()) listOf(line) else result
    }

    /**
     * Map each cleaned display sentence to a start time by aligning it to the timed
     * ASR [segments]. The chat re-segmentation only adds punctuation and line breaks
     * — never changing content characters — so a sentence's content (letters/digits,
     * ignoring spaces and punctuation) appears in order within the segment stream.
     * Walk both monotonically and read each sentence's start off the segment covering
     * its first content character. Returns [] if there are no segments to align to.
     */
    fun alignCues(sentences: List<String>, segments: List<OpenAIService.Segment>): List<TranscriptCue> {
      if (segments.isEmpty() || sentences.isEmpty()) return emptyList()
      val chars = ArrayList<Char>()
      val times = ArrayList<Double>()
      for (seg in segments) for (c in seg.text) if (c.isLetterOrDigit()) { chars.add(c); times.add(seg.start) }
      if (chars.isEmpty()) return emptyList()

      val cues = ArrayList<TranscriptCue>(sentences.size)
      var idx = 0
      var lastStart = times[0]
      for (sentence in sentences) {
        val content = sentence.filter { it.isLetterOrDigit() }
        val first = content.firstOrNull()
        if (first != null) {
          // Resync: find this sentence's first content char at/after the cursor,
          // scanning a small window to absorb minor ASR/chat drift.
          val limit = minOf(chars.size, idx + 32)
          var probe = idx
          while (probe < limit && chars[probe] != first) probe++
          if (probe < limit) idx = probe
        }
        val start = if (idx < times.size) times[idx] else lastStart
        lastStart = start
        cues.add(TranscriptCue(start, sentence))
        idx = minOf(chars.size, idx + maxOf(1, content.length))
      }
      return cues
    }

    /** Each handout "Part" covers at most this many seconds of audio (~15 min). */
    private const val HANDOUT_PART_SECONDS = 900

    /** A final part shorter than this (10 min) is a stub: the last two parts merge
     *  and re-split evenly so the episode doesn't end on a sliver. */
    private const val HANDOUT_MIN_TAIL_SECONDS = 600

    /**
     * Cut points (in seconds) for a `duration`-second episode's handout parts:
     * `[0, b1, …, duration]`, so part `i` spans `bounds[i]..bounds[i+1]`. Parts cap
     * at ~15 min, but when the final part would run shorter than 10 min the last two
     * parts merge and re-split evenly — e.g. 35 min yields `[0, 900, 1500, 2100]`
     * (0–15, 15–25, 25–35) rather than `[0, 900, 1800, 2100]` (0–15, 15–30, 30–35).
     * The single source of truth for both `handoutSegments` and `partTitle`, so the
     * text split and the time labels always agree. Mirrors the iOS version.
     */
    fun handoutPartBoundaries(duration: Int): List<Int> {
      val parts = maxOf(1, ceil(duration.toDouble() / HANDOUT_PART_SECONDS).toInt())
      if (parts <= 1) return listOf(0, duration)
      val bounds = ((0 until parts).map { it * HANDOUT_PART_SECONDS } + duration).toMutableList()
      // bounds[parts] == duration; the last part spans bounds[parts-1]..duration.
      if (duration - bounds[parts - 1] < HANDOUT_MIN_TAIL_SECONDS) {
        val mergedStart = bounds[parts - 2]
        bounds[parts - 1] = mergedStart + (duration - mergedStart) / 2
      }
      return bounds
    }

    /**
     * Split the transcript into one segment per ~15-minute part. Returns a single
     * segment when the episode is ≤15 min (or its length is unknown and the
     * transcript is short). When the duration is known each part's text is sized in
     * proportion to its `handoutPartBoundaries` time span (assuming a roughly
     * constant speaking rate), so the segments line up with the Part I/II/III time
     * labels; with an unknown duration they fall back to equal character counts.
     * Breaks land only at line (sentence) boundaries. Mirrors the iOS version.
     */
    fun handoutSegments(transcript: String, durationSeconds: Int?): List<String> {
      val total = transcript.length
      // Cumulative character counts at which to cut, one per internal boundary.
      val cutAt: List<Int> = if (durationSeconds != null && durationSeconds > 0) {
        val bounds = handoutPartBoundaries(durationSeconds)
        if (bounds.size <= 2) return listOf(transcript)   // single part
        bounds.drop(1).dropLast(1).map { (total.toDouble() * it / durationSeconds).roundToInt() }
      } else {
        // Unknown duration: ~3500 chars ≈ 15 min of speech, split evenly.
        val parts = maxOf(1, ceil(total / 3500.0).toInt())
        if (parts <= 1) return listOf(transcript)
        (1 until parts).map { total * it / parts }
      }

      val segments = mutableListOf<String>()
      val current = StringBuilder()
      var cumChars = 0
      var next = 0
      for (line in transcript.split("\n")) {
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
        cumChars += line.length + 1
        if (next < cutAt.size && cumChars >= cutAt[next]) {
          segments += current.toString()
          current.setLength(0)
          next += 1
        }
      }
      if (current.isNotEmpty()) segments += current.toString()
      return segments
    }

    /** "Part I（00:00–15:00）" — Roman numeral plus the part's audio time range
     *  (range omitted when the duration is unknown). The range comes from
     *  `handoutPartBoundaries`, the same cut points `handoutSegments` splits on. */
    fun partTitle(index: Int, total: Int, duration: Int?): String {
      val label = "Part ${romanNumeral(index + 1)}"
      if (duration == null || duration <= 0) return label
      val bounds = handoutPartBoundaries(duration)
      if (index + 1 >= bounds.size) return label
      return "$label（${timeStamp(bounds[index])}–${timeStamp(bounds[index + 1])}）"
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
