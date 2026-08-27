package com.example.nerlan.widget

import android.content.Context
import android.graphics.Bitmap
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.nerlan.R

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
object ShowsWidget {
  /** Grid metrics; the frame applies PADDING on every side. MIN_CELL is the
   *  smallest cell worth splitting into — it decides how many rows/columns a
   *  given widget size gets, not how big the covers end up. */
  private const val PADDING = 12f
  private const val MIN_CELL = 64f
  private const val GAP = 10f
  private const val NAME_H = 16f
  private const val HEADER_H = 24f

  fun render(context: Context, id: Int, size: SizeF, model: WidgetModel, covers: Map<String, Bitmap>): RemoteViews {
    // Pinned selection, in the order it was picked; empty means automatic.
    val picked = ShowsWidgetPrefs.picked(context, id)
    val byId = (model.shows + model.recents).associateBy { it.id }
    val shows = if (picked.isEmpty()) model.shows else picked.mapNotNull { byId[it] }

    // The grid fills the widget: counts come from the real size, then the cover
    // is sized to the resulting cell. Sizing covers to a fixed 56dp and stacking
    // however many fit left nearly half the widget empty at anything but the
    // smallest size.
    val usableW = size.width - PADDING * 2
    val showNames = size.width >= 220f
    val gridH = size.height - PADDING * 2 - (if (showNames) HEADER_H else 0f)
    val columns = ((usableW + GAP) / (MIN_CELL + GAP)).toInt().coerceIn(2, 4)
    val rows = ((gridH + GAP) / (MIN_CELL + GAP)).toInt().coerceIn(1, 4)
    val cellW = (usableW - GAP * (columns - 1)) / columns
    val cellH = (gridH - GAP * (rows - 1)) / rows
    // Keep covers square: the smaller of the two cell dimensions wins.
    val coverDp = minOf(cellW, cellH - (if (showNames) NAME_H else 0f)).toInt().coerceAtLeast(40)
    val visible = shows.take(columns * rows)

    val content = if (visible.isEmpty()) {
      WidgetViews.empty(context, "點選節目旁的愛心\n就會出現在這裡", WidgetIntents.openTab(context, "programs"))
    } else {
      RemoteViews(context.packageName, R.layout.widget_shows_grid).apply {
        setViewVisibility(R.id.list_header, if (showNames) View.VISIBLE else View.GONE)
        removeAllViews(R.id.rows_container)
        for (rowShows in visible.chunked(columns)) {
          val row = RemoteViews(context.packageName, R.layout.widget_shows_row)
          for (show in rowShows) {
            row.addView(
              R.id.row_root,
              RemoteViews(context.packageName, R.layout.widget_shows_cell).apply {
                val open = WidgetIntents.openShow(context, show.id, show.isPodcast)
                setImageViewBitmap(
                  R.id.cell_cover, WidgetViews.cover(context, covers.cover(show.coverUrl), coverDp, cornerDp = 12))
                setOnClickPendingIntent(R.id.cell_cover, open)
                setViewVisibility(R.id.cell_name, if (showNames) View.VISIBLE else View.GONE)
                setTextViewText(R.id.cell_name, show.name)
                setOnClickPendingIntent(R.id.cell_name, open)
              },
            )
          }
          // Keep a short final row left-aligned instead of stretched.
          repeat(columns - rowShows.size) {
            row.addView(R.id.row_root, RemoteViews(context.packageName, R.layout.widget_shows_spacer))
          }
          addView(R.id.rows_container, row)
        }
      }
    }
    return WidgetViews.frame(context, content)
  }
}

/** Per-instance pinned selection of the 我的節目 widget. Stored as one joined
 *  string rather than a string set — a set has no order, and here the order *is*
 *  the layout. */
object ShowsWidgetPrefs {
  private fun prefs(context: Context) = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
  private fun key(appWidgetId: Int) = "picked_shows_$appWidgetId"

  fun picked(context: Context, appWidgetId: Int): List<String> =
    prefs(context).getString(key(appWidgetId), null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

  fun save(context: Context, appWidgetId: Int, ids: List<String>) {
    prefs(context).edit().putString(key(appWidgetId), ids.joinToString("\n")).apply()
  }

  fun clear(context: Context, appWidgetId: Int) {
    prefs(context).edit().remove(key(appWidgetId)).apply()
  }
}
