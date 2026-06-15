package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.ListeningStatsStore
import java.util.Calendar

/**
 * 使用統計 — listening behavior over time: accumulated time, completed episodes,
 * streak, today/week/month subtotals, a 日/週/月 bar chart, and the most-listened
 * programs. All numbers come from [ListeningStatsStore] (merged across devices).
 * Mirrors the iOS UsageStatsView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatsScreen(onDismiss: () -> Unit) {
  val stats = NerLanApp.instance.stats
  val revision by stats.revision.collectAsState()
  val ui = remember(revision) { stats.uiStats() }
  var range by remember { mutableStateOf(Range.DAY) }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
          IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
          Text(
            "使用統計",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }

        if (!ui.hasData) {
          Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
              "開始聆聽後，這裡會顯示你的使用統計。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          ) {
            SectionTitle("總覽", top = 8.dp)
            StatRow("總聆聽時間", durationText(ui.totalSeconds))
            StatRow("完成單集", "${ui.completedCount}")
            StatRow("連續聆聽天數", if (ui.streak > 0) "🔥 ${ui.streak} 天" else "—")
            StatRow("今日", durationText(ui.today))
            StatRow("本週", durationText(ui.week))
            StatRow("本月", durationText(ui.month))

            SectionTitle("聆聽趨勢")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
              Range.entries.forEachIndexed { i, r ->
                SegmentedButton(
                  selected = range == r,
                  onClick = { range = r },
                  shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size),
                ) { Text(r.label) }
              }
            }
            Spacer(Modifier.height(12.dp))
            when (range) {
              Range.DAY -> BarChart(
                values = ui.hourly.map { it.seconds },
                labels = ui.hourly.map { if (it.hour % 6 == 0) "${it.hour}" else null },
              )
              Range.WEEK -> BarChart(
                values = ui.last7.map { it.seconds },
                labels = ui.last7.map { weekdayLabel(it.timeMillis) },
              )
              Range.MONTH -> BarChart(
                values = ui.last30.map { it.seconds },
                labels = ui.last30.map { val d = dayOfMonth(it.timeMillis); if (d == 1 || d % 5 == 0) "$d" else null },
              )
            }

            if (ui.topPrograms.isNotEmpty()) {
              SectionTitle("最常聽節目")
              ui.topPrograms.forEach { StatRow(it.name, durationText(it.seconds)) }
            }
            Spacer(Modifier.height(24.dp))
          }
        }
      }
    }
  }
}

private enum class Range(val label: String) { DAY("日"), WEEK("週"), MONTH("月") }

/** Section header matching the SettingsScreen style. */
@Composable
private fun SectionTitle(text: String, top: androidx.compose.ui.unit.Dp = 16.dp) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = top, bottom = 4.dp),
  )
}

/** A "label … value" row, the Android counterpart of iOS LabeledContent. */
@Composable
fun StatRow(label: String, value: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
  ) {
    Text(label, modifier = Modifier.weight(1f))
    Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Minimal Compose bar chart (no external charting dependency). Bars grow from
 *  the bottom, scaled to the largest value; [labels] entries that are null leave
 *  the axis slot blank so dense axes don't overflow. */
@Composable
private fun BarChart(values: List<Double>, labels: List<String?>) {
  val maxV = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
  val color = MaterialTheme.colorScheme.primary
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      values.forEach { v ->
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
          Box(
            Modifier
              .fillMaxWidth()
              .fillMaxHeight((v / maxV).toFloat().coerceIn(0f, 1f))
              .background(color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
          )
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      labels.forEach { lbl ->
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
          if (lbl != null) {
            Text(
              lbl,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}

private fun weekdayLabel(ms: Long): String {
  val dow = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)
  return listOf("日", "一", "二", "三", "四", "五", "六")[dow - 1]
}

private fun dayOfMonth(ms: Long): Int =
  Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.DAY_OF_MONTH)

fun durationText(seconds: Double): String {
  val total = seconds.toInt()
  val h = total / 3600
  val m = (total % 3600) / 60
  return when {
    h > 0 -> "$h 小時 $m 分"
    m > 0 -> "$m 分"
    total > 0 -> "$total 秒"
    else -> "0 分"
  }
}
