package com.example.nerlan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Client for 國立教育廣播電台 Channel+ (https://channelplus.ner.gov.tw).
 * Serves the full on-demand archive of every program with direct MP3 audio.
 */
object ChannelPlusApi {
  const val BASE = "https://channelplus.ner.gov.tw/api/v1"

  /** Language-learning programs are programType=2. */
  private const val LANGUAGE_PROGRAM_TYPE = 2

  val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  class ApiException(message: String) : Exception(message)

  fun audioUrl(voiceRef: String?): String? =
    voiceRef?.takeIf { it.isNotEmpty() }?.let { "$BASE/audio?key=$it" }

  fun imageUrl(imageRef: String?): String? =
    imageRef?.takeIf { it.isNotEmpty() }?.let { "$BASE/image?key=$it" }

  /** Episode attachments (PDF handouts etc.) are served from `file?key=`. */
  fun fileUrl(attachmentKey: String?): String? =
    attachmentKey?.takeIf { it.isNotEmpty() }?.let { "$BASE/file?key=$it" }

  private suspend fun fetch(pathAndQuery: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url("$BASE/$pathAndQuery")
      .header("Accept", "application/json")
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw ApiException("HTTP ${response.code}")
      response.body?.string() ?: throw ApiException("empty body")
    }
  }

  /** All language-learning programs (currently ~96, single page). */
  suspend fun programs(): List<Program> {
    val body = fetch("programs?page=1&size=500&programType=$LANGUAGE_PROGRAM_TYPE")
    val resp = json.decodeFromString<ApiResponse<List<Program>>>(body)
    if (!resp.success) throw ApiException(resp.rtnMsg ?: "programs failed")
    return resp.data ?: emptyList()
  }

  data class EpisodePage(val episodes: List<Episode>, val totalPages: Int, val totalCount: Int)

  /**
   * One page of a program's episode archive, oldest first
   * (ascending suits sequential language courses).
   */
  suspend fun episodes(programId: String, page: Int, pageSize: Int = 50): EpisodePage {
    val body = fetch(
      "programs/episodes/$programId?page=$page&size=$pageSize&sortOrder=ASC&sortField=episode_number"
    )
    val resp = json.decodeFromString<ApiResponse<List<Episode>>>(body)
    if (!resp.success) throw ApiException(resp.rtnMsg ?: "episodes failed")
    return EpisodePage(
      episodes = resp.data ?: emptyList(),
      totalPages = resp.pagination?.totalPages ?: 1,
      totalCount = resp.pagination?.totalCount ?: 0,
    )
  }
}
