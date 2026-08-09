package com.example.nerlan.widget

import android.content.Context
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/**
 * 學習紀錄 — today's listening, the current streak, and the week's total. The one
 * widget with no Apple Podcasts counterpart, but the app already tracks all of it
 * for the 使用統計 screen and a visible streak is what a language learner most
 * wants glanceable.
 */
class StatsWidget : GlanceAppWidget() {
  // Exact, not Responsive: the number and goal bar scale with the real cell
  // size, and the block centers vertically, so a big cell reads as a big
  // stat instead of a small one floating over dead space.
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val size = LocalSize.current
          val innerHeight = size.height.value - 24f
          val stats = model.stats
          if (innerHeight < 100f) {
            // 2x1: the essentials on three tight lines — today's minutes and the
            // streak; the week total joins the streak line when the width allows.
            Column(
              modifier = GlanceModifier.fillMaxSize().clickable(openTabAction("programs")),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              WidgetCaption("今日學習")
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = "${stats.minutesToday}",
                  style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                  ),
                )
                RowSpacer(4)
                WidgetCaption("分鐘")
              }
              WidgetCaption(
                if (size.width.value >= 200f) {
                  "連續 ${stats.streakDays} 天 · 本週 ${formatMinutes(stats.minutesThisWeek)}"
                } else {
                  "連續 ${stats.streakDays} 天"
                },
              )
            }
          } else {
            val numberSp = when {
              innerHeight >= 170f -> 56
              innerHeight >= 120f -> 42
              else -> 30
            }
            Column(
              modifier = GlanceModifier.fillMaxSize().clickable(openTabAction("programs")),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              WidgetCaption("今日學習")
              Text(
                text = "${stats.minutesToday}",
                style = TextStyle(
                  color = GlanceTheme.colors.onSurface,
                  fontSize = numberSp.sp,
                  fontWeight = FontWeight.Bold,
                ),
              )
              WidgetCaption("分鐘")
              ColSpacer(8)
              Column(modifier = GlanceModifier.fillMaxWidth()) {
                WidgetCaption("連續 ${stats.streakDays} 天")
                WidgetCaption("本週 ${formatMinutes(stats.minutesThisWeek)}")
              }
            }
          }
        }
      }
    }
  }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}
