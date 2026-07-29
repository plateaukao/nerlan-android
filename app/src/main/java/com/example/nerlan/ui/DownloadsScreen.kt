package com.example.nerlan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.episodeOrder

/** Which offline copies the Downloads list shows: both kinds, explicit
 *  downloads only, or streamed-cache captures only. */
private val filterLabels = listOf("全部", "已下載", "快取")

/**
 * Offline episodes — explicit downloads plus streamed-cache captures (shown
 * with a dimmed outline check) — groupable by program or by language and
 * filterable by kind.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen() {
  val downloads = NerLanApp.instance.downloads
  val records by downloads.records.collectAsState()
  val cachedRecords by downloads.cachedRecords.collectAsState()
  var grouping by remember { mutableIntStateOf(0) } // 0 = 節目, 1 = 語言
  val groupingLabels = listOf("節目", "語言")
  var filter by remember { mutableIntStateOf(0) } // indexes filterLabels

  if (records.isEmpty() && cachedRecords.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("沒有下載的單集\n在節目頁面點選下載按鈕，即可離線收聽。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  // Ids that are cache captures rather than explicit downloads. cachedRecords
  // excludes downloaded ids by construction; the subtraction is a guard
  // against a double row.
  val downloadedIds = records.mapTo(HashSet()) { it.id }
  val visible = when (filter) {
    1 -> records
    2 -> cachedRecords
    else -> records + cachedRecords.filter { it.id !in downloadedIds }
  }

  val grouped = visible
    .groupBy { if (grouping == 0) it.programName else it.language.ifEmpty { "其他" } }
    .mapValues { (_, list) -> list.sortedWith(episodeOrder) }
    .toSortedMap()

  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      ) {
        Text(
          "下載",
          style = MaterialTheme.typography.headlineMediumEmphasized,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f),
        )
        DownloadFilterMenu(filter, onSelect = { filter = it })
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
    if (visible.isEmpty()) {
      // Everything is hidden by the current filter; keep the header so it can
      // be switched back.
      item {
        Text(
          if (filter == 2) "沒有快取的單集\n開啟串流快取後，聽完的單集會保留在這裡。"
          else "沒有下載的單集\n在節目頁面點選下載按鈕，即可離線收聽。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
      }
    }
    grouped.forEach { (groupName, list) ->
      item(key = "dh-$groupName") { SectionHeader(groupName) }
      items(list.size, key = { "de-${list[it].id}" }) { i ->
        RecordRow(
          list[i], queue = list,
          onDelete = { downloads.delete(list[i].id) },
          downloadBadge = if (list[i].id in downloadedIds) DownloadBadge.DOWNLOADED
                          else DownloadBadge.CACHED,
        )
      }
    }
  }
}

/** Compact filter menu beside the grouping toggle. The icon tints to primary
 *  when a filter narrows the list. */
@Composable
private fun DownloadFilterMenu(selected: Int, onSelect: (Int) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }) {
      Icon(
        Icons.Filled.FilterList,
        contentDescription = "篩選",
        tint = if (selected == 0) MaterialTheme.colorScheme.onSurfaceVariant
               else MaterialTheme.colorScheme.primary,
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      filterLabels.forEachIndexed { index, label ->
        DropdownMenuItem(
          text = { Text(label) },
          trailingIcon = {
            if (index == selected) Icon(Icons.Filled.Check, contentDescription = null)
          },
          onClick = {
            onSelect(index)
            open = false
          },
        )
      }
    }
  }
}
