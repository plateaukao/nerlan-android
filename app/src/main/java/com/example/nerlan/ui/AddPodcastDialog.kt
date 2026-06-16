package com.example.nerlan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import kotlinx.coroutines.launch

/**
 * Paste-a-URL dialog for subscribing to a podcast. Accepts an Apple Podcasts
 * link, an apple.co short link, or a raw RSS feed URL; resolution + parsing run
 * in [com.example.nerlan.data.PodcastStore.add], with inline progress and error.
 */
@Composable
fun AddPodcastDialog(onDismiss: () -> Unit) {
  val podcasts = NerLanApp.instance.podcasts
  val scope = rememberCoroutineScope()
  var url by remember { mutableStateOf("") }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = { if (!loading) onDismiss() },
    title = { Text("新增 Podcast") },
    text = {
      Column {
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Apple Podcasts 或 RSS 網址") },
          modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
          Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        Text(
          "支援 Apple Podcasts 連結（podcasts.apple.com）或 RSS feed 網址。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      if (loading) {
        CircularProgressIndicator(Modifier.size(24.dp))
      } else {
        TextButton(
          enabled = url.isNotBlank(),
          onClick = {
            var s = url.trim()
            if (!s.startsWith("http", ignoreCase = true)) s = "https://$s"
            scope.launch {
              loading = true
              error = null
              try {
                podcasts.add(s)
                onDismiss()
              } catch (e: Exception) {
                error = e.message ?: "新增失敗"
              }
              loading = false
            }
          },
        ) { Text("新增") }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") }
    },
  )
}
