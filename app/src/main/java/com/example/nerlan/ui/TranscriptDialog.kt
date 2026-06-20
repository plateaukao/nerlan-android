package com.example.nerlan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.AIContentStore
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.data.SettingsStore
import com.example.nerlan.data.TranscriptCue
import com.example.nerlan.player.PlayerManager
import kotlinx.coroutines.flow.collectLatest

/**
 * Read-only transcript viewer shown as a full-screen dialog over the player. The
 * stored transcript has one sentence per line (segmented by the chat model),
 * rendered here as a sentence-by-sentence list in a LazyColumn.
 *
 * Two top-bar controls tune the reading: a font-size button loops three sizes
 * (remembered across transcript screens in SettingsStore), and a translate button
 * loops the view through three modes — original, original plus per-sentence
 * translation, and translation only — translating into the target language set in
 * Settings. The translation is generated on demand, cached, and synced by
 * AIContentStore. Translate-mode resets to original each time a transcript opens.
 *
 * When the transcript was produced with timestamps ([cues]) and belongs to the
 * episode currently playing, the spoken sentence is highlighted and kept on screen.
 */
@Composable
fun TranscriptDialog(
  record: EpisodeRecord,
  text: String,
  onDismiss: () -> Unit,
  cues: List<TranscriptCue>? = null,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      TranscriptContent(record, text, onDismiss, cues = cues)
    }
  }
}

/** The transcript body, shared by the phone dialog and the large-screen panel. */
@Composable
fun TranscriptContent(
  record: EpisodeRecord,
  text: String,
  onClose: () -> Unit,
  leading: @Composable () -> Unit = {},
  cues: List<TranscriptCue>? = null,
) {
  val ai = NerLanApp.instance.ai
  val settings = NerLanApp.instance.settings
  val episodeId = record.id

  // Keep the screen awake while the transcript is on screen (caption mode in the
  // player, the standalone dialog, or the large-screen panel) so it doesn't sleep
  // mid-read. The flag is set on the host window's view and cleared automatically
  // the moment this view leaves composition.
  val view = LocalView.current
  DisposableEffect(Unit) {
    view.keepScreenOn = true
    onDispose { view.keepScreenOn = false }
  }

  val sentences = remember(text, cues) {
    if (!cues.isNullOrEmpty()) cues.map { it.text }
    else text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
  }
  val clipboard = LocalClipboardManager.current
  val listState = rememberLazyListState()

  val fontScale by settings.transcriptFontScale.collectAsState()
  val translationLanguage by settings.translationLanguage.collectAsState()
  val scrollAnimated by settings.transcriptScrollAnimated.collectAsState()
  val translationJobs by ai.translationJobs.collectAsState()
  val revision by ai.revision.collectAsState()

  val bodySize = SettingsStore.TRANSCRIPT_FONT_SIZES[fontScale.coerceIn(0, SettingsStore.TRANSCRIPT_FONT_SIZES.size - 1)]

  // View mode: 0 = original, 1 = original + translation, 2 = translation only.
  // Resets to original each time this transcript opens.
  var translateMode by remember(episodeId) { mutableStateOf(0) }
  var pendingMode by remember(episodeId) { mutableStateOf<Int?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  val translationJob = translationJobs[episodeId]
  val translating = translationJob is AIContentStore.JobState.Running
  // Cached translation for the current target language (null if absent / stale).
  val translation = remember(episodeId, revision, translationLanguage) {
    ai.translation(episodeId)?.takeIf { it.language == translationLanguage }?.sentences
  }

  // Apply a pending mode switch once its translation finishes, or surface failure.
  LaunchedEffect(translationJob, translation) {
    val pm = pendingMode ?: return@LaunchedEffect
    when {
      translationJob is AIContentStore.JobState.Failed -> {
        errorMessage = translationJob.message; pendingMode = null
      }
      translationJob == null && translation != null -> {
        translateMode = pm; pendingMode = null
      }
    }
  }

  val current by PlayerManager.current.collectAsState()
  val positionMs by PlayerManager.positionMs.collectAsState()

  // Sentence currently being spoken (index), or -1 when this isn't the playing
  // episode / there are no cues. derivedStateOf recomputes only when the index
  // actually changes, so the rows don't churn on every 0.5s position tick.
  val activeIndex by remember(cues, episodeId) {
    derivedStateOf {
      val c = cues
      if (c.isNullOrEmpty() || current?.id != episodeId) {
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

  // Keep the spoken sentence centered in the viewport (LazyColumn has no center
  // anchor, so centering means a scroll offset of half the leftover space; cf.
  // iOS `scrollTo(anchor: .center)`). Two modes, chosen by scrollAnimated:
  //
  //  • Animated (default, normal phones) — a teleprompter-style *continuous*
  //    drift: every 0.5s position tick re-targets so the active line glides
  //    toward center over the sentence's own duration, with no jump at the
  //    sentence boundary. collectLatest cancels the in-flight animation when the
  //    next tick arrives, so the motion keeps re-aiming smoothly.
  //  • Off (e-ink) — an instant jump that centers each sentence once, when it
  //    becomes active. e-ink disables animations system-wide anyway, and a
  //    continuous scroll would smear/ghost.
  if (scrollAnimated) {
    LaunchedEffect(cues, episodeId) {
      val c = cues
      if (c.isNullOrEmpty()) return@LaunchedEffect
      PlayerManager.positionMs.collectLatest { pos ->
        if (PlayerManager.current.value?.id != episodeId) return@collectLatest
        val t = pos / 1000.0 + 0.05
        // Last cue whose start is at or before now.
        var lo = 0; var hi = c.size - 1; var i = -1
        while (lo <= hi) {
          val mid = (lo + hi) / 2
          if (c[mid].start <= t) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i < 0) return@collectLatest
        val info = listState.layoutInfo
        val cur = info.visibleItemsInfo.firstOrNull { it.index == i }
        if (cur == null) {
          // Off-screen (e.g. after a seek): snap it roughly to center; the next
          // tick drifts precisely once its height is measured.
          listState.scrollToItem(i, -(info.viewportEndOffset - info.viewportStartOffset) / 2)
          return@collectLatest
        }
        val vpCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val curCenter = cur.offset + cur.size / 2f
        // Interpolate the anchor from this sentence's center toward the next as
        // playback advances through the sentence, so the scroll is continuous.
        val next = info.visibleItemsInfo.firstOrNull { it.index == i + 1 }
        val anchor = if (next != null && i + 1 < c.size) {
          val span = c[i + 1].start - c[i].start
          val p = if (span > 0) ((t - c[i].start) / span).coerceIn(0.0, 1.0).toFloat() else 0f
          val nextCenter = next.offset + next.size / 2f
          curCenter + p * (nextCenter - curCenter)
        } else {
          curCenter
        }
        val delta = anchor - vpCenter
        if (kotlin.math.abs(delta) > 1f) {
          listState.animateScrollBy(delta, tween(durationMillis = 500, easing = LinearEasing))
        }
      }
    }
  } else {
    LaunchedEffect(activeIndex) {
      if (activeIndex < 0) return@LaunchedEffect
      fun centerOffset(): Int? {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == activeIndex } ?: return null
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        return -(viewport - item.size) / 2
      }
      val off = centerOffset()
      if (off != null) {
        listState.scrollToItem(activeIndex, off)
      } else {
        listState.scrollToItem(activeIndex)
        centerOffset()?.let { listState.scrollToItem(activeIndex, it) }
      }
    }
  }

  fun cycleTranslate() {
    val next = (translateMode + 1) % 3
    when {
      next == 0 -> translateMode = 0
      translation != null -> translateMode = next
      settings.apiKey.value.isBlank() -> errorMessage = "尚未設定 OpenAI API 金鑰，無法翻譯。"
      else -> { pendingMode = next; ai.translate(record) }
    }
  }

  Column(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
      leading()
      IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
      Spacer(Modifier.weight(1f))
      if (sentences.isNotEmpty()) {
        IconButton(onClick = { settings.setTranscriptFontScale((fontScale + 1) % SettingsStore.TRANSCRIPT_FONT_SIZES.size) }) {
          Icon(
            Icons.Filled.FormatSize,
            contentDescription = "字體大小",
            tint = if (fontScale > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (translating) {
          Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          }
        } else {
          IconButton(onClick = { cycleTranslate() }) {
            Icon(
              Icons.Filled.Translate,
              contentDescription = "翻譯",
              tint = if (translateMode > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
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
            val translated = translation?.getOrNull(i)
            // In translation-only mode, fall back to the original when a line has
            // no translation, so the row is never blank.
            val showOriginal = translateMode != 2 || translated.isNullOrEmpty()
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
              if (showOriginal) {
                Text(
                  sentences[i],
                  fontSize = bodySize.sp,
                  fontWeight = if (active) FontWeight.SemiBold else null,
                  color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
              }
              if (translateMode != 0 && !translated.isNullOrEmpty()) {
                Text(
                  translated,
                  fontSize = (if (translateMode == 2) bodySize else bodySize - 2).sp,
                  fontWeight = if (active && translateMode == 2) FontWeight.SemiBold else null,
                  color = if (translateMode == 2) {
                    if (active) MaterialTheme.colorScheme.primary else Color.Unspecified
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
                  modifier = Modifier.padding(top = if (showOriginal) 2.dp else 0.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  errorMessage?.let { msg ->
    AlertDialog(
      onDismissRequest = { errorMessage = null },
      title = { Text("翻譯失敗") },
      text = { Text(msg) },
      confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("好") } },
    )
  }
}
