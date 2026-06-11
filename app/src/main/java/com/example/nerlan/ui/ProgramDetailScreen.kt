package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.Episode
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.ChannelPlusApi
import com.example.nerlan.data.Program
import com.example.nerlan.player.PlayerManager

/** Program info plus its full episode archive (infinite scroll, oldest first). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailScreen(program: Program, onBack: () -> Unit) {
  val favorites = NerLanApp.instance.favorites
  var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
  var page by remember { mutableIntStateOf(0) }
  var totalPages by remember { mutableIntStateOf(1) }
  var totalCount by remember { mutableIntStateOf(0) }
  var isLoading by remember { mutableStateOf(false) }
  var showFullIntro by remember { mutableStateOf(false) }
  var loadTrigger by remember { mutableIntStateOf(0) }
  val favoritePrograms by favorites.programs.collectAsState()
  val isFavProgram = favoritePrograms.any { it.programId == program.programId }

  val listState = rememberLazyListState()
  val nearEnd by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last >= listState.layoutInfo.totalItemsCount - 3
    }
  }

  LaunchedEffect(loadTrigger, nearEnd) {
    if (!isLoading && (page == 0 || (nearEnd && page < totalPages))) {
      isLoading = true
      try {
        val result = ChannelPlusApi.episodes(program.programId, page + 1)
        val known = episodes.map { it.episodeId }.toSet()
        episodes = episodes + result.episodes.filterNot { it.episodeId in known }
        page += 1
        totalPages = result.totalPages
        totalCount = result.totalCount
      } catch (_: Exception) {
        // keep what we have; scrolling retriggers
      }
      isLoading = false
      loadTrigger += 1
    }
  }

  val records = remember(episodes) { episodes.map { EpisodeRecord.from(it, program) } }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(program.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = { favorites.toggle(program) }) {
            Icon(
              if (isFavProgram) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
              contentDescription = "收藏節目",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
      item {
        Column(Modifier.padding(16.dp)) {
          Row {
            CoverImage(program.coverUrl, 88.dp)
            Column(Modifier.padding(start = 12.dp)) {
              Text(program.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                  program.language,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                program.level?.let {
                  Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                  )
                }
              }
              program.episodeCount?.let {
                Text(
                  "共 $it 集",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
            }
          }
          val intro = program.descriptionText
          if (intro.isNotEmpty()) {
            Text(
              intro,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = if (showFullIntro) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier
                .padding(top = 10.dp)
                .clickable { showFullIntro = !showFullIntro },
            )
          }
          Text(
            if (totalCount > 0) "單集列表（共 $totalCount 集）" else "單集列表",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
          )
        }
      }
      items(episodes.size, key = { episodes[it].episodeId }) { i ->
        EpisodeRow(episode = episodes[i], record = records[i], queue = records)
      }
      if (isLoading) {
        item {
          Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
          }
        }
      }
    }
  }
}

/** One episode row with play / favorite / download actions. */
@Composable
fun EpisodeRow(episode: Episode, record: EpisodeRecord, queue: List<EpisodeRecord>) {
  val favorites = NerLanApp.instance.favorites
  val downloads = NerLanApp.instance.downloads
  val current by PlayerManager.current.collectAsState()
  val isPlaying by PlayerManager.isPlaying.collectAsState()
  val favEpisodes by favorites.episodes.collectAsState()
  val progressMap by downloads.progress.collectAsState()
  val downloadRecords by downloads.records.collectAsState()

  val isCurrent = current?.id == episode.episodeId
  val playable = episode.audioUrl != null
  val isFav = favEpisodes.any { it.id == episode.episodeId }
  val isDownloaded = downloadRecords.any { it.id == episode.episodeId } || downloads.isDownloaded(episode.episodeId)
  val progress = progressMap[episode.episodeId]

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(enabled = playable) {
        if (isCurrent) PlayerManager.togglePlayPause() else PlayerManager.play(record, queue)
      }
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Icon(
      if (isCurrent && isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
      contentDescription = null,
      tint = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
      modifier = Modifier.size(28.dp),
    )
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        episode.displayTitle,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        listOfNotNull(
          episode.episodeNumber?.let { "EP$it" },
          episode.releaseDateText.takeIf { it.isNotEmpty() },
          episode.durationText.takeIf { it.isNotEmpty() },
        ).joinToString("  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = { favorites.toggle(record) }) {
      Icon(
        if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = "收藏",
        tint = MaterialTheme.colorScheme.error,
      )
    }
    when {
      isDownloaded -> Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "已下載",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      progress != null -> CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.size(24.dp),
      )
      else -> IconButton(onClick = { downloads.download(record) }, enabled = playable) {
        Icon(Icons.Filled.ArrowDownward, contentDescription = "下載")
      }
    }
  }
}
