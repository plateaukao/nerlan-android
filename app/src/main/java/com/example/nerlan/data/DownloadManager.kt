package com.example.nerlan.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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

  /** episodeId -> 0f..1f while a download is in flight */
  private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
  val progress: StateFlow<Map<String, Float>> = _progress

  /** Extensions an episode might be stored under: NER is always mp3, podcasts can
   *  be m4a/aac/etc. Probed in order, so the mp3 common case hits first. */
  private val audioExtensions = listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "mp4")

  /** Where an episode's audio is stored, using its declared extension. */
  private fun audioFileFor(record: EpisodeRecord) = File(audioDir, "${record.id}.${record.audioFileExtension}")

  /** The downloaded audio file for an id, whatever extension it was saved with. */
  private fun existingAudioFile(episodeId: String): File? =
    audioExtensions.asSequence().map { File(audioDir, "$episodeId.$it") }.firstOrNull { it.exists() }

  private fun attachmentFileFor(attachment: Attachment) =
    File(attachmentsDir, "${attachment.attachmentKey}.${attachment.fileExtension}")

  fun isDownloaded(episodeId: String) = existingAudioFile(episodeId) != null

  fun isDownloading(episodeId: String) = _progress.value.containsKey(episodeId)

  fun localPath(episodeId: String): String? = existingAudioFile(episodeId)?.absolutePath

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
    _progress.value += (record.id to 0f)
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
                      _progress.value += (record.id to step / 10f)
                    }
                  }
                }
              }
            }
          }
          tmp.renameTo(dest)
          if (_records.value.none { it.id == record.id }) {
            _records.value += record
            recordsFile.writeText(json.encodeToString(_records.value))
          }
        } catch (_: Exception) {
          tmp.delete()
        } finally {
          _progress.value -= record.id
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

  fun delete(episodeId: String) {
    existingAudioFile(episodeId)?.delete()
    _records.value.firstOrNull { it.id == episodeId }?.attachments.orEmpty().forEach {
      attachmentFileFor(it).delete()
    }
    _records.value = _records.value.filterNot { it.id == episodeId }
    recordsFile.writeText(json.encodeToString(_records.value))
  }
}
