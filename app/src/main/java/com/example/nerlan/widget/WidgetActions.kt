package com.example.nerlan.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.nerlan.MainActivity
import com.example.nerlan.NerLanApp
import com.example.nerlan.player.PlayerManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Widget buttons drive playback directly — the widget runs in the app's process,
// so a broadcast to WidgetActionReceiver can reach PlayerManager and, through it,
// PlaybackService. The controller may not be connected yet (the process can be
// started by the widget alone), which is why every action goes through
// PlayerManager.awaitController.

/** The PendingIntents widgets attach to their views. */
object WidgetIntents {
  const val ACTION_TOGGLE = "com.danielkao.nerlan.widget.TOGGLE"
  const val ACTION_NEXT = "com.danielkao.nerlan.widget.NEXT"
  const val ACTION_SKIP_BACK = "com.danielkao.nerlan.widget.SKIP_BACK"
  const val ACTION_PLAY_EPISODE = "com.danielkao.nerlan.widget.PLAY_EPISODE"
  const val ACTION_PLAY_SHOW = "com.danielkao.nerlan.widget.PLAY_SHOW"
  const val EXTRA_EPISODE_ID = "episodeId"
  const val EXTRA_SHOW_ID = "showId"
  const val EXTRA_IS_PODCAST = "isPodcast"

  private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

  // --- Taps that open the app -----------------------------------------------

  fun openPlayer(context: Context): PendingIntent = open(context, NerLanLink.player())

  fun openShow(context: Context, showId: String, isPodcast: Boolean): PendingIntent =
    open(context, NerLanLink.show(showId, isPodcast))

  fun openTab(context: Context, tab: String): PendingIntent = open(context, NerLanLink.tab(tab))

  private fun open(context: Context, uri: Uri): PendingIntent {
    val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, uri.toString().hashCode(), intent, FLAGS)
  }

  // --- Playback controls ------------------------------------------------------

  fun toggle(context: Context): PendingIntent = broadcast(context, ACTION_TOGGLE)
  fun next(context: Context): PendingIntent = broadcast(context, ACTION_NEXT)
  fun skipBack(context: Context): PendingIntent = broadcast(context, ACTION_SKIP_BACK)

  fun playEpisode(context: Context, episodeId: String): PendingIntent =
    broadcast(context, ACTION_PLAY_EPISODE, EXTRA_EPISODE_ID to episodeId)

  /** Resume a whole show — the 最近播放 button. */
  fun playShow(context: Context, showId: String, isPodcast: Boolean): PendingIntent =
    broadcast(context, ACTION_PLAY_SHOW, EXTRA_SHOW_ID to showId, EXTRA_IS_PODCAST to isPodcast)

  // Extras don't take part in PendingIntent identity, so they are folded into the
  // request code: two buttons for different episodes must be different intents.
  private fun broadcast(context: Context, action: String, vararg extras: Pair<String, Any>): PendingIntent {
    val intent = Intent(context, WidgetActionReceiver::class.java).setAction(action)
    for ((key, value) in extras) {
      when (value) {
        is String -> intent.putExtra(key, value)
        is Boolean -> intent.putExtra(key, value)
      }
    }
    val code = (action + extras.joinToString { "${it.first}=${it.second}" }).hashCode()
    return PendingIntent.getBroadcast(context, code, intent, FLAGS)
  }
}

class WidgetActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val app = context.applicationContext
    WidgetRenderer.scope.launch {
      try {
        // A broadcast receiver gets ~10 s; the redraw is best-effort inside that.
        withTimeoutOrNull(8_000) { handle(app, intent) }
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun handle(context: Context, intent: Intent) {
    when (intent.action) {
      WidgetIntents.ACTION_TOGGLE -> {
        val controller = PlayerManager.awaitController(context)
        if (controller != null && PlayerManager.current.value != null) {
          PlayerManager.togglePlayPause()
        } else {
          // Nothing loaded: continue whatever was last played rather than doing
          // nothing, which is what the button looks like it promises.
          val recent = NerLanApp.instance.recents.shows.value.firstOrNull()
          if (recent != null) PlayerManager.playShow(context, recent.id, recent.isPodcast)
        }
      }
      WidgetIntents.ACTION_PLAY_EPISODE ->
        intent.getStringExtra(WidgetIntents.EXTRA_EPISODE_ID)?.let { PlayerManager.playEpisode(context, it) }
      WidgetIntents.ACTION_PLAY_SHOW ->
        intent.getStringExtra(WidgetIntents.EXTRA_SHOW_ID)?.let {
          PlayerManager.playShow(context, it, intent.getBooleanExtra(WidgetIntents.EXTRA_IS_PODCAST, false))
        }
      WidgetIntents.ACTION_NEXT -> {
        PlayerManager.awaitController(context) ?: return
        PlayerManager.next()
      }
      WidgetIntents.ACTION_SKIP_BACK -> {
        PlayerManager.awaitController(context) ?: return
        PlayerManager.skip(-15_000)
      }
      else -> return
    }
    WidgetRefresher.refreshAll(context)
  }
}

/** `nerlan://` links the widgets hand back to the app, mirroring the iOS scheme. */
object NerLanLink {
  const val SCHEME = "nerlan"

  fun player(): Uri = Uri.parse("$SCHEME://player")

  fun show(id: String, isPodcast: Boolean): Uri = Uri.parse("$SCHEME://show")
    .buildUpon()
    .appendQueryParameter("id", id)
    .appendQueryParameter("podcast", if (isPodcast) "1" else "0")
    .build()

  fun tab(name: String): Uri = Uri.parse("$SCHEME://tab?name=$name")
}
