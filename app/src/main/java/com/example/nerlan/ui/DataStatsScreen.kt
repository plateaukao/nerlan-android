package com.example.nerlan.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.NerLanApp
import com.example.nerlan.player.AudioCache

/**
 * 資料統計 — an inventory of what the app has stored on this device: favorites,
 * downloads, streamed cache, AI content, and the language breakdown of downloads.
 * Read live from the existing stores, so it works retroactively. Mirrors the iOS
 * DataStatsView.
 */
@Composable
fun DataStatsScreen(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val app = NerLanApp.instance
  val favoriteEpisodes by app.favorites.episodes.collectAsState()
  val favoritePrograms by app.favorites.programs.collectAsState()
  val downloads by app.downloads.records.collectAsState()

  // Filesystem-derived values walk the files/cache dirs, so compute them once.
  val downloadBytes = remember { app.downloads.downloadedBytes() }
  val attachmentCount = remember { app.downloads.attachmentCount() }
  val cacheBytes = remember { AudioCache.sizeBytes(context) }
  val cachedCount = remember { AudioCache.cachedResourceCount(context) }
  val transcriptCount = remember { app.ai.transcriptCount() }
  val handoutCount = remember { app.ai.handoutCount() }

  val languageRows = remember(downloads) {
    downloads.groupingBy { it.language }.eachCount().entries.sortedByDescending { it.value }
  }

  fun bytes(b: Long) = Formatter.formatShortFileSize(context, b)

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
          IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
          Text(
            "資料統計",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }

        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
          Title("收藏", top = 8.dp)
          StatRow("收藏單集", "${favoriteEpisodes.size}")
          StatRow("收藏節目", "${favoritePrograms.size}")

          Title("下載")
          StatRow("已下載單集", "${downloads.size}")
          StatRow("佔用空間", bytes(downloadBytes))
          StatRow("講義附件", "$attachmentCount")

          Title("串流快取")
          if (cachedCount >= 0) StatRow("快取項目", "$cachedCount")
          StatRow("快取大小", bytes(cacheBytes))

          Title("AI 內容")
          StatRow("逐字稿", "$transcriptCount")
          StatRow("AI 講義", "$handoutCount")

          if (languageRows.isNotEmpty()) {
            Title("語言分布（已下載）")
            languageRows.forEach { StatRow(it.key, "${it.value}") }
          }
          Spacer(Modifier.height(24.dp))
        }
      }
    }
  }
}

@Composable
private fun Title(text: String, top: androidx.compose.ui.unit.Dp = 16.dp) {
  Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = top, bottom = 4.dp),
  )
}
