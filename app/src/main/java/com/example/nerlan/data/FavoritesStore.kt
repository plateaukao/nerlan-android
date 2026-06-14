package com.example.nerlan.data

import com.example.nerlan.NerLanApp
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Favorited episodes and programs, persisted as JSON files. */
class FavoritesStore(filesDir: File) {
  private val episodesFile = File(filesDir, "favorites.json")
  private val programsFile = File(filesDir, "favorite-programs.json")
  private val json = Json { ignoreUnknownKeys = true }

  private val _episodes = MutableStateFlow(load<List<EpisodeRecord>>(episodesFile) ?: emptyList())
  val episodes: StateFlow<List<EpisodeRecord>> = _episodes

  private val _programs = MutableStateFlow(load<List<Program>>(programsFile) ?: emptyList())
  val programs: StateFlow<List<Program>> = _programs

  private inline fun <reified T> load(file: File): T? =
    runCatching { json.decodeFromString<T>(file.readText()) }.getOrNull()

  /** Re-read both files into the flows; used after a Drive pull merges changes. */
  fun reload() {
    _episodes.value = load<List<EpisodeRecord>>(episodesFile) ?: emptyList()
    _programs.value = load<List<Program>>(programsFile) ?: emptyList()
  }

  // Episodes

  fun isFavorite(episodeId: String) = _episodes.value.any { it.id == episodeId }

  fun toggle(record: EpisodeRecord) {
    _episodes.value =
      if (isFavorite(record.id)) _episodes.value.filterNot { it.id == record.id }
      else _episodes.value + record
    episodesFile.writeText(json.encodeToString(_episodes.value))
    NerLanApp.instance.drive.requestSync()
  }

  // Programs

  fun isFavoriteProgram(programId: String) = _programs.value.any { it.programId == programId }

  fun toggle(program: Program) {
    _programs.value =
      if (isFavoriteProgram(program.programId)) _programs.value.filterNot { it.programId == program.programId }
      else _programs.value + program
    programsFile.writeText(json.encodeToString(_programs.value))
    NerLanApp.instance.drive.requestSync()
  }
}
