package com.example.nerlan.widget

import android.content.Context
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import com.example.nerlan.R

/**
 * 學習紀錄 — today's listening, the current streak, and the week's total. The one
 * widget with no Apple Podcasts counterpart, but the app already tracks all of it
 * for the 使用統計 screen and a visible streak is what a language learner most
 * wants glanceable.
 *
 * The number scales with the real cell size and the block centers vertically, so
 * a big cell reads as a big stat instead of a small one floating over dead space.
 */
object StatsWidget {
  fun render(context: Context, size: SizeF, model: WidgetModel): RemoteViews {
    val innerHeight = size.height - 24f
    val stats = model.stats
    val open = WidgetIntents.openTab(context, "programs")
    val content = if (innerHeight < 100f) {
      // 2x1: the essentials on three tight lines — today's minutes and the
      // streak; the week total joins the streak line when the width allows.
      RemoteViews(context.packageName, R.layout.widget_stats_small).apply {
        setTextViewText(R.id.stats_number, "${stats.minutesToday}")
        setTextViewText(
          R.id.stats_line,
          if (size.width >= 200f) "連續 ${stats.streakDays} 天 · 本週 ${formatMinutes(stats.minutesThisWeek)}"
          else "連續 ${stats.streakDays} 天",
        )
        setOnClickPendingIntent(R.id.stats_root, open)
      }
    } else {
      val numberSp = when {
        innerHeight >= 170f -> 56f
        innerHeight >= 120f -> 42f
        else -> 30f
      }
      RemoteViews(context.packageName, R.layout.widget_stats_large).apply {
        setTextViewText(R.id.stats_number, "${stats.minutesToday}")
        setTextViewTextSize(R.id.stats_number, TypedValue.COMPLEX_UNIT_SP, numberSp)
        setTextViewText(R.id.stats_streak, "連續 ${stats.streakDays} 天")
        setTextViewText(R.id.stats_week, "本週 ${formatMinutes(stats.minutesThisWeek)}")
        setOnClickPendingIntent(R.id.stats_root, open)
      }
    }
    return WidgetViews.frame(context, content)
  }
}
