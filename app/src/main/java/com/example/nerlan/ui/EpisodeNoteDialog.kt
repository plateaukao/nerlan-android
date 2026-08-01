package com.example.nerlan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nerlan.NerLanApp

/**
 * Dialog for writing or editing the user's note on an episode (see
 * [com.example.nerlan.data.EpisodeNotesStore]). Saving empty text clears the
 * note; a stored note also gets an explicit delete button.
 */
@Composable
fun EpisodeNoteDialog(episodeId: String, episodeTitle: String, onDismiss: () -> Unit) {
  val notes = NerLanApp.instance.notes
  val existing = remember { notes.notes.value[episodeId] ?: "" }
  var text by remember { mutableStateOf(existing) }
  val focusRequester = remember { FocusRequester() }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(episodeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    text = {
      Column {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("寫下這一集的內容…") },
          minLines = 3,
          modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        Text(
          "註記會顯示在單集列表，方便辨認每一集的內容。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
      }
    },
    confirmButton = {
      TextButton(onClick = {
        notes.setNote(episodeId, text)
        onDismiss()
      }) { Text("儲存") }
    },
    dismissButton = {
      Row {
        if (existing.isNotEmpty()) {
          TextButton(onClick = {
            notes.setNote(episodeId, "")
            onDismiss()
          }) { Text("刪除註記", color = MaterialTheme.colorScheme.error) }
        }
        TextButton(onClick = onDismiss) { Text("取消") }
      }
    },
  )
}

/** The note line shown under an episode row's subtitle when a note exists.
 *  Shared by the program episode list ([EpisodeRow]) and [RecordRow]. */
@Composable
fun EpisodeNoteText(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(
      Icons.AutoMirrored.Filled.StickyNote2,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.tertiary,
      modifier = Modifier.size(14.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.tertiary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
