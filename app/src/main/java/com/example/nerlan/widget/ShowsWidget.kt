package com.example.nerlan.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth

/**
 * 我的節目 — favorited programs and subscribed podcasts, each cover tapping
 * straight through to that show's episode list.
 *
 * More shows usually exist than fit, so there are two answers to "which ones":
 * by default the app orders them recently-played first then by listening time
 * (see [WidgetModelBuilder]), and the widget's configuration screen lets you pin
 * an explicit set in an explicit order. Several copies can be placed, each
 * pinned to different shows.
 */
class ShowsWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)

    // Pinned selection, in the order it was picked. Stored as one joined string
    // rather than a string set — a set has no order, and here the order *is* the
    // layout.
    val picked = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[PICKED_SHOWS]
      ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    val byId = (model.shows + model.recents).associateBy { it.id }
    val shows = if (picked.isEmpty()) model.shows else picked.mapNotNull { byId[it] }

    val covers = loadCovers(context, shows.take(8).map { it.coverUrl }, 128)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val size = LocalSize.current
          val columns = if (size.width >= WIDE.width) 4 else 2
          val rows = if (size.height >= TALL.height) 2 else 1
          val visible = shows.take(columns * rows)

          if (visible.isEmpty()) {
            WidgetEmptyState("點選節目旁的愛心\n就會出現在這裡", openTabAction("programs"))
          } else {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
              if (size.width >= WIDE.width) WidgetHeader("我的節目")
              visible.chunked(columns).forEach { rowShows ->
                Row(
                  modifier = GlanceModifier.fillMaxWidth(),
                  verticalAlignment = Alignment.Top,
                ) {
                  rowShows.forEach { show ->
                    Column(
                      modifier = GlanceModifier.defaultWeight(),
                      horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                      WidgetCover(
                        covers.cover(show.coverUrl),
                        sizeDp = 56,
                        corner = 10,
                        onClick = openShowAction(show.id, show.isPodcast),
                      )
                      if (size.width >= WIDE.width) {
                        ColSpacer(3)
                        WidgetCaption(show.name)
                      }
                    }
                  }
                  // Keep a short final row left-aligned instead of stretched.
                  repeat(columns - rowShows.size) {
                    Column(modifier = GlanceModifier.defaultWeight()) {}
                  }
                }
                ColSpacer(8)
              }
            }
          }
        }
      }
    }
  }

  companion object {
    /** Newline-joined show ids pinned for one widget instance. */
    val PICKED_SHOWS = stringPreferencesKey("pickedShows")

    val SMALL = DpSize(140.dp, 120.dp)
    val WIDE = DpSize(250.dp, 120.dp)
    val TALL = DpSize(250.dp, 220.dp)
  }
}

class ShowsWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = ShowsWidget()
}
