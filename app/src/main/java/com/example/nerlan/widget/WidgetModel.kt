package com.example.nerlan.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.EpisodeRecord
import com.example.nerlan.player.PlayerManager

/**
 * What the home-screen widgets draw.
 *
 * Unlike the iOS build — where the widget extension is a separate process and the
 * app has to publish a snapshot into a shared App Group container — an Android
 * app widget runs inside the app's own process, so it simply reads the live
 * stores. There is no snapshot file, and nothing to keep in sync.
 */
data class WidgetEpisode(
  val id: String,
  val title: String,
  val programId: String,
  val programName: String,
  val coverUrl: String?,
  val durationSeconds: Int?,
)

data class WidgetShow(
  val id: String,                 // programId, or the feed URL for a podcast
  val name: String,
  val language: String,
  val coverUrl: String?,
  val isPodcast: Boolean,
  val lastEpisodeId: String? = null,
  val lastEpisodeTitle: String? = null,
  val resumeProgress: Float? = null,
)

data class WidgetStatsData(
  val minutesToday: Int,
  val minutesThisWeek: Int,
  val streakDays: Int,
  val completedCount: Int,
)

data class WidgetModel(
  val nowPlaying: WidgetEpisode?,
  val isPlaying: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val upNext: List<WidgetEpisode>,
  /** Favorited programs and subscribed podcasts, ranked for the 我的節目 grid. */
  val shows: List<WidgetShow>,
  /** Shows actually played recently, newest first. */
  val recents: List<WidgetShow>,
  val stats: WidgetStatsData,
) {
  /** What a bare play button acts on: whatever is loaded, else the top suggestion. */
  val lead: WidgetEpisode? get() = nowPlaying ?: upNext.firstOrNull()

  val progress: Float?
    get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

object WidgetModelBuilder {

  /**
   * Build the current view. Awaits the MediaController first: a widget update can
   * run in a process that was started for the update alone, where nothing has
   * connected to PlaybackService yet and playback state would read as empty.
   */
  suspend fun build(context: Context): WidgetModel {
    val app = NerLanApp.instance
    PlayerManager.awaitController(context)

    val current = PlayerManager.current.value
    val stats = app.stats.uiStats()

    return WidgetModel(
      nowPlaying = current?.toWidget(),
      isPlaying = PlayerManager.isPlaying.value,
      positionMs = PlayerManager.positionMs.value,
      durationMs = PlayerManager.durationMs.value,
      upNext = upNext(current),
      shows = shows(),
      recents = recents(),
      stats = WidgetStatsData(
        minutesToday = (stats.today / 60).toInt(),
        minutesThisWeek = (stats.week / 60).toInt(),
        streakDays = stats.streak,
        completedCount = stats.completedCount,
      ),
    )
  }

  private fun EpisodeRecord.toWidget() = WidgetEpisode(
    id = id,
    title = title,
    programId = programId,
    programName = programName,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
  )

  /** Recent downloads and favorites, so the widget has something to offer before
   *  anything has been played. */
  private fun upNext(current: EpisodeRecord?): List<WidgetEpisode> {
    val app = NerLanApp.instance
    val seen = HashSet<String>().apply { current?.let { add(it.id) } }
    return (app.downloads.records.value.asReversed() + app.favorites.episodes.value.asReversed())
      .filter { seen.add(it.id) }
      .take(8)
      .map { it.toWidget() }
  }

  /**
   * Favorited programs and subscribed podcasts.
   *
   * Ordered recently-played first, then by listening time. Ranking on listening
   * time alone buries podcasts entirely — favorited programs are more numerous
   * and carry almost all the accumulated time, so a small grid never reaches one
   * — and pinning podcasts above programs only inverts that. Recency mixes the
   * two kinds on the signal that matters: what's being worked through now. The
   * widget's own show picker overrides this for anything deliberate.
   */
  private fun shows(): List<WidgetShow> {
    val app = NerLanApp.instance
    val out = ArrayList<WidgetShow>()
    for (p in app.favorites.programs.value) {
      out += WidgetShow(p.programId, p.name, p.language, p.coverUrl, isPodcast = false)
    }
    for (f in app.podcasts.feeds.value) {
      out += WidgetShow(f.id, f.title, f.language, f.coverUrl, isPodcast = true)
    }

    val recentRank = app.recents.shows.value.withIndex().associate { it.value.id to it.index }
    val seconds = app.stats.secondsByProgram()
    out.sortWith(
      compareBy(
        { recentRank[it.id] ?: Int.MAX_VALUE },
        { -(seconds[it.id] ?: 0.0) },
        { it.name },
      )
    )

    // Cap each kind separately: this list also populates the show picker, so
    // anything dropped here can't be chosen at all — and with many podcasts
    // subscribed a single shared cap would squeeze the programs out.
    val keep = HashSet<String>()
    out.filter { it.isPodcast }.take(20).forEach { keep += it.id }
    out.filter { !it.isPodcast }.take(20).forEach { keep += it.id }
    return out.filter { it.id in keep }
  }

  private fun recents(): List<WidgetShow> {
    val app = NerLanApp.instance
    return app.recents.shows.value.take(8).map { e ->
      WidgetShow(
        id = e.id,
        name = e.name,
        language = e.language,
        coverUrl = e.coverUrl,
        isPodcast = e.isPodcast,
        lastEpisodeId = e.lastEpisodeId,
        lastEpisodeTitle = e.lastEpisodeTitle,
        resumeProgress = app.positions.progress(e.lastEpisodeId),
      )
    }
  }
}

/**
 * Cover art as a bitmap for Glance. Coil's disk cache is already warm from the
 * app's own lists, so this is normally a local read.
 *
 * Two constraints drive the small sizes: RemoteViews caps the total bitmap
 * payload it will ferry across processes (~1 MB), and hardware bitmaps can't be
 * serialised into RemoteViews at all — hence `allowHardware(false)`.
 */
suspend fun loadCoverBitmap(context: Context, url: String?, sizePx: Int): Bitmap? {
  if (url.isNullOrEmpty()) return null
  val request = ImageRequest.Builder(context)
    .data(url)
    .size(sizePx)
    .allowHardware(false)
    .build()
  val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
  return (result?.drawable as? BitmapDrawable)?.bitmap
}

/** "1 小時 12 分" / "12 分鐘" — listening totals. */
fun formatMinutes(minutes: Int): String =
  if (minutes >= 60) {
    val h = minutes / 60
    val m = minutes % 60
    if (m == 0) "$h 小時" else "$h 小時 $m 分"
  } else "$minutes 分鐘"

/** "剩 12 分鐘" for what's playing. */
fun formatRemaining(positionMs: Long, durationMs: Long): String? {
  if (durationMs <= 0) return null
  val leftSec = ((durationMs - positionMs) / 1000).coerceAtLeast(0)
  return if (leftSec < 60) "剩 $leftSec 秒" else "剩 ${leftSec / 60} 分鐘"
}
