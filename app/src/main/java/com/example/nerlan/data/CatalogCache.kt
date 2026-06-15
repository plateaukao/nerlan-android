package com.example.nerlan.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk cache of the Channel+ catalog — the program list and each program's
 * loaded episode pages — so the browse UI renders instantly (even offline) and
 * only hits the network on a cache miss or an explicit pull-to-refresh. The
 * catalog rarely changes (sequential courses gain episodes only at the end), so
 * cached data is treated as authoritative until the user refreshes.
 *
 * Files live in cacheDir, not filesDir: this is derived, re-fetchable data, so the
 * OS may evict it under storage pressure (the next launch simply re-fetches). User
 * data — favorites, downloads — stays in filesDir as before.
 */
class CatalogCache(cacheDir: File) {
  private val dir = File(cacheDir, "catalog").apply { mkdirs() }
  private val json = Json { ignoreUnknownKeys = true }

  // Programs

  private val programsFile = File(dir, "programs.json")

  suspend fun loadPrograms(): List<Program>? = withContext(Dispatchers.IO) {
    runCatching { json.decodeFromString<List<Program>>(programsFile.readText()) }.getOrNull()
  }

  suspend fun savePrograms(programs: List<Program>) = withContext(Dispatchers.IO) {
    runCatching { programsFile.writeText(json.encodeToString(programs)) }
    Unit
  }

  // Episode pages

  /**
   * The episodes loaded so far for one program plus its pagination cursor, so a
   * reopen restores the list and infinite scroll resumes from where it left off
   * instead of re-fetching the pages already seen.
   */
  @Serializable
  data class EpisodePageCache(
    val episodes: List<Episode>,
    val page: Int,
    val totalPages: Int,
    val totalCount: Int,
  )

  private fun episodesFile(programId: String) = File(dir, "episodes-$programId.json")

  suspend fun loadEpisodes(programId: String): EpisodePageCache? = withContext(Dispatchers.IO) {
    runCatching {
      json.decodeFromString<EpisodePageCache>(episodesFile(programId).readText())
    }.getOrNull()
  }

  suspend fun saveEpisodes(programId: String, page: EpisodePageCache) = withContext(Dispatchers.IO) {
    runCatching { episodesFile(programId).writeText(json.encodeToString(page)) }
    Unit
  }
}
