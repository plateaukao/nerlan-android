package com.example.nerlan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp

/** Offline episodes, groupable by program or by language. */
@Composable
fun DownloadsScreen() {
  val downloads = NerLanApp.instance.downloads
  val records by downloads.records.collectAsState()
  var grouping by remember { mutableIntStateOf(0) } // 0 = 節目, 1 = 語言
  val groupingLabels = listOf("節目", "語言")

  if (records.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("沒有下載的單集\n在節目頁面點選下載按鈕，即可離線收聽。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  val grouped = records
    .groupBy { if (grouping == 0) it.programName else it.language.ifEmpty { "其他" } }
    .mapValues { (_, list) -> list.sortedBy { it.playDate ?: "" } }
    .toSortedMap()

  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Text(
          "下載",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
          groupingLabels.forEachIndexed { index, label ->
            SegmentedButton(
              selected = grouping == index,
              onClick = { grouping = index },
              shape = SegmentedButtonDefaults.itemShape(index, groupingLabels.size),
            ) {
              Text(label)
            }
          }
        }
      }
    }
    grouped.forEach { (groupName, list) ->
      item(key = "dh-$groupName") { SectionHeader(groupName) }
      items(list.size, key = { "de-${list[it].id}" }) { i ->
        RecordRow(list[i], queue = list, onDelete = { downloads.delete(list[i].id) })
      }
    }
  }
}
