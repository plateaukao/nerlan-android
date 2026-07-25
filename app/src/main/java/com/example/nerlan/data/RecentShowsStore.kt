package com.example.nerlan.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The shows you've actually been listening to, newest first — one entry per
 * program or podcast, remembering which episode it left off on.
 *
 * Kept separate from [FavoritesStore.programs] on purpose: you can work through a
 * course for weeks without ever tapping its heart, and the useful widget is "put
 * me back where I was", not "shows I bookmarked". Persisted as
 * `recent-shows.json`, like every other store here.
 */
class RecentShowsStore(filesDir: File) {
  @Serializable
  data class Entry(
    val id: String,            // programId, or the feed URL for a podcast
    val name: String,
    val language: String,
    val coverUrl: String? = null,
    val isPodcast: Boolean = false,
    val lastEpisodeId: String,
    val lastEpisodeTitle: String,
    val playedAt: Long,
  )

  private val file = File(filesDir, "recent-shows.json")
  private val json = Json { ignoreUnknownKeys = true }

  private val _shows = MutableStateFlow(
    runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrElse { emptyList() }
  )

  /** Newest first. */
  val shows: StateFlow<List<Entry>> = _shows

  fun entry(id: String): Entry? = _shows.value.firstOrNull { it.id == id }

  /**
   * Called whenever an episode starts playing — from PlayerManager, the one
   * chokepoint every playback path goes through, so nothing can play without
   * being recorded.
   */
  fun note(record: EpisodeRecord) {
    val entry = Entry(
      id = record.programId,
      name = record.programName,
      language = record.language,
      coverUrl = record.coverUrl,
      // Only podcast records carry an audio locale.
      isPodcast = record.audioLocale != null,
      lastEpisodeId = record.id,
      lastEpisodeTitle = record.title,
      playedAt = System.currentTimeMillis(),
    )
    val existing = _shows.value.firstOrNull { it.id == entry.id }
    // Re-noting the same episode of the same show on every transition would
    // rewrite the file for nothing; only the timestamp would move.
    if (existing?.lastEpisodeId == entry.lastEpisodeId) return
    _shows.value = (listOf(entry) + _shows.value.filterNot { it.id == entry.id }).take(LIMIT)
    runCatching { file.writeText(json.encodeToString(_shows.value)) }
  }

  private companion object {
    /** Enough to fill the largest widget several times over; beyond that it's
     *  history, not "recent". */
    const val LIMIT = 12
  }
}
