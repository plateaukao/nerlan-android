package com.example.nerlan.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.SizeF
import android.widget.RemoteViews
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
object RecentShowsWidget {
  const val MAX_ROWS = 6
  private const val HEADER_DP = 26f
  private const val ROW_DP = 56f

  fun render(context: Context, size: SizeF, model: WidgetModel, covers: Map<String, Bitmap>): RemoteViews {
    // Inner space after the frame's 12dp padding; every row the height actually
    // fits gets used (a list row runs ~56dp).
    val innerHeight = size.height - 24f
    val rows =
      if (innerHeight < 150f) 1
      else ((innerHeight - HEADER_DP) / ROW_DP).toInt().coerceIn(1, MAX_ROWS)
    val shows = model.recents.take(rows)
    val content = when {
      shows.isEmpty() ->
        WidgetViews.empty(context, "播放過的節目\n會出現在這裡", WidgetIntents.openTab(context, "programs"))
      rows == 1 -> single(context, shows.first(), covers)
      else -> list(context, shows, covers)
    }
    return WidgetViews.frame(context, content)
  }

  private fun single(context: Context, show: WidgetShow, covers: Map<String, Bitmap>) =
    RemoteViews(context.packageName, R.layout.widget_hero_small).apply {
      val open = WidgetIntents.openShow(context, show.id, show.isPodcast)
      setImageViewBitmap(R.id.hero_cover, WidgetViews.cover(context, covers.cover(show.coverUrl), 48))
      setOnClickPendingIntent(R.id.hero_cover, open)
      setImageViewResource(R.id.play_button, R.drawable.ic_widget_play)
      setOnClickPendingIntent(R.id.play_button, WidgetIntents.playShow(context, show.id, show.isPodcast))
      setTextViewText(R.id.hero_caption, show.name)
      setTextViewText(R.id.hero_title, show.lastEpisodeTitle ?: "")
      setOnClickPendingIntent(R.id.hero_text, open)
      WidgetViews.progress(this, R.id.hero_progress, show.resumeProgress)
    }

  private fun list(context: Context, shows: List<WidgetShow>, covers: Map<String, Bitmap>) =
    RemoteViews(context.packageName, R.layout.widget_list).apply {
      setTextViewText(R.id.list_header, "最近播放")
      removeAllViews(R.id.rows_container)
      for (show in shows) {
        addView(
          R.id.rows_container,
          WidgetViews.listRow(
            context,
            bitmap = covers.cover(show.coverUrl),
            title = show.lastEpisodeTitle ?: "",
            caption = show.name,
            progress = show.resumeProgress,
            open = WidgetIntents.openShow(context, show.id, show.isPodcast),
            buttonRes = R.drawable.ic_widget_play,
            buttonClick = WidgetIntents.playShow(context, show.id, show.isPodcast),
          ),
        )
      }
    }
}
