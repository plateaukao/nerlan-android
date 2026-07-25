package com.example.nerlan.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import com.example.nerlan.R

/**
 * 最近播放 — the shows you've actually been listening to, each with a button that
 * resumes the course where you left it: the remembered episode, at the remembered
 * offset, with the rest of the show queued behind it.
 *
 * Distinct from 我的節目, which lists what you *bookmarked*. This lists what
 * you've been *doing* — for a sequential language course, the thing worth one tap
 * from the home screen.
 */
class RecentShowsWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, TALL))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)
    val covers = loadCovers(context, model.recents.take(5).map { it.coverUrl }, 128)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val size = LocalSize.current
          val rows = when {
            size.height >= TALL.height -> 5
            size.width >= WIDE.width -> 2
            else -> 1
          }
          val shows = model.recents.take(rows)
          when {
            shows.isEmpty() ->
              WidgetEmptyState("播放過的節目\n會出現在這裡", openTabAction("programs"))

            rows == 1 -> {
              val show = shows.first()
              Column(modifier = GlanceModifier.fillMaxWidth()) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                  WidgetCover(
                    covers.cover(show.coverUrl), sizeDp = 48,
                    onClick = openShowAction(show.id, show.isPodcast),
                  )
                  RowSpacer(8)
                  WidgetIconButton(
                    R.drawable.ic_widget_play,
                    playShowAction(show.id, show.isPodcast),
                  )
                }
                ColSpacer(8)
                Column(modifier = GlanceModifier.clickable(openShowAction(show.id, show.isPodcast))) {
                  WidgetCaption(show.name)
                  WidgetTitle(show.lastEpisodeTitle ?: "")
                }
                show.resumeProgress?.let {
                  ColSpacer(6)
                  WidgetProgressBar(it, widthDp = 120, heightDp = 4)
                }
              }
            }

            else -> Column(modifier = GlanceModifier.fillMaxWidth()) {
              WidgetHeader("最近播放")
              shows.forEach { show ->
                WidgetListRow(
                  bitmap = covers.cover(show.coverUrl),
                  title = show.lastEpisodeTitle ?: "",
                  caption = show.name,
                  progress = show.resumeProgress,
                  openAction = openShowAction(show.id, show.isPodcast),
                  buttonRes = R.drawable.ic_widget_play,
                  buttonAction = playShowAction(show.id, show.isPodcast),
                )
              }
            }
          }
        }
      }
    }
  }

  private companion object {
    val SMALL = DpSize(140.dp, 120.dp)
    val WIDE = DpSize(250.dp, 120.dp)
    val TALL = DpSize(250.dp, 250.dp)
  }
}

class RecentShowsWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = RecentShowsWidget()
}
