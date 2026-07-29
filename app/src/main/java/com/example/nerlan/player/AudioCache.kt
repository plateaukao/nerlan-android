package com.example.nerlan.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache for streamed audio (opt-in). When [SettingsStore.cacheStreamedAudio]
 * is on, the bytes ExoPlayer pulls from the network during playback are written
 * to `cacheDir/audio` via [CacheDataSource], so a fully-played episode replays
 * offline without re-downloading.
 *
 * Kept separate from explicit downloads (which live in `filesDir/audio`): the
 * cache sits in `cacheDir` (purgeable by the OS, not backed up) and is wiped as a
 * unit by "clear cached audio". Fully-captured episodes show dimmed in the
 * Downloads tab via [DownloadManager.cachedRecords]. This is the Android
 * counterpart of the iOS `CachingPlayerItem` + `DownloadManager` cache bucket.
 * Unlike iOS, ExoPlayer caches per byte-range, so a partially played episode
 * keeps the ranges it fetched (and is not "fully cached" until every byte is).
 */
object AudioCache {
  /** Cap for the streamed-audio cache. Unbounded, a daily streamer accrues
   *  gigabytes that the OS eventually purges wholesale under storage pressure —
   *  exactly when the offline replay was most wanted. LRU keeps the most
   *  recently played ~2 GB (roughly 30–60 spoken-audio episodes). */
  private const val MAX_BYTES = 2L * 1024 * 1024 * 1024

  private var cache: SimpleCache? = null

  private fun cacheDir(context: Context) =
    File(context.applicationContext.cacheDir, "audio").apply { mkdirs() }

  @Synchronized
  private fun simpleCache(context: Context): SimpleCache =
    cache ?: SimpleCache(
      cacheDir(context),
      LeastRecentlyUsedCacheEvictor(MAX_BYTES),
      StandaloneDatabaseProvider(context.applicationContext),
    ).also { cache = it }

  /**
   * Data-source factory for the player: streams over the network while writing to
   * the cache (when [shouldWrite] is true), serves cached bytes when present, and
   * plays local downloaded files directly without re-caching them.
   *
   * [shouldWrite] is read each time a source is created (i.e. per episode load),
   * so toggling the setting takes effect on the next track.
   */
  fun dataSourceFactory(context: Context, shouldWrite: () -> Boolean): DataSource.Factory {
    val appContext = context.applicationContext
    val cache = simpleCache(appContext)
    // Some podcast audio (e.g. BBC) lives on an https enclosure that 302-redirects
    // to a plain-http CDN host. Allow cross-protocol redirects so those play;
    // cleartext itself is permitted by network_security_config.xml.
    val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
    val httpUpstream = DefaultDataSource.Factory(appContext, http)
    return DataSource.Factory {
      val cacheSource = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(httpUpstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .apply { if (!shouldWrite()) setCacheWriteDataSinkFactory(null) }
        .createDataSource()
      SchemeRoutingDataSource(FileDataSource(), cacheSource)
    }
  }

  /**
   * Whether [url]'s resource is fully cached — content length known and every
   * byte present. ExoPlayer caches per byte-range, so an episode the listener
   * seeked through keeps holes and correctly reports false. Opens the cache if
   * the player hasn't yet (reads its index file) — call off the main thread.
   */
  fun isFullyCached(context: Context, url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val c = simpleCache(context)
    val length = ContentMetadata.getContentLength(c.getContentMetadata(url))
    if (length == C.LENGTH_UNSET.toLong()) return false
    return c.getCachedBytes(url, 0, length) >= length
  }

  /** Evict one episode's cached bytes — deleting a cached row in the Downloads
   *  tab, or an explicit download superseding the capture. */
  fun removeResource(context: Context, url: String?) {
    if (url.isNullOrEmpty()) return
    simpleCache(context).removeResource(url)
  }

  /** Total bytes currently cached. Avoids initialising the cache if the player
   *  hasn't yet, by summing the directory instead. */
  fun sizeBytes(context: Context): Long {
    cache?.let { return it.cacheSpace }
    return cacheDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
  }

  /** Number of distinct cached resources (one per streamed episode), or -1 if the
   *  cache isn't initialised yet (its index can't be read without opening it). */
  fun cachedResourceCount(context: Context): Int = cache?.keys?.size ?: -1

  fun clear(context: Context) {
    val c = cache
    if (c != null) {
      c.keys.toList().forEach(c::removeResource)
    } else {
      // Not initialised, so nothing holds the directory — delete it directly.
      cacheDir(context).listFiles()?.forEach { it.deleteRecursively() }
    }
  }
}

/**
 * Routes playback reads by URI scheme: local `file://` URIs (explicit downloads)
 * go straight to [fileSource] so they are never re-cached, while `http(s)` streams
 * go through [cacheSource]. The active delegate is chosen at [open] time.
 */
private class SchemeRoutingDataSource(
  private val fileSource: DataSource,
  private val cacheSource: DataSource,
) : DataSource {
  private var active: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    fileSource.addTransferListener(transferListener)
    cacheSource.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    val scheme = dataSpec.uri.scheme?.lowercase()
    active = if (scheme == "http" || scheme == "https") cacheSource else fileSource
    return active!!.open(dataSpec)
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
    active!!.read(buffer, offset, length)

  override fun getUri(): Uri? = active?.uri

  override fun getResponseHeaders(): Map<String, List<String>> =
    active?.responseHeaders ?: emptyMap()

  override fun close() {
    active?.close()
    active = null
  }
}
