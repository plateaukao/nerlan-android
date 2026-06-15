package com.example.nerlan.data

import android.content.Context
import com.example.nerlan.NerLanApp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tracks listening *behavior* — time spent, completed episodes, and per-day /
 * per-hour / per-program buckets — to power the 使用統計 screen. Persisted as plain
 * JSON in filesDir (like downloads.json), and when Drive sync is on each device
 * mirrors its own blob to a `stats-<deviceId>.json` file in the appDataFolder.
 *
 * Cross-device merge is a conflict-free **G-counter**: a device only ever
 * increments its *own* partition, and the displayed numbers are the sum across
 * every device's blob. Partitions never overlap, so summation can't double-count
 * or clobber the way union-merging a single shared total would. Mirrors the iOS
 * ListeningStatsStore (which does the same over iCloud KVS). Forward-only — the
 * app never recorded playback before.
 */
class ListeningStatsStore(context: Context) {

  /** One device's listening tallies. */
  @Serializable
  data class Stats(
    val dailySeconds: Map<String, Double> = emptyMap(),  // "yyyy-MM-dd" -> seconds
    val hourlyDate: String = "",                          // the day hourlySeconds describes
    val hourlySeconds: Map<Int, Double> = emptyMap(),     // hour 0..23 -> seconds (that day)
    val completedCount: Int = 0,
    val programSeconds: Map<String, Double> = emptyMap(), // programId -> seconds
    val programNames: Map<String, String> = emptyMap(),   // programId -> display name
  )

  data class HourStat(val hour: Int, val seconds: Double)
  data class DayStat(val timeMillis: Long, val seconds: Double)
  data class ProgramStat(val id: String, val name: String, val seconds: Double)

  /** Everything the usage screen needs, computed once under the lock. */
  data class UiStats(
    val hasData: Boolean,
    val totalSeconds: Double,
    val completedCount: Int,
    val streak: Int,
    val today: Double,
    val week: Double,
    val month: Double,
    val hourly: List<HourStat>,
    val last7: List<DayStat>,
    val last30: List<DayStat>,
    val topPrograms: List<ProgramStat>,
  )

  /** Bumped on every change so the observing screen recomputes its view. */
  private val _revision = MutableStateFlow(0)
  val revision: StateFlow<Int> = _revision

  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()

  private val ownFile = File(context.filesDir, "listening-stats.json")
  private val peersDir = File(context.filesDir, "stats-peers").apply { mkdirs() }
  private val deviceId: String

  // This device's contribution, held as mutable maps for cheap accumulation.
  private val dailySeconds = HashMap<String, Double>()
  private var hourlyDate = ""
  private val hourlySeconds = HashMap<Int, Double>()
  private var completedCount = 0
  private val programSeconds = HashMap<String, Double>()
  private val programNames = HashMap<String, String>()

  // Other devices' blobs, refreshed by DriveSync; immutable, replaced atomically.
  @Volatile private var peers: List<Stats> = emptyList()

  /** Listening accumulated since the last disk write, to throttle persistence. */
  private var unsaved = 0.0

  init {
    val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
    deviceId = prefs.getString("deviceId", null)
      ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    runCatching { json.decodeFromString<Stats>(ownFile.readText()) }.getOrNull()?.let(::adopt)
    peers = loadPeers()
  }

  // MARK: - Recording (called from PlayerManager)

  /** Add wall-clock listening time to the current day/hour and program. */
  fun addListening(seconds: Double, record: EpisodeRecord?) {
    if (seconds <= 0) return
    synchronized(lock) {
      val now = System.currentTimeMillis()
      val day = dayKey(now)
      val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
      dailySeconds[day] = (dailySeconds[day] ?: 0.0) + seconds
      if (hourlyDate != day) { hourlyDate = day; hourlySeconds.clear() }
      hourlySeconds[hour] = (hourlySeconds[hour] ?: 0.0) + seconds
      if (record != null) {
        programSeconds[record.programId] = (programSeconds[record.programId] ?: 0.0) + seconds
        programNames[record.programId] = record.programName
      }
      unsaved += seconds
      if (unsaved >= 5) persistLocalLocked()
    }
    _revision.value += 1
  }

  /** Record an episode played through to the end. */
  fun noteCompleted(@Suppress("UNUSED_PARAMETER") record: EpisodeRecord?) {
    synchronized(lock) {
      completedCount += 1
      persistLocalLocked()
    }
    NerLanApp.instance.drive.requestSync()
    _revision.value += 1
  }

  /** Persist and request a sync now — call on pause / when leaving an episode. */
  fun flush() {
    synchronized(lock) { persistLocalLocked() }
    NerLanApp.instance.drive.requestSync()
  }

  // MARK: - Persistence

  private fun adopt(s: Stats) {
    dailySeconds.putAll(s.dailySeconds)
    hourlyDate = s.hourlyDate
    hourlySeconds.putAll(s.hourlySeconds)
    completedCount = s.completedCount
    programSeconds.putAll(s.programSeconds)
    programNames.putAll(s.programNames)
  }

  private fun snapshotLocked() = Stats(
    dailySeconds = HashMap(dailySeconds),
    hourlyDate = hourlyDate,
    hourlySeconds = HashMap(hourlySeconds),
    completedCount = completedCount,
    programSeconds = HashMap(programSeconds),
    programNames = HashMap(programNames),
  )

  private fun persistLocalLocked() {
    unsaved = 0.0
    pruneLocked()
    val snap = snapshotLocked()
    scope.launch { runCatching { ownFile.writeText(json.encodeToString(snap)) } }
  }

  /** Drop daily buckets older than ~400 days so the blob stays tiny for sync. */
  private fun pruneLocked() {
    if (dailySeconds.size <= 400) return
    val cutoff = dayKey(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -400) }.timeInMillis)
    dailySeconds.keys.iterator().let { it ->
      while (it.hasNext()) if (it.next() < cutoff) it.remove()
    }
  }

  private fun loadPeers(): List<Stats> =
    peersDir.listFiles()?.mapNotNull { f ->
      runCatching { json.decodeFromString<Stats>(f.readText()) }.getOrNull()
    } ?: emptyList()

  // MARK: - Drive sync hooks (per-device G-counter), called by DriveSync

  val driveFileName: String get() = "stats-$deviceId.json"

  fun ownJsonBytes(): ByteArray = synchronized(lock) {
    json.encodeToString(snapshotLocked()).toByteArray()
  }

  /** Store another device's blob (named by its Drive file name) for merging. */
  fun savePeer(driveName: String, bytes: ByteArray) {
    if (driveName == driveFileName) return
    runCatching { File(peersDir, driveName).writeBytes(bytes) }
  }

  fun reloadPeers() {
    peers = loadPeers()
    _revision.value += 1
  }

  // MARK: - Merged view (this device + peers)

  /** Compute the whole usage view in one locked pass. */
  fun uiStats(): UiStats = synchronized(lock) {
    val merged = mergedDailyLocked()
    val total = merged.values.sum()
    val completed = completedCount + peers.sumOf { it.completedCount }
    UiStats(
      hasData = completed > 0 || total > 0,
      totalSeconds = total,
      completedCount = completed,
      streak = streakLocked(merged),
      today = merged[dayKey(System.currentTimeMillis())] ?: 0.0,
      week = sumSinceLocked(merged, startOfWeek()),
      month = sumSinceLocked(merged, startOfMonth()),
      hourly = hourlyTodayLocked(),
      last7 = dailySeriesLocked(merged, 7),
      last30 = dailySeriesLocked(merged, 30),
      topPrograms = topProgramsLocked(3),
    )
  }

  private fun mergedDailyLocked(): Map<String, Double> {
    val out = HashMap<String, Double>(dailySeconds)
    for (p in peers) for ((d, s) in p.dailySeconds) out[d] = (out[d] ?: 0.0) + s
    return out
  }

  private fun sumSinceLocked(merged: Map<String, Double>, start: Long): Double {
    val startKey = dayKey(start)
    return merged.filterKeys { it >= startKey }.values.sum()
  }

  /** Consecutive days with listening, ending today (or yesterday if today is
   *  still empty, so the streak holds until a day is actually missed). */
  private fun streakLocked(merged: Map<String, Double>): Int {
    val cal = startOfDayCal()
    if ((merged[dayKey(cal.timeInMillis)] ?: 0.0) <= 0) cal.add(Calendar.DAY_OF_YEAR, -1)
    var streak = 0
    while ((merged[dayKey(cal.timeInMillis)] ?: 0.0) > 0) {
      streak++
      cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
  }

  private fun hourlyTodayLocked(): List<HourStat> {
    val today = dayKey(System.currentTimeMillis())
    val hours = DoubleArray(24)
    if (hourlyDate == today) for ((h, s) in hourlySeconds) if (h in 0..23) hours[h] += s
    for (p in peers) if (p.hourlyDate == today) for ((h, s) in p.hourlySeconds) if (h in 0..23) hours[h] += s
    return (0..23).map { HourStat(it, hours[it]) }
  }

  /** Listening per day for the last [days] days (oldest first), zero-filled. */
  private fun dailySeriesLocked(merged: Map<String, Double>, days: Int): List<DayStat> {
    val today = startOfDayCal().timeInMillis
    return (days - 1 downTo 0).map { offset ->
      val cal = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -offset) }
      DayStat(cal.timeInMillis, merged[dayKey(cal.timeInMillis)] ?: 0.0)
    }
  }

  private fun topProgramsLocked(limit: Int): List<ProgramStat> {
    val secs = HashMap<String, Double>()
    val names = HashMap<String, String>()
    fun acc(ps: Map<String, Double>, ns: Map<String, String>) {
      for ((id, v) in ps) secs[id] = (secs[id] ?: 0.0) + v
      for ((id, n) in ns) if (id !in names) names[id] = n
    }
    acc(programSeconds, programNames)
    for (p in peers) acc(p.programSeconds, p.programNames)
    return secs.entries.sortedByDescending { it.value }.take(limit)
      .map { ProgramStat(it.key, names[it.key] ?: it.key, it.value) }
  }

  // MARK: - Date helpers

  private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private fun dayKey(timeMillis: Long): String = dayFormat.format(Date(timeMillis))

  private fun startOfDayCal(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
  }

  private fun startOfWeek(): Long = startOfDayCal().apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.timeInMillis

  private fun startOfMonth(): Long = startOfDayCal().apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
}
