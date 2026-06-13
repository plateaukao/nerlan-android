package com.example.nerlan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Read-only transcript viewer shown as a full-screen dialog over the player. The
 * stored transcript has one sentence per line (segmented by the chat model),
 * rendered here as a numbered, sentence-by-sentence list in a LazyColumn (cell
 * reuse keeps scrolling smooth for long episodes). Wrapped in a SelectionContainer
 * so sentences can be selected/copied; a copy-all button is in the top bar.
 */
@Composable
fun TranscriptDialog(title: String, text: String, onDismiss: () -> Unit) {
  val sentences = remember(text) {
    text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
  }
  val clipboard = LocalClipboardManager.current

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
          IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
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
            LazyColumn(Modifier.fillMaxSize()) {
              items(sentences.size) { i ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                  Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                  )
                  Text(
                    sentences[i],
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
