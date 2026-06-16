package com.example.nerlan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.PodcastFeed
import kotlinx.coroutines.launch

/**
 * A subscribed podcast: show info plus its episodes. Episodes are already
 * [EpisodeRecord]s, so the row reuses [RecordRow] (with inline favorite +
 * download on, AI icons off — matching the NER episode list); playback, download,
 * favoriting, and AI all run through the existing code path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastDetailScreen(feed: PodcastFeed, onBack: () -> Unit) {
  val podcasts = NerLanApp.instance.podcasts
  val scope = rememberCoroutineScope()
  val feeds by podcasts.feeds.collectAsState()
  // Prefer the freshest stored copy (pull-to-refresh updates the store).
  val current = feeds.firstOrNull { it.id == feed.id } ?: feed
  val isSubscribed = feeds.any { it.id == feed.id }
  var isRefreshing by remember { mutableStateOf(false) }
  var showFullIntro by remember { mutableStateOf(false) }

  Scaffold(
    contentWindowInsets = WindowInsets(0),
    topBar = {
      TopAppBar(
        windowInsets = WindowInsets(0),
        title = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
          }
        },
        actions = {
          IconButton(onClick = {
            if (isSubscribed) podcasts.unsubscribe(feed.id) else podcasts.subscribe(current)
          }) {
            Icon(
              if (isSubscribed) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
              contentDescription = "訂閱",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        },
      )
    },
  ) { padding ->
    PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = {
        scope.launch {
          isRefreshing = true
          runCatching { podcasts.refresh(feed.id) }
          isRefreshing = false
        }
      },
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      LazyColumn(Modifier.fillMaxSize()) {
        item {
          Column(Modifier.padding(16.dp)) {
            Row {
              CoverImage(current.coverUrl, 88.dp)
              Column(Modifier.padding(start = 12.dp)) {
                Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                current.author?.takeIf { it.isNotEmpty() }?.let {
                  Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                  )
                }
                Text(
                  "共 ${current.episodes.size} 集",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                )
              }
            }
            val intro = current.descriptionText
            if (intro.isNotEmpty()) {
              Text(
                intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showFullIntro) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).clickable { showFullIntro = !showFullIntro },
              )
            }
            Text(
              "單集列表（共 ${current.episodes.size} 集）",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 16.dp),
            )
          }
        }
        items(current.episodes.size, key = { current.episodes[it].id }) { i ->
          val record = current.episodes[i]
          RecordRow(
            record = record,
            queue = current.episodes,
            showFavorite = true,
            showDownload = true,
            subtitleOverride = subtitle(record),
            showAI = false,
          )
        }
      }
    }
  }
}

/** Date · duration; falls back to the language label if neither is known. */
private fun subtitle(record: EpisodeRecord): String {
  val parts = listOfNotNull(
    record.releaseDateText.takeIf { it.isNotEmpty() },
    record.durationText.takeIf { it.isNotEmpty() },
  )
  return if (parts.isEmpty()) record.language else parts.joinToString(" · ")
}
