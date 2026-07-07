package com.example.nerlan.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.nerlan.NerLanApp

/**
 * Foreground media service: hosts the ExoPlayer instance and a MediaSession,
 * which gives us the media notification, lock-screen controls, and
 * audio-focus handling for free.
 */
class PlaybackService : MediaSessionService() {
  private var mediaSession: MediaSession? = null

  override fun onCreate() {
    super.onCreate()
    // Route streamed bytes through the (opt-in) disk cache; local downloads play
    // straight from file. The write flag is re-read per episode load.
    val dataSourceFactory = AudioCache.dataSourceFactory(this) {
      NerLanApp.instance.settings.cacheStreamedAudio.value
    }
    val player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
          .build(),
        /* handleAudioFocus = */ true,
      )
      .setHandleAudioBecomingNoisy(true)
      // Hold a wake (and wifi) lock while playing: without it, screen-off
      // *streaming* stalls once the buffer drains and the device dozes. The
      // manifest already declares WAKE_LOCK; harmless for local files.
      .setWakeMode(C.WAKE_MODE_NETWORK)
      .build()
    // Without a session activity the media notification has no content intent —
    // tapping the lock-screen/notification card would do nothing.
    val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
      PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
    }
    mediaSession = MediaSession.Builder(this, player)
      .apply { openApp?.let(::setSessionActivity) }
      .build()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
    mediaSession

  override fun onTaskRemoved(rootIntent: Intent?) {
    val player = mediaSession?.player
    if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
      stopSelf()
    }
  }

  override fun onDestroy() {
    mediaSession?.run {
      player.release()
      release()
      mediaSession = null
    }
    super.onDestroy()
  }
}
