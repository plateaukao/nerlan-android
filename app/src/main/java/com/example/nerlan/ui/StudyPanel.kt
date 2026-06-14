package com.example.nerlan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.EpisodeRecord

/** What the large-screen detail (right) panel is showing. */
sealed interface StudyItem {
  val record: EpisodeRecord

  data class Transcript(override val record: EpisodeRecord) : StudyItem
  data class Handout(override val record: EpisodeRecord) : StudyItem
  data class Attachment(override val record: EpisodeRecord) : StudyItem
}

/** Holds the open study artifact for the two-pane layout. */
class StudyPanelController {
  var item by mutableStateOf<StudyItem?>(null)
}

/**
 * Non-null only in two-pane (large-screen) mode. When present, the transcript /
 * handout / 講義 buttons route their content into the panel; when null (phone)
 * they fall back to full-screen dialogs. The Android analog of iOS `StudyPanel`.
 */
val LocalStudyPanel = staticCompositionLocalOf<StudyPanelController?> { null }

/** Default panel content for a newly-playing episode: PDF handout, else AI
 *  handout, else transcript, else nothing. */
fun defaultStudyItem(record: EpisodeRecord): StudyItem? {
  val ai = NerLanApp.instance.ai
  return when {
    record.pdfAttachments.isNotEmpty() -> StudyItem.Attachment(record)
    ai.hasHandout(record.id) -> StudyItem.Handout(record)
    ai.hasTranscript(record.id) -> StudyItem.Transcript(record)
    else -> null
  }
}

/** The right pane: renders the open artifact (reusing the dialog bodies) or a
 *  placeholder. The close button clears the panel. */
@Composable
fun StudyDetailPanel(controller: StudyPanelController) {
  val ai = NerLanApp.instance.ai
  val revision by ai.revision.collectAsState()
  val close = { controller.item = null }

  when (val item = controller.item) {
    is StudyItem.Transcript -> {
      val text = remember(item.record.id, revision) { ai.transcriptText(item.record.id).orEmpty() }
      TranscriptContent(item.record.title, text, close)
    }
    is StudyItem.Handout -> {
      val html = remember(item.record.id, revision) { ai.handoutHtml(item.record.id).orEmpty() }
      HandoutContent(item.record.title, html, close)
    }
    is StudyItem.Attachment ->
      AttachmentContent(item.record.title, item.record.pdfAttachments, close)
    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "逐字稿與講義\n播放單集時，這裡會顯示講義、AI 講義或逐字稿。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}
