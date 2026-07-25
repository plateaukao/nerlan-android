package com.example.nerlan.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import com.example.nerlan.R

/**
 * 繼續收聽 — whatever is loaded in the player, plus the rest of the queue. With
 * nothing loaded it offers the most recent download or favorite, so the widget is
 * useful from the first launch.
 */
class UpNextWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Responsive(
    setOf(SMALL, WIDE, TALL)
  )

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)
    val heroCover = loadCoverBitmap(context, model.lead?.coverUrl, 160)
    val rowCovers = loadCovers(context, model.upNext.take(3).map { it.coverUrl }, 96)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val size = LocalSize.current
          when {
            model.lead == null ->
              WidgetEmptyState("還沒有可以播放的單集\n先下載或收藏一集吧", openTabAction("programs"))
            size.height >= TALL.height -> Tall(model, heroCover, rowCovers)
            size.width >= WIDE.width -> Wide(model, heroCover)
            else -> Small(model, heroCover)
          }
        }
      }
    }
  }

  @Composable
  private fun Small(model: WidgetModel, hero: android.graphics.Bitmap?) {
    val lead = model.lead ?: return
    Column(modifier = GlanceModifier.fillMaxWidth()) {
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
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
  ) {
    val lead = model.lead ?: return
    Column(modifier = GlanceModifier.fillMaxWidth()) {
      Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WidgetCover(hero, sizeDp = 64, onClick = openPlayerAction())
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
      ColSpacer(8)
      Transport(model)
      ColSpacer(10)
      // On the "nothing loaded" path the lead came out of upNext itself.
      val rest = model.upNext.dropWhile { it.id == lead.id }.take(3)
      if (rest.isNotEmpty()) {
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
    val SMALL = DpSize(140.dp, 120.dp)
    val WIDE = DpSize(250.dp, 120.dp)
    val TALL = DpSize(250.dp, 250.dp)
  }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}
