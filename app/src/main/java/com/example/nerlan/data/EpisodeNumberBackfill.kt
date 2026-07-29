package com.example.nerlan.data

import com.example.nerlan.NerLanApp

/**
 * One-time-per-record migration, mirroring the iOS EpisodeNumberBackfill:
 * records persisted before [EpisodeRecord.episodeNo] existed decode with it
 * null, which leaves the Downloads/AI lists sorted by release date —
 * meaningless for bulk-published courses that share one date. At launch this
 * fills the number in from the on-disk catalog cache when the program's episode
 * pages are there, else with an API sweep per affected program, then persists —
 * so each record is fixed exactly once.
 */
object EpisodeNumberBackfill {
  suspend fun run() {
    val app = NerLanApp.instance
    // NER records missing a number. Podcasts (audioLocale != null) have no
    // episode numbers and no NER program to ask.
    val stores: List<List<EpisodeRecord>> = listOf(
      app.downloads.records.value,
      app.downloads.cachedRecords.value,
      app.favorites.episodes.value,
      app.ai.records.value.values.toList(),
    )
    val needed = stores.flatten().filter { it.episodeNo == null && it.audioLocale == null }
    if (needed.isEmpty()) return

    val numbers = HashMap<String, Int>()
    fun harvest(episodes: List<Episode>) {
      episodes.forEach { ep -> ep.episodeNumber?.let { numbers[ep.episodeId] = it } }
    }

    // Cached catalog pages first — free and offline.
    for (programId in needed.map { it.programId }.toSet()) {
      app.catalog.loadEpisodes(programId)?.episodes?.let(::harvest)
    }

    // Fetch programs that still have unresolved ids. Missing numbers cost at
    // most a page sweep per program per launch; once resolved and persisted the
    // whole backfill short-circuits above.
    val unresolved = needed.filter { numbers[it.id] == null }.map { it.programId }.toSet()
    for (programId in unresolved) {
      var page = 1
      var totalPages = 1
      while (page <= totalPages) {
        val result = runCatching { ChannelPlusApi.episodes(programId, page, pageSize = 500) }
          .getOrNull() ?: break
        harvest(result.episodes)
        totalPages = result.totalPages
        page += 1
      }
    }

    if (numbers.isEmpty()) return
    app.downloads.applyEpisodeNumbers(numbers)
    app.favorites.applyEpisodeNumbers(numbers)
    app.ai.applyEpisodeNumbers(numbers)
  }
}

/** Fill null `episodeNo`s from the map, or null when nothing changed — so
 *  callers persist only then. Shared by the stores' applyEpisodeNumbers. */
internal fun fillEpisodeNumbers(
  numbers: Map<String, Int>,
  records: List<EpisodeRecord>,
): List<EpisodeRecord>? {
  var changed = false
  val out = records.map { r ->
    if (r.episodeNo != null) r
    else numbers[r.id]?.let { changed = true; r.copy(episodeNo = it) } ?: r
  }
  return if (changed) out else null
}
