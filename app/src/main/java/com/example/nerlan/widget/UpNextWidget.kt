package com.example.nerlan.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.nerlan.R

/**
 * 繼續收聽 — whatever is loaded in the player, plus the rest of the queue. With
 * nothing loaded it offers the most recent download or favorite, so the widget is
 * useful from the first launch.
 *
 * Row count comes from the real height: a 2-cell-high widget (~224dp) gets one
 * 接下來 row rather than an empty bottom half.
 */
object UpNextWidget {
  const val MAX_NEXT = 3

  /** Hero row (56dp cover) + spacers + transport row, in dp. */
  private const val HERO_BLOCK_DP = 110f
  private const val HEADER_DP = 26f
  private const val ROW_DP = 56f

  fun render(context: Context, size: SizeF, model: WidgetModel, covers: Map<String, Bitmap>): RemoteViews {
    val lead = model.lead
    // Inner space after the frame's 12dp padding; how many queue rows fit under
    // the hero block decides the layout.
    val innerHeight = size.height - 24f
    val nextRows = ((innerHeight - HERO_BLOCK_DP - HEADER_DP) / ROW_DP).toInt().coerceIn(0, MAX_NEXT)
    val rest =
      if (lead == null) emptyList()
      else model.upNext.dropWhile { it.id == lead.id }.take(nextRows)
    val content = when {
      lead == null ->
        WidgetViews.empty(context, "還沒有可以播放的單集\n先下載或收藏一集吧", WidgetIntents.openTab(context, "programs"))
      rest.isNotEmpty() -> tall(context, model, lead, covers, rest)
      size.width >= 250f -> wide(context, model, lead, covers)
      else -> small(context, model, lead, covers)
    }
    return WidgetViews.frame(context, content)
  }

  private fun small(context: Context, model: WidgetModel, lead: WidgetEpisode, covers: Map<String, Bitmap>) =
    RemoteViews(context.packageName, R.layout.widget_hero_small).apply {
      hero(context, model, lead, covers, coverDp = 48)
      playPause(context, model, R.id.play_button)
      WidgetViews.progress(this, R.id.hero_progress, if (model.nowPlaying != null) model.progress else null)
    }

  private fun wide(context: Context, model: WidgetModel, lead: WidgetEpisode, covers: Map<String, Bitmap>) =
    RemoteViews(context.packageName, R.layout.widget_up_next_wide).apply {
      hero(context, model, lead, covers, coverDp = 72)
      if (model.nowPlaying != null) {
        WidgetViews.progress(this, R.id.hero_progress, model.progress)
        val remaining = formatRemaining(model.positionMs, model.durationMs)
        setViewVisibility(R.id.hero_remaining, if (remaining == null) View.GONE else View.VISIBLE)
        setTextViewText(R.id.hero_remaining, remaining.orEmpty())
      } else {
        setViewVisibility(R.id.hero_progress, View.GONE)
        setViewVisibility(R.id.hero_remaining, View.GONE)
      }
      transport(context, model)
    }

  private fun tall(
    context: Context, model: WidgetModel, lead: WidgetEpisode, covers: Map<String, Bitmap>,
    // Pre-sliced to what the measured height fits; on the "nothing loaded" path
    // the lead came out of upNext itself, hence the dropWhile upstream.
    rest: List<WidgetEpisode>,
  ) = RemoteViews(context.packageName, R.layout.widget_up_next_tall).apply {
    hero(context, model, lead, covers, coverDp = 56)
    WidgetViews.progress(this, R.id.hero_progress, if (model.nowPlaying != null) model.progress else null)
    transport(context, model)
    removeAllViews(R.id.rows_container)
    for (episode in rest) {
      addView(
        R.id.rows_container,
        WidgetViews.listRow(
          context,
          bitmap = covers.cover(episode.coverUrl),
          title = episode.title,
          caption = episode.programName,
          progress = null,
          open = WidgetIntents.playEpisode(context, episode.id),
          buttonRes = R.drawable.ic_widget_play,
          buttonClick = WidgetIntents.playEpisode(context, episode.id),
        ),
      )
    }
  }

  /** Cover, caption and title of the lead episode; all of them open the player. */
  private fun RemoteViews.hero(
    context: Context, model: WidgetModel, lead: WidgetEpisode, covers: Map<String, Bitmap>, coverDp: Int,
  ) {
    val open = WidgetIntents.openPlayer(context)
    setImageViewBitmap(R.id.hero_cover, WidgetViews.cover(context, covers.cover(lead.coverUrl), coverDp))
    setOnClickPendingIntent(R.id.hero_cover, open)
    setTextViewText(R.id.hero_caption, lead.programName)
    setTextViewText(R.id.hero_title, lead.title)
    setOnClickPendingIntent(R.id.hero_text, open)
  }

  private fun RemoteViews.transport(context: Context, model: WidgetModel) {
    setOnClickPendingIntent(R.id.back_button, WidgetIntents.skipBack(context))
    playPause(context, model, R.id.play_button)
    setOnClickPendingIntent(R.id.next_button, WidgetIntents.next(context))
  }

  private fun RemoteViews.playPause(context: Context, model: WidgetModel, viewId: Int) {
    val playing = model.nowPlaying != null && model.isPlaying
    // Targeting the episode explicitly matters when the widget is offering a
    // suggestion the player hasn't loaded.
    val lead = model.lead
    val action =
      if (model.nowPlaying == null && lead != null) WidgetIntents.playEpisode(context, lead.id)
      else WidgetIntents.toggle(context)
    setImageViewResource(viewId, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
    setOnClickPendingIntent(viewId, action)
  }
}
