package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info as InfoOutline
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.AiKind
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.Program
import com.example.nerlan.player.PlayerManager

/** Favorited programs and episodes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavoritesScreen(onProgramClick: (Program) -> Unit) {
  val favorites = NerLanApp.instance.favorites
  val programs by favorites.programs.collectAsState()
  val episodes by favorites.episodes.collectAsState()

  if (programs.isEmpty() && episodes.isEmpty()) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("沒有收藏\n點選節目或單集旁的愛心即可加入收藏。",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  val grouped = episodes.groupBy { it.programName }
    .mapValues { (_, records) -> records.sortedBy { it.playDate ?: "" } }
    .toSortedMap()

  LazyColumn(Modifier.fillMaxSize()) {
    item {
      Text(
        "收藏",
        style = MaterialTheme.typography.headlineMediumEmphasized,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
      )
    }
    if (programs.isNotEmpty()) {
      item {
        SectionHeader("節目")
      }
      items(programs.size, key = { "fp-${programs[it].programId}" }) { i ->
        ProgramRow(programs[i], onClick = { onProgramClick(programs[i]) })
      }
    }
    grouped.forEach { (programName, records) ->
      item(key = "fh-$programName") { SectionHeader(programName) }
      items(records.size, key = { "fe-${records[it].id}" }) { i ->
        RecordRow(records[i], queue = records, onDelete = { favorites.toggle(records[i]) })
      }
    }
  }
}

@Composable
fun SectionHeader(title: String) {
  Text(
    title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

/**
 * Shared row for favorites / downloads / AI lists: tap to play, optional trash.
 * In [aiReadyOnly] mode (the AI tab) the transcript/handout buttons appear only
 * for content that already exists, regardless of whether an API key is set.
 *
 * When [onDelete] is set the row is wrapped in a [SwipeToDismissBox]: swipe it
 * right-to-left to remove (downloads delete the file, favorites un-favorite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordRow(
  record: EpisodeRecord,
  queue: List<EpisodeRecord>,
  onDelete: (() -> Unit)? = null,
  aiReadyOnly: Boolean = false,
  // Podcast detail turns these on for inline favorite + download (like the NER
  // episode list). Off everywhere else, so Downloads/Favorites/AI rows are unchanged.
  showFavorite: Boolean = false,
  showDownload: Boolean = false,
  // Replaces the "program · language" subtitle (podcast rows show date · duration).
  subtitleOverride: String? = null,
  // Podcast episode list turns AI icons off, matching the NER episode list.
  showAI: Boolean = true,
) {
  val current by PlayerManager.current.collectAsState()
  val apiKey by NerLanApp.instance.settings.apiKey.collectAsState()
  val ai = NerLanApp.instance.ai
  val revision by ai.revision.collectAsState()
  val panel = LocalStudyPanel.current
  val isCurrent = current?.id == record.id
  var showAttachment by remember { mutableStateOf(false) }

  val row = @Composable {
   Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      // Opaque so the swipe-to-dismiss reveal doesn't bleed through the row.
      .background(MaterialTheme.colorScheme.background)
      .clickable {
        if (isCurrent) PlayerManager.togglePlayPause() else PlayerManager.play(record, queue)
      }
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    CoverImage(record.coverUrl, 44.dp)
    Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(
        record.title,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        subtitleOverride ?: "${record.programName} · ${record.language}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (showFavorite) RowFavoriteButton(record)
    if (showDownload) RowDownloadButton(record)
    if (record.pdfAttachments.isNotEmpty()) {
      RowHandoutButton(record) {
        if (panel != null) panel.item = StudyItem.Attachment(record) else showAttachment = true
      }
    }
    if (showAI) {
      if (aiReadyOnly) {
        val hasTranscript = remember(revision, record.id) { ai.hasTranscript(record.id) }
        val hasHandout = remember(revision, record.id) { ai.hasHandout(record.id) }
        if (hasTranscript) AiActionButton(AiKind.TRANSCRIPT, record, compact = true)
        if (hasHandout) AiActionButton(AiKind.HANDOUT, record, compact = true)
      } else if (apiKey.isNotBlank()) {
        AiActionButton(AiKind.TRANSCRIPT, record, compact = true)
        AiActionButton(AiKind.HANDOUT, record, compact = true)
      }
    }
   }
  }

  if (onDelete != null) {
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
      state = dismissState,
      onDismiss = { onDelete() },
      enableDismissFromStartToEnd = false,
      backgroundContent = {
        Box(
          Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
          contentAlignment = Alignment.CenterEnd,
        ) {
          Icon(
            Icons.Filled.Delete,
            contentDescription = "移除",
            tint = MaterialTheme.colorScheme.onErrorContainer,
          )
        }
      },
    ) {
      row()
    }
  } else {
    row()
  }

  if (showAttachment) {
    AttachmentViewer(
      title = record.title,
      attachments = record.pdfAttachments,
      onDismiss = { showAttachment = false },
    )
  }
}

/** Inline PDF-handout (講義) button. Filled once the PDF is saved offline,
 *  outline until then — the fill, not the tint, signals it on e-ink. Collects
 *  download state only here so rows without an attachment stay free of it. */
@Composable
private fun RowHandoutButton(record: EpisodeRecord, onOpen: () -> Unit) {
  val downloads = NerLanApp.instance.downloads
  val downloadRecords by downloads.records.collectAsState()
  val saved = remember(downloadRecords, record.id) {
    record.pdfAttachments.all { downloads.localAttachmentPath(it) != null }
  }
  IconButton(onClick = onOpen) {
    Icon(
      if (saved) Icons.Filled.Info else Icons.Outlined.InfoOutline,
      contentDescription = "講義",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(20.dp),
    )
  }
}

/** Inline favorite heart (podcast detail rows). Collects favorites only here so
 *  it's free for rows that don't show it. */
@Composable
private fun RowFavoriteButton(record: EpisodeRecord) {
  val favorites = NerLanApp.instance.favorites
  val favs by favorites.episodes.collectAsState()
  val isFav = favs.any { it.id == record.id }
  IconButton(onClick = { favorites.toggle(record) }) {
    Icon(
      if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
      contentDescription = "收藏",
      tint = MaterialTheme.colorScheme.error,
    )
  }
}

/** Inline download button mirroring EpisodeRow's three states. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowDownloadButton(record: EpisodeRecord) {
  val downloads = NerLanApp.instance.downloads
  val progressMap by downloads.progress.collectAsState()
  val downloadRecords by downloads.records.collectAsState()
  val isDownloaded = downloadRecords.any { it.id == record.id } || downloads.isDownloaded(record.id)
  val progress = progressMap[record.id]
  Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
    when {
      isDownloaded -> Icon(
        Icons.Filled.CheckCircle, contentDescription = "已下載",
        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp),
      )
      progress != null -> CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp))
      else -> IconButton(onClick = { downloads.download(record) }, enabled = record.audio != null) {
        Icon(Icons.Filled.ArrowDownward, contentDescription = "下載")
      }
    }
  }
}
