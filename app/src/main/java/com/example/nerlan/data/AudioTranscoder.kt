package com.example.nerlan.data

import android.content.Context
import android.net.Uri
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
  /** Returns true if [output] was written; false (caller falls back to source) on failure. */
  @OptIn(UnstableApi::class)
  suspend fun toMono16k(context: Context, input: Uri, output: File): Boolean =
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

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
          .setRemoveVideo(true)
          .setEffects(Effects(listOf(channelMixing, sonic), emptyList()))
          .build()

        transformer.start(edited, output.absolutePath)
      }
    }
}
