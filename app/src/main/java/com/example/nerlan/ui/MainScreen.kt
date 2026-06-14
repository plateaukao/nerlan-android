package com.example.nerlan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.data.Program
import com.example.nerlan.player.PlayerManager

/**
 * Root scaffold: four tabs (節目/收藏/下載/AI) with the mini player floating
 * above the bottom navigation bar, mirroring the iOS layout. On large screens
 * (width >= 720.dp) the browser sits in a left pane with a transcript/handout/
 * 講義 detail panel on the right, like the iPad two-pane layout.
 */
@Composable
fun MainScreen() {
  var tab by rememberSaveable { mutableIntStateOf(0) }
  // per-tab pushed program detail (single-level stack, like the iOS app in practice)
  var programsDetail by remember { mutableStateOf<Program?>(null) }
  var favoritesDetail by remember { mutableStateOf<Program?>(null) }
  var showPlayerSheet by remember { mutableStateOf(false) }
  var leftCollapsed by remember { mutableStateOf(false) }
  val current by PlayerManager.current.collectAsState()
  val panel = remember { StudyPanelController() }

  val browser = @Composable {
    Scaffold(
      bottomBar = {
        Column {
          if (current != null) {
            MiniPlayerBar(onTap = { showPlayerSheet = true })
          }
          NavigationBar {
            NavigationBarItem(
              selected = tab == 0,
              onClick = { tab = 0 },
              icon = { Icon(Icons.Filled.Radio, contentDescription = null) },
              label = { Text("節目") },
            )
            NavigationBarItem(
              selected = tab == 1,
              onClick = { tab = 1 },
              icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
              label = { Text("收藏") },
            )
            NavigationBarItem(
              selected = tab == 2,
              onClick = { tab = 2 },
              icon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
              label = { Text("下載") },
            )
            NavigationBarItem(
              selected = tab == 3,
              onClick = { tab = 3 },
              icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
              label = { Text("AI") },
            )
          }
        }
      },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        // Keep every tab — and the program detail overlaid on its list —
        // composed, so switching tabs or opening/closing a program never
        // re-fetches or resets filters/scroll. Only the active tab is drawn.
        TabContainer(tab == 0) {
          ProgramListScreen(onProgramClick = { programsDetail = it })
          programsDetail?.let { program ->
            ProgramDetailScreen(program, onBack = { programsDetail = null })
          }
        }
        TabContainer(tab == 1) {
          FavoritesScreen(onProgramClick = { favoritesDetail = it })
          favoritesDetail?.let { program ->
            ProgramDetailScreen(program, onBack = { favoritesDetail = null })
          }
        }
        TabContainer(tab == 2) { DownloadsScreen() }
        TabContainer(tab == 3) { AiTabScreen() }
      }
    }
  }

  BoxWithConstraints(Modifier.fillMaxSize()) {
    // 800dp threshold so the detail pane has room; below it (e.g. tablet
    // portrait) we use the single-pane phone layout.
    val twoPane = maxWidth >= 800.dp
    CompositionLocalProvider(LocalStudyPanel provides (if (twoPane) panel else null)) {
      if (twoPane) {
        // When a new episode starts, default the panel to its study content
        // (PDF handout, else AI handout, else transcript).
        LaunchedEffect(current) { panel.item = current?.let { defaultStudyItem(it) } }
        Row(Modifier.fillMaxSize()) {
          if (!leftCollapsed) {
            Box(Modifier.width(380.dp).fillMaxHeight()) { browser() }
            VerticalDivider()
          }
          // The browser pane gets insets from its Scaffold; the detail pane is
          // raw, so inset it from the status/navigation bars here.
          Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars)) {
            // Always offer the hide/show-browser toggle in two-pane mode.
            StudyDetailPanel(
              panel,
              leftCollapsed = leftCollapsed,
              onToggleLeft = { leftCollapsed = !leftCollapsed },
            )
          }
        }
      } else {
        browser()
      }

      if (showPlayerSheet) {
        PlayerSheet(onDismiss = { showPlayerSheet = false })
      }
    }
  }
}

/**
 * Hosts one tab's content. The content is always composed (so its loaded data,
 * filters and scroll state survive), but only measured and drawn when [active] —
 * inactive tabs stay alive without being shown or receiving touches.
 */
@Composable
private fun TabContainer(active: Boolean, content: @Composable () -> Unit) {
  Box(
    Modifier.fillMaxSize().layout { measurable, constraints ->
      val placeable = measurable.measure(constraints)
      if (active) {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      } else {
        // Composed (state retained) but not placed: nothing drawn or touchable.
        layout(0, 0) {}
      }
    },
  ) { content() }
}

/** Compact now-playing bar shown above the bottom navigation. */
@Composable
fun MiniPlayerBar(onTap: () -> Unit) {
  val current by PlayerManager.current.collectAsState()
  val isPlaying by PlayerManager.isPlaying.collectAsState()
  val hasNext by PlayerManager.hasNext.collectAsState()
  val record = current ?: return

  Surface(tonalElevation = 3.dp) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f).clickable(onClick = onTap),
      ) {
        CoverImage(record.coverUrl, 40.dp)
        Column(Modifier.padding(start = 12.dp)) {
          Text(
            record.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            record.programName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      IconButton(onClick = { PlayerManager.togglePlayPause() }) {
        Icon(
          if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
          contentDescription = "播放/暫停",
          modifier = Modifier.size(28.dp),
        )
      }
      IconButton(onClick = { PlayerManager.next() }, enabled = hasNext) {
        Icon(Icons.Filled.SkipNext, contentDescription = "下一集", modifier = Modifier.size(28.dp))
      }
    }
  }
}
