package com.example.nerlan.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.AutoAwesome as AutoAwesomeOutline
import androidx.compose.material.icons.outlined.Subtitles as SubtitlesOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.AIContentStore
import com.example.nerlan.data.AiKind
import com.example.nerlan.data.EpisodeRecord

/**
 * Shared transcript / AI-handout action button. Shows an idle icon, a spinner
 * while its OpenAI job runs, and opens the saved content in a dialog when ready;
 * tapping when nothing is saved kicks off processing and auto-opens the result.
 * `compact` = icon only (list rows); otherwise icon + label (full player).
 * Mirrors the iOS `AIActionButton`.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AiActionButton(
  kind: AiKind,
  record: EpisodeRecord,
  compact: Boolean,
  onOpenedInPanel: (() -> Unit)? = null,
) {
  val ai = NerLanApp.instance.ai
  val panel = LocalStudyPanel.current
  val jobs by ai.jobs.collectAsState()
  val revision by ai.revision.collectAsState()
  val job = jobs["${kind.prefix}:${record.id}"]
  val ready = remember(job, revision, record.id) {
    if (kind == AiKind.TRANSCRIPT) ai.hasTranscript(record.id) else ai.hasHandout(record.id)
  }
  val running = job is AIContentStore.JobState.Running
  val failureMessage = (job as? AIContentStore.JobState.Failed)?.message

  var pendingOpen by remember { mutableStateOf(false) }
  var showSheet by remember { mutableStateOf(false) }
  var showMenu by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // On large screens route content to the detail panel; else open a dialog.
  fun open() {
    if (panel != null) {
      panel.item =
        if (kind == AiKind.TRANSCRIPT) StudyItem.Transcript(record) else StudyItem.Handout(record)
      onOpenedInPanel?.invoke()
    } else {
      showSheet = true
    }
  }

  LaunchedEffect(ready) { if (ready && pendingOpen) { pendingOpen = false; open() } }
  LaunchedEffect(failureMessage) {
    if (failureMessage != null && pendingOpen) { pendingOpen = false; errorMessage = failureMessage }
  }

  fun start() {
    if (kind == AiKind.TRANSCRIPT) ai.processTranscript(record) else ai.processHandout(record)
  }

  val onClick: () -> Unit = {
    when {
      running -> {}
      failureMessage != null -> errorMessage = failureMessage
      ready -> open()
      else -> { pendingOpen = true; start() }
    }
  }

  val label = if (kind == AiKind.TRANSCRIPT) "逐字稿" else "AI 講義"
  val onLongClick: () -> Unit = { if (ready || failureMessage != null) showMenu = true }
  Box {
    if (compact) {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .combinedClickable(onClick = onClick, onLongClick = onLongClick)
          .size(40.dp),
        contentAlignment = Alignment.Center,
      ) { AiIcon(kind, running, ready, failureMessage != null) }
    } else {
      Row(
        modifier = Modifier
          .clip(MaterialTheme.shapes.small)
          .combinedClickable(onClick = onClick, onLongClick = onLongClick)
          .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AiIcon(kind, running, ready, failureMessage != null)
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
      }
    }
    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
      DropdownMenuItem(
        text = { Text("重新產生") },
        onClick = { showMenu = false; pendingOpen = false; ai.regenerate(kind, record) },
      )
      DropdownMenuItem(
        text = { Text("刪除$label") },
        onClick = { showMenu = false; ai.delete(kind, record.id) },
      )
    }
  }

  if (showSheet) {
    when (kind) {
      AiKind.TRANSCRIPT -> TranscriptDialog(
        record, ai.transcriptText(record.id).orEmpty(),
        onDismiss = { showSheet = false }, cues = ai.transcriptCues(record.id))
      AiKind.HANDOUT -> HandoutDialog(record.title, ai.handoutHtml(record.id).orEmpty()) { showSheet = false }
    }
  }

  errorMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { errorMessage = null },
      title = { Text("處理失敗") },
      text = { Text(msg) },
      confirmButton = {
        TextButton(onClick = { errorMessage = null; pendingOpen = true; start() }) { Text("重試") }
      },
      dismissButton = { TextButton(onClick = { errorMessage = null }) { Text("好") } },
    )
  }
}

@Composable
private fun AiIcon(kind: AiKind, running: Boolean, ready: Boolean, failed: Boolean) {
  when {
    running -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    failed -> Icon(
      Icons.Filled.ErrorOutline,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.error,
      modifier = Modifier.size(20.dp),
    )
    else -> {
      // Outline = not yet generated, filled = generated. The fill (not just the
      // tint) carries the state so it stays legible on grayscale e-ink displays.
      val icon: ImageVector = when (kind) {
        AiKind.TRANSCRIPT -> if (ready) Icons.Filled.Subtitles else Icons.Outlined.SubtitlesOutline
        AiKind.HANDOUT -> if (ready) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesomeOutline
      }
      Icon(
        icon,
        contentDescription = null,
        tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
