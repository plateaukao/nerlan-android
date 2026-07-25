package com.example.nerlan.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.example.nerlan.NerLanApp
import com.example.nerlan.player.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps the home-screen widgets in step with the app.
 *
 * Everything that can change what a widget shows is a StateFlow already, so this
 * merges them and redraws on a short debounce — a queue swap or a Drive pull
 * emits several changes at once and only the settled result is worth drawing.
 *
 * The position flow is deliberately *not* observed: it ticks twice a second
 * during playback, and pushing a RemoteViews update at that rate would be
 * wasteful. Widgets instead show a progress bar that is accurate as of the last
 * real change (play/pause/track), which is what an app widget can honestly
 * promise without a foreground update loop.
 */
object WidgetRefresher {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  fun start(app: NerLanApp) {
    scope.launch {
      merge(
        PlayerManager.current.map { },
        PlayerManager.isPlaying.map { },
        app.favorites.episodes.map { },
        app.favorites.programs.map { },
        app.podcasts.feeds.map { },
        app.downloads.records.map { },
        app.recents.shows.map { },
      ).debounce(1_000).collect { refreshAll(app) }
    }
  }

  suspend fun refreshAll(context: Context) {
    runCatching {
      UpNextWidget().updateAll(context)
      RecentShowsWidget().updateAll(context)
      ShowsWidget().updateAll(context)
      StatsWidget().updateAll(context)
    }
  }
}
