package com.example.nerlan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp

/**
 * Every episode that has a generated transcript or AI handout, grouped by
 * program or language. Rows reuse [RecordRow] in ready-only mode so existing
 * content opens without an API key. Mirrors the iOS AI tab.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiTabScreen() {
  val ai = NerLanApp.instance.ai
  val records by ai.records.collectAsState()
  val revision by ai.revision.collectAsState()
  val aiRecords = remember(records, revision) { ai.recordsWithContent() }

  var grouping by remember { mutableIntStateOf(0) } // 0 = 節目, 1 = 語言
  val groupingLabels = listOf("節目", "語言")

  if (aiRecords.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "沒有 AI 內容\n在播放器或單集列表點選逐字稿或 AI 講義圖示來產生內容。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    return
  }

  val grouped = aiRecords
    .groupBy { if (grouping == 0) it.programName.ifEmpty { "其他" } else it.language.ifEmpty { "其他" } }
    .mapValues { (_, list) -> list.sortedBy { it.playDate ?: "" } }
    .toSortedMap()

  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Text(
          "AI",
          style = MaterialTheme.typography.headlineMediumEmphasized,
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
      item(key = "aih-$groupName") { SectionHeader(groupName) }
      items(list.size, key = { "aie-${list[it].id}" }) { i ->
        RecordRow(list[i], queue = list, aiReadyOnly = true)
      }
    }
  }
}
