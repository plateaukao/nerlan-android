package com.example.nerlan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info as InfoOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.AiKind
import com.example.nerlan.player.PlayerManager

/** Full player sheet with transport, speed, favorite and download controls. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSheet(onDismiss: () -> Unit) {
  val favorites = NerLanApp.instance.favorites
  val downloads = NerLanApp.instance.downloads
  val ai = NerLanApp.instance.ai
  val apiKey by NerLanApp.instance.settings.apiKey.collectAsState()
  val panel = LocalStudyPanel.current
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
  val aiRevision by ai.revision.collectAsState()

  var isScrubbing by remember { mutableStateOf(false) }
  var scrubPosition by remember { mutableFloatStateOf(0f) }
  var speedMenuOpen by remember { mutableStateOf(false) }
  var showAttachment by remember { mutableStateOf(false) }
  var showShadowDialog by remember { mutableStateOf(false) }

  // Timestamp cues for the playing episode's transcript, when one exists. A
  // non-empty list enables the caption (字幕) toggle, which swaps the cover/title
  // block for the synced transcript view. Reset per-episode so each starts on
  // the cover.
  val captionCues = remember(current?.id, aiRevision) {
    current?.id?.let { id -> if (ai.hasTranscript(id)) ai.transcriptCues(id) else null }
  }
  val captionsAvailable = !captionCues.isNullOrEmpty()
  var captionMode by remember(current?.id) { mutableStateOf(false) }

  // Open fully expanded so transport controls aren't cropped at half height.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    val record = current ?: return@ModalBottomSheet
    // In caption mode the transcript takes the slack above the controls, so the
    // column must fill the sheet height for weight(1f) to resolve.
    // In landscape on a phone the sheet is too short for the 200dp cover plus the
    // title/metadata header and all the controls, so the lower rows get cropped.
    // Drop the whole header when the available height is small (phone landscape)
    // so the transport and action rows always fit without scrolling. Portrait and
    // tablets (taller than this) keep the cover as before.
    val compactHeight = LocalConfiguration.current.screenHeightDp < 500
    // Caption mode swaps the cover area for the synced transcript; with the header
    // hidden in compact height there's nowhere for it to live, so disable it (and
    // hide its toggle below) in phone landscape.
    val showCaptions = captionMode && captionsAvailable && !compactHeight
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .then(if (showCaptions) Modifier.fillMaxHeight() else Modifier)
        .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
      if (showCaptions) {
        // Take over the cover/title/program/language area with the synced
        // transcript; its Close button (and the 字幕 toggle) exit caption mode.
        Box(Modifier.fillMaxWidth().weight(1f)) {
          TranscriptContent(
            record,
            remember(record.id, aiRevision) { ai.transcriptText(record.id).orEmpty() },
            onClose = { captionMode = false },
            cues = captionCues,
          )
        }
      } else if (!compactHeight) {
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
      }

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

      // Repeat / speed / favorite / handout / download. Spread evenly across the
      // full width with compact button padding so all five fit one line.
      val controlPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
      val controlLabel = MaterialTheme.typography.labelMedium
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
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

        Box {
          TextButton(onClick = { speedMenuOpen = true }, contentPadding = controlPadding) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(rateLabel(rate), style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
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
        TextButton(onClick = { favorites.toggle(record) }, contentPadding = controlPadding) {
          Icon(
            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
          )
          Text("收藏", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        }

        if (record.pdfAttachments.isNotEmpty()) {
          // Filled once the PDF is saved offline, outline until then (fill, not
          // tint, signals state on e-ink). Matches the AI transcript/handout icons.
          val handoutSaved = remember(downloadRecords, record.id) {
            record.pdfAttachments.all { downloads.localAttachmentPath(it) != null }
          }
          TextButton(
            onClick = {
              if (panel != null) { panel.item = StudyItem.Attachment(record); onDismiss() }
              else showAttachment = true
            },
            contentPadding = controlPadding,
          ) {
            Icon(
              if (handoutSaved) Icons.Filled.Info else Icons.Outlined.InfoOutline,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
            Text("講義", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
          }
        }

        val isDownloaded = downloadRecords.any { it.id == record.id } || downloads.isDownloaded(record.id)
        val progress = progressMap[record.id]
        when {
          isDownloaded -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp),
          ) {
            Icon(
              Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text("已下載", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
          }
          progress != null -> CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
          else -> TextButton(onClick = { downloads.download(record) }, contentPadding = controlPadding) {
            Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(20.dp))
            Text("下載", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
          }
        }
      }

      // AI tools (caption / transcript / handout) — only once an API key is set.
      if (apiKey.isNotBlank()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
          modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
        ) {
          // Caption toggle: only when the transcript carries timestamps, so the
          // synced transcript view has cues to highlight/scroll. Hidden in compact
          // height (phone landscape) where caption mode is disabled.
          if (captionsAvailable && !compactHeight) {
            Row(
              modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { captionMode = !captionMode }
                .padding(horizontal = 6.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                Icons.Filled.ClosedCaption,
                contentDescription = "字幕",
                tint = if (captionMode) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
              Text("字幕", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }

            // 跟讀: open the transcript (dialog on phone, side panel on large
            // screens) already in shadowing mode — same surface as 逐字稿.
            Row(
              modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable {
                  if (panel != null) { panel.item = StudyItem.Shadow(record); onDismiss() }
                  else showShadowDialog = true
                }
                .padding(horizontal = 6.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                Icons.Filled.RecordVoiceOver,
                contentDescription = "跟讀",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
              Text("跟讀", style = controlLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
          }
          AiActionButton(AiKind.TRANSCRIPT, record, compact = false, onOpenedInPanel = onDismiss)
          AiActionButton(AiKind.HANDOUT, record, compact = false, onOpenedInPanel = onDismiss)
        }
      }

      if (showAttachment) {
        AttachmentViewer(
          title = record.title,
          attachments = record.pdfAttachments,
          onDismiss = { showAttachment = false },
        )
      }

      if (showShadowDialog) {
        TranscriptDialog(
          record,
          ai.transcriptText(record.id).orEmpty(),
          onDismiss = { showShadowDialog = false },
          cues = captionCues,
          startShadowing = true,
        )
      }
    }
  }
}
