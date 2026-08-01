package com.example.nerlan.data

import com.example.nerlan.NerLanApp
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * User-written notes on episodes, keyed by episode id. Course episodes are
 * often titled just "EP12", so a note is how the user records what's actually
 * inside; the episode lists show it under the subtitle. Persisted as JSON
 * (`episode-notes.json`, mirroring iOS) and union-merged through Drive sync
 * like favorites.
 */
class EpisodeNotesStore(filesDir: File) {
  private val file = File(filesDir, "episode-notes.json")
  private val json = Json { ignoreUnknownKeys = true }

  private val _notes = MutableStateFlow(load() ?: emptyMap())
  val notes: StateFlow<Map<String, String>> = _notes

  private fun load(): Map<String, String>? =
    runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }.getOrNull()

  /** Re-read the file into the flow; used after a Drive pull merges changes. */
  fun reload() {
    _notes.value = load() ?: emptyMap()
  }

  /** Save (or clear, when the trimmed text is empty) the note for an episode. */
  fun setNote(episodeId: String, text: String) {
    val trimmed = text.trim()
    val current = _notes.value
    val updated =
      if (trimmed.isEmpty()) {
        if (episodeId !in current) return
        current - episodeId
      } else {
        if (current[episodeId] == trimmed) return
        current + (episodeId to trimmed)
      }
    _notes.value = updated
    file.writeText(json.encodeToString(updated))
    NerLanApp.instance.drive.requestSync()
  }
}
