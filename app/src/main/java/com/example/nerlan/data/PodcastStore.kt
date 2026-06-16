package com.example.nerlan.data

import com.example.nerlan.NerLanApp
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * Subscribed podcast shows, persisted as plain JSON in filesDir (podcasts.json) —
 * matching the app's no-database convention. Each feed carries its episodes as
 * [EpisodeRecord]s, so the rest of the app (player, downloads, favorites, AI)
 * needs no podcast-specific code. Mirrors the iOS PodcastStore.
 */
class PodcastStore(filesDir: File) {
  private val file = File(filesDir, "podcasts.json")
  /** Last-writer-wins subscription ledger (feed id -> [SubEntry]); lets Drive sync
   *  propagate unsubscribe/re-subscribe across devices. */
  private val subsFile = File(filesDir, "podcast-subs.json")
  private val json = Json { ignoreUnknownKeys = true }

  private val _feeds = MutableStateFlow(load())
  val feeds: StateFlow<List<PodcastFeed>> = _feeds

  private var ledger: MutableMap<String, SubEntry> = loadLedger()

  private fun load(): List<PodcastFeed> =
    runCatching { json.decodeFromString<List<PodcastFeed>>(file.readText()) }.getOrNull() ?: emptyList()

  private fun loadLedger(): MutableMap<String, SubEntry> =
    runCatching { json.decodeFromString<MutableMap<String, SubEntry>>(subsFile.readText()) }.getOrNull()
      ?: mutableMapOf()

  fun isSubscribed(id: String) = _feeds.value.any { it.id == id }
  fun feed(id: String): PodcastFeed? = _feeds.value.firstOrNull { it.id == id }

  /**
   * Resolve a pasted URL (Apple Podcasts page, apple.co link, or raw RSS), fetch
   * + parse the feed, and subscribe. Returns the stored feed. Re-adding an
   * existing show refreshes the stored copy.
   */
  suspend fun add(pastedUrl: String): PodcastFeed {
    val feedUrl = PodcastApi.resolveFeedUrl(pastedUrl)
    val xml = PodcastApi.fetchFeed(feedUrl)
    val feed = PodcastFeedParser.parse(xml, feedUrl)
    upsert(feed)
    return feed
  }

  /** Subscribe to an already-parsed feed (no network) — re-subscribing from the
   *  detail screen's heart toggle. */
  fun subscribe(feed: PodcastFeed) = upsert(feed)

  fun unsubscribe(id: String) {
    _feeds.value = _feeds.value.filterNot { it.id == id }
    ledger[id] = SubEntry(subscribed = false, ts = System.currentTimeMillis())
    persist()
    NerLanApp.instance.drive.requestSync()
  }

  /** Re-fetch a subscribed feed (its id is the resolved RSS URL) and replace the
   *  stored copy, surfacing newly published episodes. */
  suspend fun refresh(id: String) {
    val xml = PodcastApi.fetchFeed(id)
    upsert(PodcastFeedParser.parse(xml, id))
  }

  /** Re-read podcasts.json and the subscription ledger into memory — used after a
   *  Drive sync merges in changes from another device. */
  fun reload() {
    _feeds.value = load()
    ledger = loadLedger()
  }

  private fun upsert(feed: PodcastFeed) {
    _feeds.value = if (_feeds.value.any { it.id == feed.id }) {
      _feeds.value.map { if (it.id == feed.id) feed else it }
    } else {
      _feeds.value + feed
    }
    ledger[feed.id] = SubEntry(subscribed = true, ts = System.currentTimeMillis())
    persist()
    NerLanApp.instance.drive.requestSync()
  }

  private fun persist() {
    runCatching { file.writeText(json.encodeToString(_feeds.value)) }
    runCatching { subsFile.writeText(json.encodeToString(ledger)) }
  }
}
