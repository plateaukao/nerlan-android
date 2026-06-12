package com.example.nerlan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.player.PlayerManager

/** Full player sheet with transport, speed, favorite and download controls. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheet(onDismiss: () -> Unit) {
  val favorites = NerLanApp.instance.favorites
  val downloads = NerLanApp.instance.downloads
  val current by PlayerManager.current.collectAsState()
  val isPlaying by PlayerManager.isPlaying.collectAsState()
  val positionMs by PlayerManager.positionMs.collectAsState()
  val durationMs by PlayerManager.durationMs.collectAsState()
  val hasNext by PlayerManager.hasNext.collectAsState()
  val hasPrevious by PlayerManager.hasPrevious.collectAsState()
  val rate by PlayerManager.playbackRate.collectAsState()
  val repeatMode by PlayerManager.repeatMode.collectAsState()
  val favEpisodes by favorites.episodes.collectAsState()
  val progressMap by downloads.progress.collectAsState()
  val downloadRecords by downloads.records.collectAsState()

  var isScrubbing by remember { mutableStateOf(false) }
  var scrubPosition by remember { mutableFloatStateOf(0f) }
  var speedMenuOpen by remember { mutableStateOf(false) }

  // Open fully expanded so transport controls aren't cropped at half height.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    val record = current ?: return@ModalBottomSheet
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
      CoverImage(record.coverUrl, 200.dp)
      Text(
        record.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 16.dp),
      )
      Text(
        record.programName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        record.language,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
      )

      // Scrubber
      Slider(
        value = if (isScrubbing) scrubPosition else positionMs.toFloat(),
        onValueChange = {
          isScrubbing = true
          scrubPosition = it
        },
        onValueChangeFinished = {
          PlayerManager.seekTo(scrubPosition.toLong())
          isScrubbing = false
        },
        valueRange = 0f..maxOf(durationMs.toFloat(), 1f),
        modifier = Modifier.padding(top = 8.dp),
      )
      Row(Modifier.fillMaxWidth()) {
        Text(
          formatTime(if (isScrubbing) scrubPosition.toLong() else positionMs),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          formatTime(durationMs),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.End,
          modifier = Modifier.weight(1f),
        )
      }

      // Transport
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 8.dp),
      ) {
        IconButton(onClick = { PlayerManager.previous() }, enabled = hasPrevious) {
          Icon(Icons.Filled.SkipPrevious, "上一集", Modifier.size(32.dp))
        }
        IconButton(onClick = { PlayerManager.skip(-15_000) }) {
          Icon(Icons.Filled.FastRewind, "倒退15秒", Modifier.size(28.dp))
        }
        // Expressive shape-morphing primary action: round while paused,
        // rounded-square while pressed/playing.
        FilledIconButton(
          onClick = { PlayerManager.togglePlayPause() },
          shapes = IconButtonDefaults.shapes(),
          modifier = Modifier.size(72.dp),
        ) {
          Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = "播放/暫停",
            modifier = Modifier.size(40.dp),
          )
        }
        IconButton(onClick = { PlayerManager.skip(15_000) }) {
          Icon(Icons.Filled.FastForward, "快轉15秒", Modifier.size(28.dp))
        }
        IconButton(onClick = { PlayerManager.next() }, enabled = hasNext) {
          Icon(Icons.Filled.SkipNext, "下一集", Modifier.size(32.dp))
        }
      }

      // Speed / repeat / favorite / download
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
      ) {
        IconButton(onClick = { PlayerManager.cycleRepeatMode() }) {
          Icon(
            if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne
            else Icons.Filled.Repeat,
            contentDescription = when (repeatMode) {
              androidx.media3.common.Player.REPEAT_MODE_ALL -> "重複播放清單"
              androidx.media3.common.Player.REPEAT_MODE_ONE -> "重複單集"
              else -> "不重複"
            },
            tint = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_OFF)
              MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          TextButton(onClick = { speedMenuOpen = true }) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(rateLabel(rate), modifier = Modifier.padding(start = 4.dp))
          }
          DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
            PlayerManager.AVAILABLE_RATES.forEach { r ->
              DropdownMenuItem(
                text = { Text(rateLabel(r) + if (r == rate) " ✓" else "") },
                onClick = {
                  PlayerManager.setRate(r)
                  speedMenuOpen = false
                },
              )
            }
          }
        }

        val isFav = favEpisodes.any { it.id == record.id }
        TextButton(onClick = { favorites.toggle(record) }) {
          Icon(
            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
          )
          Text("收藏", modifier = Modifier.padding(start = 4.dp))
        }

        val isDownloaded = downloadRecords.any { it.id == record.id } || downloads.isDownloaded(record.id)
        val progress = progressMap[record.id]
        when {
          isDownloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text("已下載", modifier = Modifier.padding(start = 4.dp))
          }
          progress != null -> CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
          else -> TextButton(onClick = { downloads.download(record) }) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("下載", modifier = Modifier.padding(start = 4.dp))
          }
        }
      }
    }
  }
}
