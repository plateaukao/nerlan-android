package com.example.nerlan.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Turns a pasted podcast URL into an RSS feed URL, and fetches feed bytes.
 * Mirrors the iOS `PodcastAPI`. Three input shapes:
 *   - an Apple Podcasts page (`podcasts.apple.com/.../idNNN`) — resolved to its
 *     RSS `feedUrl` via the public iTunes Lookup API,
 *   - an `apple.co` short link — its redirect is followed first,
 *   - anything else — treated as a raw RSS feed URL.
 */
object PodcastApi {
  class PodcastException(message: String) : Exception(message)

  private val client = OkHttpClient()
  private val json = Json { ignoreUnknownKeys = true }

  /** Some feed hosts (and Apple) reject requests without a browser-ish UA. */
  private const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) NerLan/1.0"

  private val ID_REGEX = Regex("""/id(\d+)""")

  /** Resolve a pasted URL to an RSS feed URL. */
  suspend fun resolveFeedUrl(pasted: String): String = withContext(Dispatchers.IO) {
    var url = pasted
    // apple.co share links redirect to the real podcasts.apple.com page.
    if (url.contains("apple.co")) url = finalUrl(url)
    if (url.contains("apple.com")) {
      ID_REGEX.find(url)?.groupValues?.get(1)?.let { return@withContext feedUrlForCollection(it) }
    }
    // Raw RSS / direct feed URL — use as-is.
    url
  }

  /** Download a feed (or any) URL's bytes with a browser-ish UA. */
  suspend fun fetchFeed(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw PodcastException("下載 RSS 失敗（HTTP ${response.code}）")
      response.body?.string() ?: throw PodcastException("empty body")
    }
  }

  /** iTunes Lookup → the show's RSS `feedUrl`. */
  private fun feedUrlForCollection(id: String): String {
    val url = "https://itunes.apple.com/lookup?id=$id&entity=podcast"
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw PodcastException("查詢 Podcast 失敗（HTTP ${response.code}）")
      val feed = runCatching {
        json.parseToJsonElement(body).jsonObject["results"]!!.jsonArray
          .first().jsonObject["feedUrl"]!!.jsonPrimitive.content
      }.getOrNull()
      return feed ?: throw PodcastException("找不到對應的 Podcast")
    }
  }

  /** Follow redirects to where a short link lands (OkHttp follows them; the final
   *  request URL is on the response). */
  private fun finalUrl(url: String): String {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    client.newCall(request).execute().use { response -> return response.request.url.toString() }
  }
}
