package com.example.nerlan.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.example.nerlan.NerLanApp
import com.example.nerlan.player.AudioCache
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Transcodes episode audio to a small mono 16 kHz AAC .m4a before upload, the
 * Android counterpart of the iOS `SpeechAudioExporter`. OpenAI's transcription
 * endpoint caps uploads at 25 MB; spoken audio at 32 kbps stays far under that
 * (a 5-min chunk is ~1.5 MB), and mono 16 kHz is the format speech recognition
 * expects. Uses media3 Transformer (must run on a thread with a Looper).
 *
 * The source is read in place — a local download, or the remote URL through the
 * player's data source (cache-aware, follows cross-protocol redirects) — so
 * nothing is fetched up front: each chunk pulls only its own minutes over HTTP
 * range requests, and the first chunk is ready at the same time whether the
 * episode is 10 minutes or 2 hours.
 */
object AudioTranscoder {
  private const val TAG = "AudioTranscoder"

  /** Max audio duration per transcription request. The gpt-4o-transcribe models
   *  reject audio longer than 1400 s, but latency is what sets this: the first
   *  chunk's text appears once it has been transcoded, uploaded and transcribed,
   *  so 5 min keeps that wait short even on a slow LTE uplink, and the next chunk
   *  transcodes while this one uploads (see [transcodeChunks]). */
  const val MAX_CHUNK_SECONDS = 300L

  /** AAC bitrate of the upload, the same as the iOS exporter. Transformer's default
   *  is 128 kbps, which for 16 kHz mono speech is 4x the bytes for no benefit —
   *  a 13-min episode came out at ~13 MB and took 7 min to upload over LTE. */
  private const val BITRATE = 32_000

  /** One transcoded piece of an episode: [file] holds chunk [index] of [count]. */
  class Chunk(val index: Int, val count: Int, val file: File)

  /** Audio seconds per chunk that [transcodeChunks] will produce for a known
   *  [durationMs]: full chunks then the remainder; empty when the length is
   *  unknown. Drives the progress estimate. */
  fun chunkSeconds(durationMs: Long): List<Double> {
    if (durationMs <= 0L) return emptyList()
    val maxMs = MAX_CHUNK_SECONDS * 1000
    if (durationMs <= maxMs) return listOf(durationMs / 1000.0)
    val count = ceil(durationMs.toDouble() / maxMs).toInt()
    return (0 until count).map { i -> minOf(maxMs, durationMs - i * maxMs) / 1000.0 }
  }

  /**
   * Transcode the audio and split it into chunks each no longer than
   * [MAX_CHUNK_SECONDS], emitted in order as each is ready; the caller deletes the
   * cache files. Collect with `buffer()` so the next chunk transcodes while the
   * current one is being uploaded. [durationMs] is the episode length when known
   * (the feed's figure); 0 probes the source, which for a remote URL costs a
   * round trip. A short episode yields a single chunk, falling back to [fallback]
   * (a local source file, if any) when it can't be transcoded — a short file is
   * fine to upload as-is. A failed chunk of a long episode throws instead, since
   * the source would exceed the API's limits anyway.
   */
  fun transcodeChunks(
    context: Context,
    id: String,
    input: Uri,
    durationMs: Long,
    fallback: File?,
  ): Flow<Chunk> = flow {
    val maxMs = MAX_CHUNK_SECONDS * 1000
    val totalMs = if (durationMs > 0L) durationMs else probeDurationMs(context, input)

    // Unknown or short duration: a single whole-file transcode.
    if (totalMs <= 0L || totalMs <= maxMs) {
      val out = File(context.cacheDir, "ai-speech-$id.m4a").also { it.delete() }
      when {
        toMono16k(context, input, out) -> emit(Chunk(0, 1, out))
        fallback != null -> emit(Chunk(0, 1, fallback))
        else -> throw Exception("音訊轉檔失敗")
      }
      return@flow
    }

    val chunkCount = ceil(totalMs.toDouble() / maxMs).toInt()
    for (i in 0 until chunkCount) {
      val startMs = i.toLong() * maxMs
      val last = i == chunkCount - 1
      // The feed's duration is approximate, so the last chunk runs to the real end
      // rather than the nominal one.
      val endMs = if (last) C.TIME_UNSET else startMs + maxMs
      val out = File(context.cacheDir, "ai-speech-$id-$i.m4a").also { it.delete() }
      if (!toMono16k(context, input, out, startMs, endMs)) {
        // A feed that overstates the length puts the last chunk's start past the
        // real end: that's the end of the audio, not a failure.
        if (last) {
          Log.i(TAG, "chunk $i of $chunkCount failed; treating as end of audio")
          out.delete()
          return@flow
        }
        throw Exception("音訊轉檔失敗（第 ${i + 1}/$chunkCount 段）")
      }
      emit(Chunk(i, chunkCount, out))
    }
  }

  private fun probeDurationMs(context: Context, uri: Uri): Long {
    val mmr = MediaMetadataRetriever()
    return try {
      val scheme = uri.scheme
      if (scheme == "http" || scheme == "https") mmr.setDataSource(uri.toString(), emptyMap())
      else mmr.setDataSource(context, uri)
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
        val started = SystemClock.elapsedRealtime()
        val channelMixing = ChannelMixingAudioProcessor().apply {
          putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1)) // mono passthrough
          putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1)) // stereo -> mono
        }
        val sonic = SonicAudioProcessor().apply { setOutputSampleRateHz(16_000) }
        val encoderFactory = DefaultEncoderFactory.Builder(context)
          .setRequestedAudioEncoderSettings(
            AudioEncoderSettings.Builder().setBitrate(BITRATE).build())
          .build()
        // Read the source through the same data source as playback, so a remote
        // URL streams (and hits the audio cache) instead of needing a download.
        val dataSourceFactory = AudioCache.dataSourceFactory(context) {
          NerLanApp.instance.settings.cacheStreamedAudio.value
        }
        val assetLoaderFactory = DefaultAssetLoaderFactory(
          context,
          DefaultDecoderFactory.Builder(context).build(),
          Clock.DEFAULT,
          DefaultMediaSourceFactory(dataSourceFactory),
          DataSourceBitmapLoader(context),
        )

        val transformer = Transformer.Builder(context)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .setEncoderFactory(encoderFactory)
          .setAssetLoaderFactory(assetLoaderFactory)
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              Log.i(TAG, "${output.name}: ${output.length() / 1024} KB in " +
                "${SystemClock.elapsedRealtime() - started} ms")
              if (cont.isActive) cont.resume(true)
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              Log.w(TAG, "${output.name}: transcode failed", exportException)
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
