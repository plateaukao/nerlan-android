package com.example.nerlan.player

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import com.example.nerlan.NerLanApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Records the learner reading a sentence aloud during shadowing practice and
 * plays it back so they can compare with the original. One clip is kept per
 * (episode, sentence) under cacheDir/shadow; a new attempt overwrites the last.
 * Clips are disposable practice data — not synced.
 *
 * Recording pauses the original first (so the mic isn't fighting playback) via
 * [PlayerManager.pause]. The caller is responsible for holding RECORD_AUDIO
 * before [startRecording]. The Android analog of iOS `ShadowRecorder`; unlike
 * iOS, MediaRecorder.stop() finalizes synchronously, so auto-play can run inline.
 */
object ShadowRecorder {
  private val _isRecording = MutableStateFlow(false)
  val isRecording: StateFlow<Boolean> = _isRecording

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying

  /** Key of the most recently recorded sentence, so the UI re-checks whether
   *  "play my voice" should enable when a take finishes. */
  private val _lastRecordedKey = MutableStateFlow<String?>(null)
  val lastRecordedKey: StateFlow<String?> = _lastRecordedKey

  private var recorder: MediaRecorder? = null
  private var player: MediaPlayer? = null
  private var currentKey: String? = null

  private fun dir(): File = File(NerLanApp.instance.cacheDir, "shadow").apply { mkdirs() }
  private fun file(key: String): File = File(dir(), "$key.m4a")
  fun hasRecording(key: String): Boolean = file(key).exists()

  fun startRecording(key: String) {
    stopPlayback()
    PlayerManager.clearLoop()
    PlayerManager.pause()
    val target = file(key)
    target.delete()
    @Suppress("DEPRECATION")
    val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(NerLanApp.instance)
    } else {
      MediaRecorder()
    }
    val ok = runCatching {
      rec.setAudioSource(MediaRecorder.AudioSource.MIC)
      rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      rec.setAudioEncodingBitRate(96_000)
      rec.setAudioSamplingRate(44_100)
      rec.setOutputFile(target.absolutePath)
      rec.prepare()
      rec.start()
    }.isSuccess
    if (!ok) {
      runCatching { rec.release() }
      target.delete()
      return
    }
    recorder = rec
    currentKey = key
    _isRecording.value = true
  }

  /** Stop recording. When [thenPlay] the take is played back immediately (the
   *  shadowing flow plays your voice right after you stop). */
  fun stopRecording(thenPlay: Boolean = false) {
    val rec = recorder ?: return
    val key = currentKey
    val ok = runCatching { rec.stop() }.isSuccess
    runCatching { rec.release() }
    recorder = null
    _isRecording.value = false
    if (ok && key != null) {
      _lastRecordedKey.value = key
      if (thenPlay) playRecording(key)
    } else if (key != null) {
      file(key).delete()   // unusable clip (stopped too soon / no frames)
    }
  }

  fun playRecording(key: String) {
    stopPlayback()
    val target = file(key)
    if (!target.exists()) return
    PlayerManager.pause()   // don't talk over the original
    val mp = MediaPlayer()
    val ok = runCatching {
      mp.setDataSource(target.absolutePath)
      mp.setOnCompletionListener { stopPlayback() }
      mp.prepare()
      mp.start()
    }.isSuccess
    if (!ok) {
      runCatching { mp.release() }
      return
    }
    player = mp
    _isPlaying.value = true
  }

  fun stopPlayback() {
    player?.let { runCatching { it.stop() }; runCatching { it.release() } }
    player = null
    _isPlaying.value = false
  }

  /** Stop any recording/playback (leaving shadow mode, changing episode, etc.). */
  fun reset() {
    if (_isRecording.value) stopRecording(thenPlay = false)
    stopPlayback()
  }
}
