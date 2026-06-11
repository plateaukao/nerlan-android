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
        }
        override fun onRepeatModeChanged(repeatMode: Int) {
          _repeatMode.value = repeatMode
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
          _current.value = mediaItem?.let(::recordOf)
          _hasNext.value = c.hasNextMediaItem()
          _hasPrevious.value = c.hasPreviousMediaItem()
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
      while (true) {
        controller?.let {
          _positionMs.value = it.currentPosition.coerceAtLeast(0)
          _durationMs.value = it.duration.takeIf { d -> d > 0 } ?: 0
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

  fun next() { controller?.seekToNextMediaItem() }

  fun previous() { controller?.seekToPreviousMediaItem() }

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
