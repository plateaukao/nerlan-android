package com.example.nerlan.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
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
@Composable
fun AiActionButton(kind: AiKind, record: EpisodeRecord, compact: Boolean) {
  val ai = NerLanApp.instance.ai
  val jobs by ai.jobs.collectAsState()
  val job = jobs["${kind.prefix}:${record.id}"]
  val ready = remember(job, record.id) {
    if (kind == AiKind.TRANSCRIPT) ai.hasTranscript(record.id) else ai.hasHandout(record.id)
  }
  val running = job is AIContentStore.JobState.Running
  val failureMessage = (job as? AIContentStore.JobState.Failed)?.message

  var pendingOpen by remember { mutableStateOf(false) }
  var showSheet by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(ready) { if (ready && pendingOpen) { pendingOpen = false; showSheet = true } }
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
      ready -> showSheet = true
      else -> { pendingOpen = true; start() }
    }
  }

  val label = if (kind == AiKind.TRANSCRIPT) "逐字稿" else "AI 講義"
  if (compact) {
    IconButton(onClick = onClick) { AiIcon(kind, running, ready, failureMessage != null) }
  } else {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)) {
      AiIcon(kind, running, ready, failureMessage != null)
      Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
  }

  if (showSheet) {
    when (kind) {
      AiKind.TRANSCRIPT -> TranscriptDialog(record.title, ai.transcriptText(record.id).orEmpty()) { showSheet = false }
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
      val icon: ImageVector = when (kind) {
        AiKind.TRANSCRIPT -> Icons.Filled.Subtitles
        AiKind.HANDOUT -> if (ready) Icons.Filled.Description else Icons.Filled.AutoAwesome
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
