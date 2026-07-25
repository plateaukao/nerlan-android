package com.example.nerlan.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import com.example.nerlan.MainActivity
import com.example.nerlan.player.PlayerManager

// Widget buttons drive playback directly — the widget runs in the app's process,
// so a callback can reach PlayerManager and, through it, PlaybackService. The
// controller may not be connected yet (the process can be started by the widget
// alone), which is why every callback goes through PlayerManager.awaitController.

object WidgetKeys {
  val episodeId = ActionParameters.Key<String>("episodeId")
  val showId = ActionParameters.Key<String>("showId")
  val isPodcast = ActionParameters.Key<Boolean>("isPodcast")
}

class TogglePlaybackAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    val controller = PlayerManager.awaitController(context)
    if (controller != null && PlayerManager.current.value != null) {
      PlayerManager.togglePlayPause()
    } else {
      // Nothing loaded: continue whatever was last played rather than doing
      // nothing, which is what the button looks like it promises.
      NerLanAppRecents.resumeMostRecent(context)
    }
    WidgetRefresher.refreshAll(context)
  }
}

class PlayEpisodeAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    parameters[WidgetKeys.episodeId]?.let { PlayerManager.playEpisode(context, it) }
    WidgetRefresher.refreshAll(context)
  }
}

/** Resume a whole show — the 最近播放 button. */
class PlayShowAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    val showId = parameters[WidgetKeys.showId] ?: return
    PlayerManager.playShow(context, showId, parameters[WidgetKeys.isPodcast] ?: false)
    WidgetRefresher.refreshAll(context)
  }
}

class NextEpisodeAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    PlayerManager.awaitController(context) ?: return
    PlayerManager.next()
    WidgetRefresher.refreshAll(context)
  }
}

class SkipBackAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    PlayerManager.awaitController(context) ?: return
    PlayerManager.skip(-15_000)
    WidgetRefresher.refreshAll(context)
  }
}

private object NerLanAppRecents {
  suspend fun resumeMostRecent(context: Context) {
    val recent = com.example.nerlan.NerLanApp.instance.recents.shows.value.firstOrNull() ?: return
    PlayerManager.playShow(context, recent.id, recent.isPodcast)
  }
}

// --- Taps that open the app -------------------------------------------------

fun openPlayerAction(): Action = openAction(NerLanLink.player())

fun openShowAction(showId: String, isPodcast: Boolean): Action =
  openAction(NerLanLink.show(showId, isPodcast))

fun openTabAction(tab: String): Action = openAction(NerLanLink.tab(tab))

fun playEpisodeAction(episodeId: String): Action =
  actionRunCallback<PlayEpisodeAction>(actionParametersOf(WidgetKeys.episodeId to episodeId))

fun playShowAction(showId: String, isPodcast: Boolean): Action =
  actionRunCallback<PlayShowAction>(
    actionParametersOf(WidgetKeys.showId to showId, WidgetKeys.isPodcast to isPodcast)
  )

private fun openAction(uri: Uri): Action = actionStartActivity(
  Intent(Intent.ACTION_VIEW, uri).setClass(
    com.example.nerlan.NerLanApp.instance, MainActivity::class.java
  ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
)

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
