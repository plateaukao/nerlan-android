package com.example.nerlan.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import com.example.nerlan.R

/**
 * 繼續收聽 — whatever is loaded in the player, plus the rest of the queue. With
 * nothing loaded it offers the most recent download or favorite, so the widget is
 * useful from the first launch.
 */
class UpNextWidget : GlanceAppWidget() {
  // Exact, not Responsive: the old 250dp "tall" bucket meant a 2-cell-high
  // widget (~224dp) never got the 接下來 list even though one row fits, so its
  // bottom half sat empty. Row count now comes from the real height.
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)
    val heroCover = loadCoverBitmap(context, model.lead?.coverUrl, 160)
    val rowCovers = loadCovers(context, model.upNext.take(MAX_NEXT).map { it.coverUrl }, 96)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val size = LocalSize.current
          val lead = model.lead
          // Inner space after WidgetSurface's 12dp padding; how many queue rows
          // fit under the hero block decides the layout.
          val innerHeight = size.height.value - 24f
          val nextRows = ((innerHeight - HERO_BLOCK_DP - HEADER_DP) / ROW_DP)
            .toInt().coerceIn(0, MAX_NEXT)
          val rest =
            if (lead == null) emptyList()
            else model.upNext.dropWhile { it.id == lead.id }.take(nextRows)
          when {
            lead == null ->
              WidgetEmptyState("還沒有可以播放的單集\n先下載或收藏一集吧", openTabAction("programs"))
            rest.isNotEmpty() -> Tall(model, heroCover, rowCovers, rest)
            size.width >= 250.dp -> Wide(model, heroCover)
            else -> Small(model, heroCover)
          }
        }
      }
    }
  }

  @Composable
  private fun Small(model: WidgetModel, hero: android.graphics.Bitmap?) {
    val lead = model.lead ?: return
    Column(
      modifier = GlanceModifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        WidgetCover(hero, sizeDp = 48, onClick = openPlayerAction())
        RowSpacer(8)
        PlayPauseButton(model)
      }
      ColSpacer(8)
      Column(modifier = GlanceModifier.clickable(openPlayerAction())) {
        WidgetCaption(lead.programName)
        WidgetTitle(lead.title)
      }
      if (model.nowPlaying != null) {
        ColSpacer(6)
        WidgetProgressBar(model.progress, widthDp = 120, heightDp = 4)
      }
    }
  }

  @Composable
  private fun Wide(model: WidgetModel, hero: android.graphics.Bitmap?) {
    val lead = model.lead ?: return
    Row(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
      WidgetCover(hero, sizeDp = 72, onClick = openPlayerAction())
      RowSpacer(12)
      Column(modifier = GlanceModifier.defaultWeight()) {
        Column(modifier = GlanceModifier.clickable(openPlayerAction())) {
          WidgetCaption(lead.programName)
          WidgetTitle(lead.title)
        }
        if (model.nowPlaying != null) {
          ColSpacer(6)
          WidgetProgressBar(model.progress, widthDp = 150, heightDp = 4)
          formatRemaining(model.positionMs, model.durationMs)?.let {
            ColSpacer(3)
            WidgetCaption(it)
          }
        }
        ColSpacer(8)
        Transport(model)
      }
    }
  }

  @Composable
  private fun Tall(
    model: WidgetModel,
    hero: android.graphics.Bitmap?,
    rowCovers: Map<String, android.graphics.Bitmap>,
    // Pre-sliced to what the measured height fits; on the "nothing loaded"
    // path the lead came out of upNext itself, hence the dropWhile upstream.
    rest: List<WidgetEpisode>,
  ) {
    val lead = model.lead ?: return
    Column(
      modifier = GlanceModifier.fillMaxSize(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(hero, sizeDp = 56, onClick = openPlayerAction())
        RowSpacer(12)
        Column(modifier = GlanceModifier.defaultWeight().clickable(openPlayerAction())) {
          WidgetCaption(lead.programName)
          WidgetTitle(lead.title)
          if (model.nowPlaying != null) {
            ColSpacer(5)
            WidgetProgressBar(model.progress, widthDp = 140, heightDp = 4)
          }
        }
      }
      ColSpacer(6)
      Transport(model)
      ColSpacer(8)
      WidgetHeader("接下來")
      rest.forEach { episode ->
        WidgetListRow(
          bitmap = rowCovers.cover(episode.coverUrl),
          title = episode.title,
          caption = episode.programName,
          progress = null,
          openAction = playEpisodeAction(episode.id),
          buttonRes = R.drawable.ic_widget_play,
          buttonAction = playEpisodeAction(episode.id),
        )
      }
    }
  }

  @Composable
  private fun PlayPauseButton(model: WidgetModel, sizeDp: Int = 36) {
    val playing = model.nowPlaying != null && model.isPlaying
    // Targeting the episode explicitly matters when the widget is offering a
    // suggestion the player hasn't loaded.
    val action = if (model.nowPlaying == null && model.lead != null) {
      playEpisodeAction(model.lead!!.id)
    } else {
      actionRunCallback<TogglePlaybackAction>()
    }
    WidgetIconButton(
      resId = if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
      onClick = action,
      sizeDp = sizeDp,
    )
  }

  @Composable
  private fun Transport(model: WidgetModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      WidgetIconButton(
        R.drawable.ic_widget_back15,
        actionRunCallback<SkipBackAction>(),
        sizeDp = 32,
        filled = false,
      )
      RowSpacer(10)
      PlayPauseButton(model, sizeDp = 40)
      RowSpacer(10)
      WidgetIconButton(
        R.drawable.ic_widget_next,
        actionRunCallback<NextEpisodeAction>(),
        sizeDp = 32,
        filled = false,
      )
    }
  }

  private companion object {
    const val MAX_NEXT = 3

    /** Hero row (56dp cover) + spacers + transport row, in dp. */
    const val HERO_BLOCK_DP = 110f
    const val HEADER_DP = 26f
    const val ROW_DP = 56f
  }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}
