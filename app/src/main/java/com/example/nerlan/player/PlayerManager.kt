package com.example.nerlan.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.EpisodeRecord
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * App-wide playback facade. Connects to PlaybackService via MediaController
 * and exposes Compose-friendly state flows. The queue is whatever episode
 * list the playing item was started from, mirroring the iOS app.
 */
object PlayerManager {
  val AVAILABLE_RATES = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var controller: MediaController? = null

  private val _current = MutableStateFlow<EpisodeRecord?>(null)
  val current: StateFlow<EpisodeRecord?> = _current

  private val _isPlaying = MutableStateFlow(false)
  val isPlaying: StateFlow<Boolean> = _isPlaying

  private val _positionMs = MutableStateFlow(0L)
  val positionMs: StateFlow<Long> = _positionMs

  private val _durationMs = MutableStateFlow(0L)
  val durationMs: StateFlow<Long> = _durationMs

  private val _hasNext = MutableStateFlow(false)
  val hasNext: StateFlow<Boolean> = _hasNext

  private val _hasPrevious = MutableStateFlow(false)
  val hasPrevious: StateFlow<Boolean> = _hasPrevious

  private val _playbackRate = MutableStateFlow(1.0f)
  val playbackRate: StateFlow<Float> = _playbackRate

  private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
  val repeatMode: StateFlow<Int> = _repeatMode

  /** The [startMs, endMs) region currently looping for shadowing, or null. */
  private val _loopRegion = MutableStateFlow<LongRange?>(null)
  val loopRegion: StateFlow<LongRange?> = _loopRegion

  /** Bumped when a finite sentence loop finishes its last pass — the shadowing UI
   *  observes this to auto-start recording the learner's repeat. */
  private val _loopFinishedSignal = MutableStateFlow(0)
  val loopFinishedSignal: StateFlow<Int> = _loopFinishedSignal

  private var loopRemaining: Int? = null
  private var loopJob: Job? = null

  fun initialize(context: Context) {
    if (controller != null) return
    val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    _playbackRate.value = prefs.getFloat("playbackRate", 1.0f)
    _repeatMode.value = prefs.getInt("repeatMode", Player.REPEAT_MODE_OFF)

    val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    val future = MediaController.Builder(context, token).buildAsync()
    future.addListener({
      val c = future.get()
      controller = c
      c.setPlaybackSpeed(_playbackRate.value)
      c.repeatMode = _repeatMode.value
      c.addListener(object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          _isPlaying.value = isPlaying
          // Persist the tally and request a sync when playback stops.
          if (!isPlaying) NerLanApp.instance.stats.flush()
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
          _repeatMode.value = repeatMode
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          // An auto-advance or a repeat-one loop means the previous item played to
          // its end — count it before _current moves on.
          if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            NerLanApp.instance.stats.noteCompleted(_current.value)
          }
          _current.value = mediaItem?.let(::recordOf)
          _hasNext.value = c.hasNextMediaItem()
          _hasPrevious.value = c.hasPreviousMediaItem()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
          // The whole queue finished (no further transition fires) — count the
          // final episode's completion and flush the listening tally.
          if (playbackState == Player.STATE_ENDED) {
            NerLanApp.instance.stats.noteCompleted(_current.value)
          }
        }
        override fun onEvents(player: Player, events: Player.Events) {
          _hasNext.value = player.hasNextMediaItem()
          _hasPrevious.value = player.hasPreviousMediaItem()
        }
      })
      // restore state if the service was already playing
      _current.value = c.currentMediaItem?.let(::recordOf)
      _isPlaying.value = c.isPlaying
    }, MoreExecutors.directExecutor())

    scope.launch {
      // Credit real time spent in the playing state to the listening stats.
      // Gaps from pause/seek/backgrounding (delta >= 5s) are dropped, so this is
      // wall-clock listening time, independent of playback rate.
      var lastTick = 0L
      while (true) {
        controller?.let { c ->
          _positionMs.value = c.currentPosition.coerceAtLeast(0)
          _durationMs.value = c.duration.takeIf { d -> d > 0 } ?: 0
          if (c.isPlaying) {
            val now = System.currentTimeMillis()
            if (lastTick != 0L) {
              val delta = (now - lastTick) / 1000.0
              if (delta in 0.0..5.0) NerLanApp.instance.stats.addListening(delta, _current.value)
            }
            lastTick = now
          } else {
            lastTick = 0L
          }
        }
        delay(500)
      }
    }
  }

  private fun mediaItemOf(record: EpisodeRecord): MediaItem {
    // Prefer the offline copy when one exists.
    val local = NerLanApp.instance.downloads.localPath(record.id)
    val uri = local?.let { Uri.fromFile(java.io.File(it)) } ?: Uri.parse(record.audio ?: "")
    val extras = Bundle().apply { putString("record", json.encodeToString(record)) }
    return MediaItem.Builder()
      .setMediaId(record.id)
      .setUri(uri)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(record.title)
          .setArtist(record.programName)
          .setAlbumTitle(record.language)
          .setArtworkUri(record.coverUrl?.let(Uri::parse))
          .setExtras(extras)
          .build()
      )
      .build()
  }

  private fun recordOf(item: MediaItem): EpisodeRecord? =
    item.mediaMetadata.extras?.getString("record")?.let {
      runCatching { json.decodeFromString<EpisodeRecord>(it) }.getOrNull()
    }

  fun play(record: EpisodeRecord, queue: List<EpisodeRecord>) {
    val c = controller ?: return
    clearLoop()   // a sentence loop never carries across episodes
    val playable = queue.filter { it.audio != null }
    val index = playable.indexOfFirst { it.id == record.id }.coerceAtLeast(0)
    c.setMediaItems(playable.map(::mediaItemOf), index, 0)
    c.prepare()
    c.play()
    _current.value = record
  }

  fun togglePlayPause() {
    val c = controller ?: return
    if (c.isPlaying) c.pause() else c.play()
  }

  fun next() { clearLoop(); controller?.seekToNextMediaItem() }

  fun previous() { clearLoop(); controller?.seekToPreviousMediaItem() }

  /** Pause without touching the queue or loop — used when the recorder takes the
   *  mic, when playing back the learner's own voice, and at a finite loop's end. */
  fun pause() { controller?.pause() }

  // --- Shadowing: single-sentence loop -------------------------------------

  /**
   * Loop a single [startMs, endMs) region — the core of shadowing's sentence
   * repeat. [times] null loops forever; a finite count plays the segment that many
   * times, then stops on the sentence and bumps [loopFinishedSignal] so the UI can
   * auto-start recording. Replaces any region already looping. Media3 exposes no
   * exact boundary callback through MediaController, so a tight poll (~40ms, far
   * finer than the 500ms position loop) watches the end and seeks back.
   */
  fun loopSegment(startMs: Long, endMs: Long, times: Int?) {
    val c = controller ?: return
    val from = startMs.coerceAtLeast(0)
    if (endMs <= from) return
    loopJob?.cancel()
    loopRemaining = times
    _loopRegion.value = from..endMs
    c.seekTo(from)
    c.play()
    loopJob = scope.launch {
      var triggered = false
      while (true) {
        val ctrl = controller ?: break
        val pos = ctrl.currentPosition
        if (!triggered && pos >= endMs) {
          triggered = true
          val rem = loopRemaining
          when {
            rem == null -> ctrl.seekTo(from)
            rem > 1 -> { loopRemaining = rem - 1; ctrl.seekTo(from) }
            else -> {
              // Finite count done: stop on the sentence and signal the UI.
              ctrl.pause()
              _loopRegion.value = null
              loopRemaining = null
              _loopFinishedSignal.value += 1
              break
            }
          }
        } else if (triggered && pos < endMs) {
          triggered = false   // the seek landed; re-arm for the next pass
        }
        delay(40)
      }
    }
  }

  /** Stop any active segment loop; playback continues from wherever it is. */
  fun clearLoop() {
    loopJob?.cancel()
    loopJob = null
    _loopRegion.value = null
    loopRemaining = null
  }

  fun seekTo(ms: Long) { controller?.seekTo(ms) }

  fun skip(deltaMs: Long) {
    val c = controller ?: return
    c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
  }

  /** Cycle no-repeat -> repeat all -> repeat one. */
  fun cycleRepeatMode() {
    val next = when (_repeatMode.value) {
      Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
      Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
      else -> Player.REPEAT_MODE_OFF
    }
    _repeatMode.value = next
    controller?.repeatMode = next
    NerLanApp.instance.getSharedPreferences("player", Context.MODE_PRIVATE)
      .edit().putInt("repeatMode", next).apply()
  }

  fun setRate(rate: Float) {
    _playbackRate.value = rate
    controller?.setPlaybackSpeed(rate)
    NerLanApp.instance.getSharedPreferences("player", Context.MODE_PRIVATE)
      .edit().putFloat("playbackRate", rate).apply()
  }
}
