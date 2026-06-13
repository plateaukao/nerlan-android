package com.example.nerlan.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Transcodes episode audio to a small mono 16 kHz AAC .m4a before upload, the
 * Android counterpart of the iOS `SpeechAudioExporter`. OpenAI's transcription
 * endpoint caps uploads at 25 MB; spoken audio at this bitrate stays well under
 * that even for long episodes, and mono 16 kHz is the format speech recognition
 * expects. Uses media3 Transformer (must run on a thread with a Looper).
 */
object AudioTranscoder {
  /** Max audio duration per transcription request. The gpt-4o-transcribe models
   *  reject audio longer than 1400 s; we split below that with margin. whisper-1
   *  has no duration cap, but chunking it as well keeps one code path. */
  const val MAX_CHUNK_SECONDS = 1200L

  /**
   * Transcode the audio and split it into chunks each no longer than
   * [MAX_CHUNK_SECONDS], returned in order (caller deletes the cache files). A
   * short episode yields a single chunk. Falls back to `[source]` if the duration
   * is unknown or a chunk can't be transcoded.
   */
  suspend fun transcodeChunks(context: Context, id: String, input: Uri, source: File): List<File> {
    val maxMs = MAX_CHUNK_SECONDS * 1000
    val durationMs = durationMs(context, input)

    // Unknown or short duration: a single whole-file transcode.
    if (durationMs <= 0L || durationMs <= maxMs) {
      val out = File(context.cacheDir, "ai-speech-$id.m4a").also { it.delete() }
      return if (toMono16k(context, input, out)) listOf(out) else listOf(source)
    }

    val chunkCount = ceil(durationMs.toDouble() / maxMs).toInt()
    val files = mutableListOf<File>()
    for (i in 0 until chunkCount) {
      val startMs = i.toLong() * maxMs
      val endMs = minOf(startMs + maxMs, durationMs)
      val out = File(context.cacheDir, "ai-speech-$id-$i.m4a").also { it.delete() }
      if (toMono16k(context, input, out, startMs, endMs)) {
        files += out
      } else {
        files.forEach { it.delete() }
        return listOf(source) // best effort; a long source will error at the API
      }
    }
    return files
  }

  private fun durationMs(context: Context, uri: Uri): Long {
    val mmr = MediaMetadataRetriever()
    return try {
      mmr.setDataSource(context, uri)
      mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
      0L
    } finally {
      mmr.release()
    }
  }

  /**
   * Returns true if [output] was written; false (caller falls back to source) on
   * failure. [startMs]/[endMs] clip the source to a time range; the defaults
   * transcode the whole file.
   */
  @OptIn(UnstableApi::class)
  suspend fun toMono16k(
    context: Context,
    input: Uri,
    output: File,
    startMs: Long = 0L,
    endMs: Long = C.TIME_UNSET,
  ): Boolean =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        val channelMixing = ChannelMixingAudioProcessor().apply {
          putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1)) // mono passthrough
          putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1)) // stereo -> mono
        }
        val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(16_000) }

        val transformer = Transformer.Builder(context)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              if (cont.isActive) cont.resume(true)
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (cont.isActive) cont.resume(false)
            }
          })
          .build()

        val mediaItem = MediaItem.Builder().setUri(input).apply {
          if (startMs > 0L || endMs != C.TIME_UNSET) {
            setClippingConfiguration(
              MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .apply { if (endMs != C.TIME_UNSET) setEndPositionMs(endMs) }
                .build()
            )
          }
        }.build()
        val edited = EditedMediaItem.Builder(mediaItem)
          .setRemoveVideo(true)
          .setEffects(Effects(listOf(channelMixing, sonic), emptyList()))
          .build()

        transformer.start(edited, output.absolutePath)
      }
    }
}
