package com.example.nerlan.data

import com.example.nerlan.NerLanApp
import com.example.nerlan.player.AudioCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Downloads episode MP3s for offline playback into filesDir/audio/{episodeId}.mp3.
 * Channel+ serves direct audio files, so a plain streaming copy suffices.
 * Episode attachments (PDF handouts) ride along into filesDir/attachments/.
 */
class DownloadManager(filesDir: File) {
  private val recordsFile = File(filesDir, "downloads.json")
  private val cachedRecordsFile = File(filesDir, "cached.json")
  private val audioDir = File(filesDir, "audio").apply { mkdirs() }
  private val attachmentsDir = File(filesDir, "attachments").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** attachmentKeys currently being fetched, to avoid duplicate concurrent downloads. */
  private val attachmentsInFlight = ConcurrentHashMap.newKeySet<String>()

  /**
   * Caps concurrent audio downloads. We use OkHttp's *synchronous* execute(),
   * which bypasses the dispatcher's per-host/total request limits, so tapping
   * download on many episodes would otherwise launch up to ~64 simultaneous
   * connections (Dispatchers.IO's pool) and starve other IO.
   */
  private val audioSemaphore = Semaphore(3)

  private val _records = MutableStateFlow(
    runCatching { json.decodeFromString<List<EpisodeRecord>>(recordsFile.readText()) }
      .getOrNull() ?: emptyList()
  )
  val records: StateFlow<List<EpisodeRecord>> = _records

  /** Records for episodes fully captured by the streamed-audio cache
   *  (player.AudioCache), so the Downloads tab can list them (dimmed) alongside
   *  explicit downloads. The metadata lives in filesDir (`cached.json`) but the
   *  bytes stay in the purgeable LRU cache, so [pruneCachedRecords] drops
   *  entries whose audio no longer survives there. */
  private val _cachedRecords = MutableStateFlow(
    runCatching { json.decodeFromString<List<EpisodeRecord>>(cachedRecordsFile.readText()) }
      .getOrNull() ?: emptyList()
  )
  val cachedRecords: StateFlow<List<EpisodeRecord>> = _cachedRecords

  /** Serializes downloads.json writes: up to 3 download coroutines finish
   *  concurrently (plus deletes from the main thread), and two interleaved
   *  writeText calls can corrupt the file. The value is re-read inside the
   *  lock so the last write always carries the newest state. */
  private val recordsWriteMutex = Mutex()

  private fun persistRecords() {
    scope.launch {
      recordsWriteMutex.withLock {
        runCatching { recordsFile.writeText(json.encodeToString(_records.value)) }
      }
    }
  }

  private fun persistCachedRecords() {
    scope.launch {
      recordsWriteMutex.withLock {
        runCatching { cachedRecordsFile.writeText(json.encodeToString(_cachedRecords.value)) }
      }
    }
  }

  // MARK: Streamed-audio cache records

  /** Register the record for a cache-captured episode so the Downloads tab can
   *  list it. Called by the player when a stream finishes fully cached, and when
   *  an episode loads already-cached — which backfills captures from before
   *  records were kept. */
  fun noteCachedEpisode(record: EpisodeRecord) {
    if (isDownloaded(record.id)) return
    if (_cachedRecords.value.any { it.id == record.id }) return
    _cachedRecords.update { it + record }
    persistCachedRecords()
  }

  /** Drop cached records that became explicit downloads or whose bytes the LRU
   *  cache no longer fully holds. Called at launch with an AudioCache-backed
   *  [fullyCached] check (opens the cache index — run on IO). */
  fun pruneCachedRecords(fullyCached: (EpisodeRecord) -> Boolean) {
    val keep = _cachedRecords.value.filter { !isDownloaded(it.id) && fullyCached(it) }
    if (keep.size != _cachedRecords.value.size) {
      _cachedRecords.value = keep
      persistCachedRecords()
    }
  }

  /** Forget every cache capture; rides along with "clear cached audio". */
  fun clearCachedRecords() {
    if (_cachedRecords.value.isEmpty()) return
    _cachedRecords.value = emptyList()
    persistCachedRecords()
  }

  private fun removeCachedRecord(episodeId: String) {
    val cached = _cachedRecords.value.firstOrNull { it.id == episodeId } ?: return
    _cachedRecords.update { rs -> rs.filterNot { it.id == episodeId } }
    persistCachedRecords()
    // Evict the bytes too (span deletes are file IO, so off the caller's thread).
    scope.launch { AudioCache.removeResource(NerLanApp.instance, cached.audio) }
  }

  /** episodeId -> 0f..1f while a download is in flight */
  private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
  val progress: StateFlow<Map<String, Float>> = _progress

  /** Where an episode's audio is stored, using its declared extension. */
  private fun audioFileFor(record: EpisodeRecord) = File(audioDir, "${record.id}.${record.audioFileExtension}")

  /** episodeId -> completed audio file, seeded by one directory scan. The UI
   *  asks isDownloaded per visible row per recomposition; probing the
   *  filesystem there (formerly up to 7 File.exists per call) did hundreds of
   *  redundant stats while scrolling. Interrupted downloads also left stale
   *  .part files behind forever — swept here at startup. */
  private val downloadedFiles = ConcurrentHashMap<String, File>()

  init {
    listOf(audioDir, attachmentsDir).forEach { dir ->
      dir.listFiles()?.forEach { f ->
        when {
          !f.isFile -> {}
          f.name.endsWith(".part") -> f.delete()
          dir === audioDir -> downloadedFiles[f.nameWithoutExtension] = f
        }
      }
    }
  }

  private fun attachmentFileFor(attachment: Attachment) =
    File(attachmentsDir, "${attachment.attachmentKey}.${attachment.fileExtension}")

  fun isDownloaded(episodeId: String) = downloadedFiles.containsKey(episodeId)

  fun isDownloading(episodeId: String) = _progress.value.containsKey(episodeId)

  fun localPath(episodeId: String): String? = downloadedFiles[episodeId]?.absolutePath

  /** Local copy of an attachment, if it has been downloaded. */
  fun localAttachmentPath(attachment: Attachment): String? =
    attachmentFileFor(attachment).takeIf { it.exists() }?.absolutePath

  fun download(record: EpisodeRecord) {
    downloadAudio(record)
    record.attachments.orEmpty().forEach { downloadAttachment(it) }
  }

  private fun downloadAudio(record: EpisodeRecord) {
    val url = record.audio ?: return
    if (isDownloaded(record.id) || isDownloading(record.id)) return
    _progress.update { it + (record.id to 0f) }
    scope.launch {
      audioSemaphore.withPermit {
        val dest = audioFileFor(record)
        val tmp = File(dest.path + ".part")
        var lastStep = -1
        try {
          val request = Request.Builder().url(url).build()
          ChannelPlusApi.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body ?: throw Exception("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
              tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                  val n = input.read(buffer)
                  if (n < 0) break
                  output.write(buffer, 0, n)
                  copied += n
                  if (total > 0) {
                    // Publish only on 10% steps: emitting on every chunk
                    // allocates a fresh map and recomposes every visible row, and
                    // a 24dp indicator can't show finer than ~10% anyway. So at
                    // most ~10 emissions per download.
                    val step = (copied * 10 / total).toInt().coerceAtMost(10)
                    if (step != lastStep) {
                      lastStep = step
                      _progress.update { it + (record.id to step / 10f) }
                    }
                  }
                }
              }
            }
          }
          tmp.renameTo(dest)
          downloadedFiles[record.id] = dest
          _records.update { rs -> if (rs.any { it.id == record.id }) rs else rs + record }
          persistRecords()
          // An explicit download supersedes any streamed-cache capture.
          removeCachedRecord(record.id)
        } catch (_: Exception) {
          tmp.delete()
        } finally {
          // update {} (CAS), not value -=: concurrent finishes doing plain
          // read-modify-writes can lose the removal, leaving a stuck spinner
          // and an episode that can never be re-downloaded until restart.
          _progress.update { it - record.id }
        }
      }
    }
  }

  /**
   * Fetch an attachment for offline use (no progress UI — handouts are small and
   * ride along with the audio download). Skips files already present or in flight.
   */
  private fun downloadAttachment(attachment: Attachment) {
    val url = attachment.remoteUrl ?: return
    val dest = attachmentFileFor(attachment)
    if (dest.exists() || !attachmentsInFlight.add(attachment.attachmentKey)) return
    scope.launch {
      val tmp = File(dest.path + ".part")
      try {
        val request = Request.Builder().url(url).build()
        ChannelPlusApi.client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
          val body = response.body ?: throw Exception("empty body")
          body.byteStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        }
        tmp.renameTo(dest)
      } catch (_: Exception) {
        tmp.delete()
      } finally {
        attachmentsInFlight.remove(attachment.attachmentKey)
      }
    }
  }

  // Inventory (for the 資料統計 screen).
  fun downloadedBytes(): Long = audioDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  fun attachmentBytes(): Long = attachmentsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

  fun attachmentCount(): Int = attachmentsDir.listFiles()?.count { it.isFile } ?: 0

  /** Remove an episode's local audio — the explicit download and/or the
   *  streamed-cache capture, whichever exist (the Downloads list mixes both). */
  fun delete(episodeId: String) {
    downloadedFiles.remove(episodeId)?.delete()
    _records.value.firstOrNull { it.id == episodeId }?.attachments.orEmpty().forEach {
      attachmentFileFor(it).delete()
    }
    _records.update { rs -> rs.filterNot { it.id == episodeId } }
    persistRecords()
    removeCachedRecord(episodeId)
  }

  /** Backfill [EpisodeRecord.episodeNo] on records saved before the field
   *  existed (see [EpisodeNumberBackfill]). */
  fun applyEpisodeNumbers(numbers: Map<String, Int>) {
    fillEpisodeNumbers(numbers, _records.value)?.let { _records.value = it; persistRecords() }
    fillEpisodeNumbers(numbers, _cachedRecords.value)?.let {
      _cachedRecords.value = it
      persistCachedRecords()
    }
  }
}
