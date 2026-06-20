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
import com.example.nerlan.player.ShadowRecorder
import kotlinx.coroutines.flow.collectLatest
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.drop

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
 * AIContentStore. Translate-mode is remembered across screens; on open it's
 * reapplied only when a matching translation is already cached, so opening one
 * never silently starts a paid translation.
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
  startShadowing: Boolean = false,
) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      TranscriptContent(record, text, onDismiss, cues = cues, startShadowing = startShadowing)
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
  startShadowing: Boolean = false,
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

  val translationJob = translationJobs[episodeId]
  val translating = translationJob is AIContentStore.JobState.Running
  // Cached translation for the current target language (null if absent / stale).
  val translation = remember(episodeId, revision, translationLanguage) {
    ai.translation(episodeId)?.takeIf { it.language == translationLanguage }?.sentences
  }

  // View mode: 0 = original, 1 = original + translation, 2 = translation only.
  // On open, honor the remembered preference only when a matching translation is
  // already cached; otherwise show the original and never auto-trigger generation.
  val translatePref by settings.transcriptTranslateMode.collectAsState()
  var translateMode by remember(episodeId) {
    mutableStateOf(if (translatePref != 0 && translation != null) translatePref else 0)
  }
  var pendingMode by remember(episodeId) { mutableStateOf<Int?>(null) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  // Apply a pending mode switch once its translation finishes, or surface failure.
  LaunchedEffect(translationJob, translation) {
    val pm = pendingMode ?: return@LaunchedEffect
    when {
      translationJob is AIContentStore.JobState.Failed -> {
        errorMessage = translationJob.message; pendingMode = null
      }
      translationJob == null && translation != null -> {
        translateMode = pm; settings.setTranscriptTranslateMode(pm); pendingMode = null
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

  // --- Shadowing state ------------------------------------------------------
  val isCurrentEpisode = current?.id == episodeId
  val shadowingAvailable = isCurrentEpisode && !cues.isNullOrEmpty()
  var shadowing by remember(episodeId) { mutableStateOf(false) }
  var shadowIndex by remember(episodeId) { mutableStateOf<Int?>(null) }
  val loopCount by settings.shadowLoopCount.collectAsState()
  val recording by ShadowRecorder.isRecording.collectAsState()
  val playingMine by ShadowRecorder.isPlaying.collectAsState()
  val loopRegion by PlayerManager.loopRegion.collectAsState()
  // Highlight the loop target while shadowing (stable across the loop's lead-in),
  // else the sentence being spoken.
  val highlightIndex = if (shadowing) (shadowIndex ?: -1) else activeIndex

  val context = LocalContext.current
  var micGranted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  var pendingRecordKey by remember { mutableStateOf<String?>(null) }
  var showMicDenied by remember { mutableStateOf(false) }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    micGranted = granted
    if (granted) pendingRecordKey?.let { ShadowRecorder.startRecording(it) } else showMicDenied = true
    pendingRecordKey = null
  }

  fun recordKey(i: Int) = "$episodeId-$i"
  // A sentence's span: its cue start to the next cue's start (duration for the last).
  fun regionFor(i: Int): Pair<Long, Long>? {
    val c = cues ?: return null
    if (i < 0 || i >= c.size) return null
    val startMs = (c[i].start * 1000).toLong()
    val endMs = if (i + 1 < c.size) (c[i + 1].start * 1000).toLong() else PlayerManager.durationMs.value
    return startMs to endMs
  }
  // Stops any in-progress recording / playback first, so stepping to another
  // sentence (or replaying) interrupts a take and plays the segment.
  fun loopSentence(i: Int?) {
    val idx = i ?: return
    val r = regionFor(idx) ?: return
    ShadowRecorder.reset()
    shadowIndex = idx
    PlayerManager.loopSegment(r.first, r.second, if (loopCount == 0) null else loopCount)
  }
  fun requestRecord(key: String) {
    if (micGranted) ShadowRecorder.startRecording(key)
    else { pendingRecordKey = key; micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
  }
  fun setShadowing(on: Boolean) {
    shadowing = on
    if (on) loopSentence(if (activeIndex >= 0) activeIndex else 0)
    else { shadowIndex = null; PlayerManager.clearLoop(); ShadowRecorder.reset() }
  }

  // Stop the loop / recorder when this transcript leaves composition while shadowing.
  val shadowingFlag by rememberUpdatedState(shadowing)
  DisposableEffect(Unit) {
    onDispose { if (shadowingFlag) { PlayerManager.clearLoop(); ShadowRecorder.reset() } }
  }

  // Auto-start shadowing when opened via the 跟讀 entry.
  var didAutoStart by remember(episodeId) { mutableStateOf(false) }
  LaunchedEffect(startShadowing, shadowingAvailable) {
    if (startShadowing && shadowingAvailable && !shadowing && !didAutoStart) {
      didAutoStart = true
      setShadowing(true)
    }
  }

  // After a finite loop finishes its repeats, auto-start recording the learner's
  // turn. drop(1) skips the value StateFlow emits on subscription, so only a real
  // completion (not entering the view) triggers it. Infinite loops never finish.
  val currentShadowIndex by rememberUpdatedState(shadowIndex)
  LaunchedEffect(shadowing) {
    if (!shadowing) return@LaunchedEffect
    PlayerManager.loopFinishedSignal.drop(1).collect {
      if (shadowingAvailable && !ShadowRecorder.isRecording.value && !ShadowRecorder.isPlaying.value) {
        currentShadowIndex?.let { requestRecord(recordKey(it)) }
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
  if (scrollAnimated && !shadowing) {
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
    // e-ink instant centering, and (in any scroll mode) centering on the looped
    // sentence while shadowing.
    val scrollTarget = if (shadowing) (shadowIndex ?: -1) else activeIndex
    LaunchedEffect(scrollTarget) {
      if (scrollTarget < 0) return@LaunchedEffect
      fun centerOffset(): Int? {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == scrollTarget } ?: return null
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        return -(viewport - item.size) / 2
      }
      val off = centerOffset()
      if (off != null) {
        listState.scrollToItem(scrollTarget, off)
      } else {
        listState.scrollToItem(scrollTarget)
        centerOffset()?.let { listState.scrollToItem(scrollTarget, it) }
      }
    }
  }

  fun cycleTranslate() {
    val next = (translateMode + 1) % 3
    when {
      next == 0 -> { translateMode = 0; settings.setTranscriptTranslateMode(0) }
      translation != null -> { translateMode = next; settings.setTranscriptTranslateMode(next) }
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
      if (shadowingAvailable) {
        IconButton(onClick = { setShadowing(!shadowing) }) {
          Icon(
            Icons.Filled.RecordVoiceOver,
            contentDescription = "跟讀",
            tint = if (shadowing) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
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
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text("沒有逐字稿內容", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else {
      SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
          items(sentences.size) { i ->
            val active = i == highlightIndex
            val translated = translation?.getOrNull(i)
            // In translation-only mode, fall back to the original when a line has
            // no translation, so the row is never blank.
            val showOriginal = translateMode != 2 || translated.isNullOrEmpty()
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .then(if (shadowing) Modifier.clickable { loopSentence(i) } else Modifier)
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

    // Sentence-grained transport + recording, shown while shadowing.
    if (shadowing && shadowingAvailable) {
      val count = cues?.size ?: 0
      var countMenuOpen by remember { mutableStateOf(false) }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
      ) {
        IconButton(
          onClick = { loopSentence((shadowIndex ?: 0) - 1) },
          enabled = (shadowIndex ?: 0) > 0,
        ) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一句") }

        IconButton(onClick = {
          if (loopRegion != null) { PlayerManager.clearLoop(); PlayerManager.pause() }
          else loopSentence(shadowIndex ?: if (activeIndex >= 0) activeIndex else 0)
        }) {
          Icon(
            if (loopRegion != null) Icons.Filled.Pause else Icons.Filled.Replay,
            contentDescription = if (loopRegion != null) "停止重複" else "重複這句",
          )
        }

        IconButton(
          onClick = { loopSentence((shadowIndex ?: -1) + 1) },
          enabled = shadowIndex != null && (shadowIndex!! + 1) < count,
        ) { Icon(Icons.Filled.SkipNext, contentDescription = "下一句") }

        Spacer(Modifier.weight(1f))

        // Record your read, then play it back to compare with the original.
        IconButton(onClick = {
          if (recording) ShadowRecorder.stopRecording(thenPlay = true)
          else shadowIndex?.let { requestRecord(recordKey(it)) }
        }) {
          Icon(
            if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (recording) "停止錄音" else "錄音",
            tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          )
        }

        val mineKey = shadowIndex?.let { recordKey(it) }
        // `recording` flips when a take finishes, recomposing this and re-checking.
        val canPlayMine = mineKey != null && ShadowRecorder.hasRecording(mineKey)
        IconButton(
          onClick = {
            if (playingMine) ShadowRecorder.stopPlayback()
            else mineKey?.let { ShadowRecorder.playRecording(it) }
          },
          enabled = playingMine || canPlayMine,
        ) {
          Icon(
            if (playingMine) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = "播放我的錄音",
          )
        }

        Spacer(Modifier.weight(1f))

        Box {
          TextButton(onClick = { countMenuOpen = true }) {
            Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
              if (loopCount == 0) "∞" else "×$loopCount",
              modifier = Modifier.padding(start = 4.dp),
            )
          }
          DropdownMenu(expanded = countMenuOpen, onDismissRequest = { countMenuOpen = false }) {
            listOf(1, 2, 3, 5, 0).forEach { n ->
              DropdownMenuItem(
                text = { Text((if (n == 0) "無限" else "$n 次") + if (n == loopCount) " ✓" else "") },
                onClick = {
                  settings.setShadowLoopCount(n)
                  countMenuOpen = false
                  if (shadowing) loopSentence(shadowIndex)
                },
              )
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

  if (showMicDenied) {
    AlertDialog(
      onDismissRequest = { showMicDenied = false },
      title = { Text("無法使用麥克風") },
      text = { Text("請到系統設定開啟 NerLan 的麥克風權限，才能錄下你的朗讀。") },
      confirmButton = { TextButton(onClick = { showMicDenied = false }) { Text("好") } },
    )
  }
}
