package com.example.nerlan.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where the listener stopped in each episode, so reopening one picks up instead
 * of restarting from zero. Plain JSON in filesDir (`playback-positions.json`),
 * matching the rest of the app's no-database persistence.
 *
 * Deliberately not synced to Drive: a resume offset is a per-device, per-moment
 * thing that goes stale the instant you press play somewhere else.
 */
class PlaybackPositionStore(filesDir: File) {
  @Serializable
  private data class Entry(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

  private val file = File(filesDir, "playback-positions.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()
  private val entries: MutableMap<String, Entry> =
    runCatching { json.decodeFromString<Map<String, Entry>>(file.readText()).toMutableMap() }
      .getOrElse { mutableMapOf() }

  /** Seconds to resume, or null when there's nothing worth resuming. */
  fun positionMs(episodeId: String): Long? = synchronized(lock) {
    entries[episodeId]?.positionMs?.takeIf { it > EDGE_MS }
  }

  /** 0..1 through the episode, for the widgets' resume bars. */
  fun progress(episodeId: String): Float? = synchronized(lock) {
    val e = entries[episodeId] ?: return null
    if (e.durationMs <= 0) return null
    (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f)
  }

  /**
   * Remember where playback is. A position at either edge — barely started, or
   * essentially finished — clears the entry instead of storing one, so "play"
   * never resumes into the last breath of a lesson.
   */
  fun record(episodeId: String, positionMs: Long, durationMs: Long) {
    val nearEnd = durationMs > 0 && positionMs >= durationMs - EDGE_MS
    if (positionMs <= EDGE_MS || nearEnd) {
      clear(episodeId)
      return
    }
    synchronized(lock) {
      entries[episodeId] = Entry(positionMs, durationMs, System.currentTimeMillis())
      prune()
      persist()
    }
  }

  /** Forget an episode — it finished, or was deleted. */
  fun clear(episodeId: String) {
    synchronized(lock) {
      if (entries.remove(episodeId) == null) return
      persist()
    }
  }

  private fun prune() {
    if (entries.size <= MAX_ENTRIES) return
    entries.entries.sortedBy { it.value.updatedAt }
      .take(entries.size - MAX_ENTRIES)
      .forEach { entries.remove(it.key) }
  }

  private fun persist() {
    runCatching { file.writeText(json.encodeToString<Map<String, Entry>>(entries)) }
  }

  private companion object {
    /** Under this far in it hasn't really started; within this of the end it's done. */
    const val EDGE_MS = 15_000L
    const val MAX_ENTRIES = 500
  }
}
