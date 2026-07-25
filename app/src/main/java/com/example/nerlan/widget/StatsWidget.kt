package com.example.nerlan.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import androidx.glance.layout.Column
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
  override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val model = WidgetModelBuilder.build(context)

    provideContent {
      GlanceTheme {
        WidgetSurface {
          val wide = LocalSize.current.width >= WIDE.width
          val stats = model.stats
          Column(
            modifier = GlanceModifier.fillMaxSize().clickable(openTabAction("programs"))
          ) {
            WidgetCaption("今日學習")
            Text(
              text = "${stats.minutesToday}",
              style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = if (wide) 34.sp else 30.sp,
                fontWeight = FontWeight.Bold,
              ),
            )
            WidgetCaption("分鐘")
            ColSpacer(8)
            // A progress hint against a 30-minute day, not a promise: nothing in
            // the app lets the goal be configured.
            WidgetProgressBar(
              (stats.minutesToday / 30f).coerceIn(0f, 1f),
              widthDp = if (wide) 180 else 110,
            )
            ColSpacer(6)
            Column(modifier = GlanceModifier.fillMaxWidth()) {
              WidgetCaption("連續 ${stats.streakDays} 天")
              WidgetCaption("本週 ${formatMinutes(stats.minutesThisWeek)}")
            }
          }
        }
      }
    }
  }

  private companion object {
    val SMALL = DpSize(110.dp, 110.dp)
    val WIDE = DpSize(220.dp, 110.dp)
  }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = StatsWidget()
}
