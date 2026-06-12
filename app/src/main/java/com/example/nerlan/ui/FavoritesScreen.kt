package com.example.nerlan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.Program
import com.example.nerlan.player.PlayerManager

/** Favorited programs and episodes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavoritesScreen(onProgramClick: (Program) -> Unit) {
  val favorites = NerLanApp.instance.favorites
  val programs by favorites.programs.collectAsState()
  val episodes by favorites.episodes.collectAsState()

  if (programs.isEmpty() && episodes.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("沒有收藏\n點選節目或單集旁的愛心即可加入收藏。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  val grouped = episodes.groupBy { it.programName }
    .mapValues { (_, records) -> records.sortedBy { it.playDate ?: "" } }
    .toSortedMap()

  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Text(
        "收藏",
        style = MaterialTheme.typography.headlineMediumEmphasized,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
      )
    }
    if (programs.isNotEmpty()) {
      item {
        SectionHeader("節目")
      }
      items(programs.size, key = { "fp-${programs[it].programId}" }) { i ->
        ProgramRow(programs[i], onClick = { onProgramClick(programs[i]) })
      }
    }
    grouped.forEach { (programName, records) ->
      item(key = "fh-$programName") { SectionHeader(programName) }
      items(records.size, key = { "fe-${records[it].id}" }) { i ->
        RecordRow(records[i], queue = records, onDelete = { favorites.toggle(records[i]) })
      }
    }
  }
}

@Composable
fun SectionHeader(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

/** Shared row for favorites & downloads lists: tap to play, trash to remove. */
@Composable
fun RecordRow(record: EpisodeRecord, queue: List<EpisodeRecord>, onDelete: () -> Unit) {
  val current by PlayerManager.current.collectAsState()
  val isPlaying by PlayerManager.isPlaying.collectAsState()
  val isCurrent = current?.id == record.id

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable {
        if (isCurrent) PlayerManager.togglePlayPause() else PlayerManager.play(record, queue)
      }
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    CoverImage(record.coverUrl, 44.dp)
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        record.title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        "${record.programName} · ${record.language}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Icon(
      if (isCurrent && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
    IconButton(onClick = onDelete) {
      Icon(Icons.Filled.Delete, contentDescription = "移除",
        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
  }
}
