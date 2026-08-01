package com.example.nerlan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.CatalogCache
import com.example.nerlan.data.Episode
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.ChannelPlusApi
import com.example.nerlan.data.Program
import com.example.nerlan.player.PlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Program info plus its full episode archive (infinite scroll, oldest first). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgramDetailScreen(program: Program, onBack: () -> Unit) {
  val favorites = NerLanApp.instance.favorites
  val catalog = NerLanApp.instance.catalog
  val scope = rememberCoroutineScope()
  var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
  var page by remember { mutableIntStateOf(0) }
  var totalPages by remember { mutableIntStateOf(1) }
  var totalCount by remember { mutableIntStateOf(0) }
  var isLoading by remember { mutableStateOf(false) }
  var isRefreshing by remember { mutableStateOf(false) }
  var initialized by remember { mutableStateOf(false) }
  var showFullIntro by remember { mutableStateOf(false) }
  val favoritePrograms by favorites.programs.collectAsState()
  val isFavProgram = favoritePrograms.any { it.programId == program.programId }

  val listState = rememberLazyListState()
  val nearEnd by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last >= listState.layoutInfo.totalItemsCount - 3
    }
  }

  fun persist() {
    scope.launch {
      catalog.saveEpisodes(
        program.programId,
        CatalogCache.EpisodePageCache(episodes, page, totalPages, totalCount),
      )
    }
  }

  // On first appearance, restore cached episode pages (no network); infinite
  // scroll then resumes from the cached cursor. Only fetch on a cache miss.
  LaunchedEffect(Unit) {
    val cached = catalog.loadEpisodes(program.programId)
    if (cached != null && cached.episodes.isNotEmpty()) {
      episodes = cached.episodes
      page = cached.page
      totalPages = cached.totalPages
      totalCount = cached.totalCount
    }
    initialized = true
  }

  // Infinite scroll: fetch the next page near the end (and page 1 when there was
  // no cache). Persists each page so reopening the program skips the network.
  // One long-lived effect owns the whole fetch loop: keying it on nearEnd would
  // cancel an in-flight request whenever the flag flips — and the spinner row
  // itself shifts totalItemsCount, so resting at the threshold could thrash
  // cancel/re-request cycles for the same page.
  LaunchedEffect(Unit) {
    snapshotFlow { initialized }.first { it }
    while (true) {
      snapshotFlow { page == 0 || (nearEnd && page < totalPages) }.first { it }
      isLoading = true
      try {
        val result = ChannelPlusApi.episodes(program.programId, page + 1)
        val known = episodes.map { it.episodeId }.toSet()
        episodes = episodes + result.episodes.filterNot { it.episodeId in known }
        page += 1
        totalPages = result.totalPages
        totalCount = result.totalCount
        persist()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Keep what we have, but back off so a dead network doesn't hammer
        // the API while the list sits at the threshold.
        delay(2_000)
      }
      isLoading = false
    }
  }

  // Pull-to-refresh: re-fetch from the first page, replacing the cache. Episodes
  // are ascending, so a higher total count surfaces newly-added ones on scroll.
  fun refresh() {
    scope.launch {
      isRefreshing = true
      try {
        val result = ChannelPlusApi.episodes(program.programId, 1)
        episodes = result.episodes
        page = 1
        totalPages = result.totalPages
        totalCount = result.totalCount
        persist()
      } catch (_: Exception) {
        // keep what we have on a failed refresh
      }
      isRefreshing = false
    }
  }

  // One combined list drives the lazy items below. Indexing two separately
  // derived lists inside the item lambda can crash: a measure-time subcomposition
  // may run between the episodes write and recomposition (or after refresh()
  // shrinks the list), and then count, keys and content disagree.
  val rows = remember(episodes) { episodes.map { it to EpisodeRecord.from(it, program) } }
  val records = remember(rows) { rows.map { it.second } }

  // The parent (MainScreen) Scaffold already insets below the status bar;
  // zero out this nested Scaffold's insets to avoid double top spacing.
  Scaffold(
    contentWindowInsets = WindowInsets(0),
    topBar = {
      TopAppBar(
        windowInsets = WindowInsets(0),
        // The header below shows the program name next to the cover, so don't repeat
        // it as the app-bar title.
        title = {},
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
    PullToRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = { refresh() },
      modifier = Modifier.fillMaxSize().padding(padding),
    ) {
      LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
      items(rows, key = { it.first.episodeId }) { (episode, record) ->
        EpisodeRow(episode = episode, record = record, queue = records)
      }
      if (isLoading) {
        item {
          Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(32.dp))
          }
        }
      }
      }
    }
  }
}

/** One episode row with play / favorite / download actions. A trailing swipe
 *  or a long-press opens the note editor (mirroring iOS) — titles are often
 *  just "EP12", so a note is how the user records what the episode actually
 *  covers. The swipe box never dismisses: crossing the threshold opens the
 *  editor and the row snaps back. */
@OptIn(
  ExperimentalMaterial3ExpressiveApi::class,
  ExperimentalFoundationApi::class,
  ExperimentalMaterial3Api::class,
)
@Composable
fun EpisodeRow(episode: Episode, record: EpisodeRecord, queue: List<EpisodeRecord>) {
  val favorites = NerLanApp.instance.favorites
  val downloads = NerLanApp.instance.downloads
  val current by PlayerManager.current.collectAsState()
  val favEpisodes by favorites.episodes.collectAsState()
  val progressMap by downloads.progress.collectAsState()
  val downloadRecords by downloads.records.collectAsState()
  val notesMap by NerLanApp.instance.notes.notes.collectAsState()
  var editingNote by remember { mutableStateOf(false) }

  val isCurrent = current?.id == episode.episodeId
  val playable = episode.audioUrl != null
  val isFav = favEpisodes.any { it.id == episode.episodeId }
  val isDownloaded = downloadRecords.any { it.id == episode.episodeId } || downloads.isDownloaded(episode.episodeId)
  val progress = progressMap[episode.episodeId]

  val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { value ->
      if (value == SwipeToDismissBoxValue.EndToStart) editingNote = true
      value == SwipeToDismissBoxValue.Settled
    }
  )
  SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = false,
    backgroundContent = {
      Box(
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.tertiaryContainer)
          .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd,
      ) {
        Icon(
          Icons.AutoMirrored.Filled.StickyNote2,
          contentDescription = "註記",
          tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
      }
    },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        // Opaque so the swipe reveal doesn't bleed through the row.
        .background(MaterialTheme.colorScheme.background)
        .combinedClickable(
          onClick = {
            if (!playable) return@combinedClickable
            if (isCurrent) PlayerManager.togglePlayPause() else PlayerManager.play(record, queue)
          },
          onLongClick = { editingNote = true },
        )
        .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Column(Modifier.weight(1f)) {
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
        notesMap[episode.episodeId]?.let { EpisodeNoteText(it) }
      }
      IconButton(onClick = { favorites.toggle(record) }) {
        Icon(
          if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
          contentDescription = "收藏",
          tint = MaterialTheme.colorScheme.error,
        )
      }
      // All three states share the 48dp IconButton footprint so row columns align.
      Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when {
          isDownloaded -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "已下載",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
          )
          progress != null -> CircularWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
          )
          else -> IconButton(onClick = { downloads.download(record) }, enabled = playable) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = "下載")
          }
        }
      }
    }
  }

  if (editingNote) {
    EpisodeNoteDialog(
      episodeId = episode.episodeId,
      episodeTitle = episode.displayTitle,
      onDismiss = { editingNote = false },
    )
  }
}
