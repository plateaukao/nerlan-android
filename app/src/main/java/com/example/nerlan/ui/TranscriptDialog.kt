package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.data.TranscriptCue
import com.example.nerlan.player.PlayerManager

/**
 * Read-only transcript viewer shown as a full-screen dialog over the player. The
 * stored transcript has one sentence per line (segmented by the chat model),
 * rendered here as a numbered, sentence-by-sentence list in a LazyColumn (cell
 * reuse keeps scrolling smooth for long episodes). Wrapped in a SelectionContainer
 * so sentences can be selected/copied; a copy-all button is in the top bar.
 *
 * When the transcript was produced with timestamps ([cues]) and belongs to the
 * episode currently playing, the spoken sentence is highlighted and kept on screen
 * — a karaoke-style follow-along. Transcripts without cues render plain.
 */
@Composable
fun TranscriptDialog(
  title: String,
  text: String,
  onDismiss: () -> Unit,
  episodeId: String? = null,
  cues: List<TranscriptCue>? = null,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      TranscriptContent(title, text, onDismiss, episodeId = episodeId, cues = cues)
    }
  }
}

/** The transcript body, shared by the phone dialog and the large-screen panel. */
@Composable
fun TranscriptContent(
  title: String,
  text: String,
  onClose: () -> Unit,
  leading: @Composable () -> Unit = {},
  episodeId: String? = null,
  cues: List<TranscriptCue>? = null,
) {
  val sentences = remember(text, cues) {
    if (!cues.isNullOrEmpty()) cues.map { it.text }
    else text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
  }
  val clipboard = LocalClipboardManager.current
  val listState = rememberLazyListState()

  val current by PlayerManager.current.collectAsState()
  val positionMs by PlayerManager.positionMs.collectAsState()

  // Sentence currently being spoken (index), or -1 when this isn't the playing
  // episode / there are no cues. derivedStateOf recomputes only when the index
  // actually changes, so the rows don't churn on every 0.5s position tick.
  val activeIndex by remember(cues, episodeId) {
    derivedStateOf {
      val c = cues
      if (episodeId == null || c.isNullOrEmpty() || current?.id != episodeId) {
        -1
      } else {
        val t = positionMs / 1000.0 + 0.05
        var lo = 0; var hi = c.size - 1; var found = -1
        while (lo <= hi) {
          val mid = (lo + hi) / 2
          if (c[mid].start <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        found
      }
    }
  }

  LaunchedEffect(activeIndex) {
    if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
  }

  Column(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
      leading()
      IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
      )
      if (sentences.isNotEmpty()) {
        IconButton(onClick = { clipboard.setText(AnnotatedString(sentences.joinToString("\n"))) }) {
          Icon(Icons.Filled.ContentCopy, contentDescription = "複製全部")
        }
      }
    }

    if (sentences.isEmpty()) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("沒有逐字稿內容", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      SelectionContainer {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
          items(sentences.size) { i ->
            val active = i == activeIndex
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
              Text(
                "${i + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
              )
              Text(
                sentences[i],
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else null,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
              )
            }
          }
        }
      }
    }
  }
}
