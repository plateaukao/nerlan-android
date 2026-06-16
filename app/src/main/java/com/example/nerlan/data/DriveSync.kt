package com.example.nerlan.data

import android.content.Context
import com.example.nerlan.NerLanApp
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.Scope
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Syncs favorites and AI study content to the user's Google Drive **appDataFolder**
 * — a hidden, app-private folder in their own Drive (no developer-hosted backend,
 * the Android analog of the iOS iCloud sync). Audio is deliberately never synced.
 *
 * Talks to the Drive REST API directly over the app's OkHttp client; the access
 * token comes from Google sign-in via [GoogleAuthUtil], so the only added
 * dependency is play-services-auth.
 *
 * Sync model (no server-side change feed, so it runs on launch / sign-in / a
 * manual "sync now"):
 *  - metadata (favorites, programs, AI index): union-merge by id, last write wins
 *    on conflict — additions on any device propagate; deletions don't (a backup
 *    tradeoff, like the iOS file sync).
 *  - content files (transcripts/handouts): write-once, so copy whichever side is
 *    missing it.
 */
class DriveSync(private val context: Context) {
  private val filesDir = context.filesDir
  private val client = ChannelPlusApi.client
  private val json = Json { ignoreUnknownKeys = true }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _accountEmail = MutableStateFlow(GoogleSignIn.getLastSignedInAccount(context)?.email)
  val accountEmail: StateFlow<String?> = _accountEmail

  /** Human-readable last-sync status for the settings screen. */
  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status

  fun onSignedIn(account: GoogleSignInAccount) {
    _accountEmail.value = account.email
    syncNow()
  }

  /** Surface a failed sign-in (e.g. code 10 = DEVELOPER_ERROR when the OAuth
   *  client / Drive API isn't configured in Google Cloud yet). */
  fun reportSignInError(code: Int) {
    val hint = if (code == GoogleSignInStatusCodes.DEVELOPER_ERROR) {
      "請先在 Google Cloud 設定 OAuth 用戶端（套件名 com.danielkao.nerlan ＋ SHA-1）並啟用 Drive API。"
    } else ""
    _status.value = "登入失敗：${GoogleSignInStatusCodes.getStatusCodeString(code)}（$code）。$hint"
  }

  fun signOut() {
    signInClient(context).signOut()
    _accountEmail.value = null
    _status.value = null
  }

  private val syncMutex = Mutex()
  private var debounceJob: Job? = null

  /** Run a full sync in the background (no-op if not signed in). */
  fun syncNow() {
    if (_accountEmail.value == null) return
    scope.launch { runSyncWithStatus() }
  }

  /**
   * Debounced auto-sync after a local change (favoriting, or a transcript/handout
   * finishing). Coalesces a burst of changes into one sync ~2.5s after the last
   * one. No-op unless sync is on and signed in.
   */
  fun requestSync() {
    if (!NerLanApp.instance.settings.syncToDrive.value || _accountEmail.value == null) return
    debounceJob?.cancel()
    debounceJob = scope.launch {
      delay(2_500)
      runSyncWithStatus()
    }
  }

  private suspend fun runSyncWithStatus() {
    _status.value = "同步中…"
    runCatching { syncMutex.withLock { sync() } }
      .onSuccess { (up, down) -> _status.value = "已同步（↑$up ↓$down）" }
      .onFailure {
        _status.value = when (it) {
          is UserRecoverableAuthException -> "需要重新授權，請重新登入"
          else -> "同步失敗：${it.message}"
        }
      }
  }

  // MARK: - Sync engine

  private fun sync(): Pair<Int, Int> {
    val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: error("尚未登入 Google 帳戶")
    val token = GoogleAuthUtil.getToken(context, account, "oauth2:$SCOPE")
    val remote = listFiles(token).associateBy { it.name }
    var pushed = 0
    var pulled = 0

    // Metadata: union-merge, then write the merged copy back up.
    pushed += syncMetadata(token, remote, "favorites.json") { remoteBytes ->
      val merged = mergeById(readList<EpisodeRecord>(favoritesFile), decodeList<EpisodeRecord>(remoteBytes)) { it.id }
      favoritesFile.writeText(json.encodeToString(merged)); json.encodeToString(merged)
    }
    pushed += syncMetadata(token, remote, "favorite-programs.json") { remoteBytes ->
      val merged = mergeById(readList<Program>(programsFile), decodeList<Program>(remoteBytes)) { it.programId }
      programsFile.writeText(json.encodeToString(merged)); json.encodeToString(merged)
    }
    pushed += syncMetadata(token, remote, "ai-index.json") { remoteBytes ->
      // toMap() keeps a plain LinkedHashMap — serializing a TreeMap (toSortedMap)
      // throws "Serializer for class 'TreeMap' is not found".
      val merged: Map<String, EpisodeRecord> =
        (decodeMap(remoteBytes) + readMap(indexFile)).toList().sortedBy { it.first }.toMap()
      indexFile.parentFile?.mkdirs()
      indexFile.writeText(json.encodeToString(merged))
      json.encodeToString(merged)
    }
    // Podcast subscriptions: union-merge by feed id (the RSS URL). Each device keeps
    // its own episode snapshots fresh via pull-to-refresh; additions propagate.
    pushed += syncMetadata(token, remote, "podcasts.json") { remoteBytes ->
      val merged = mergeById(readList<PodcastFeed>(podcastsFile), decodeList<PodcastFeed>(remoteBytes)) { it.id }
      podcastsFile.writeText(json.encodeToString(merged)); json.encodeToString(merged)
    }

    // Content files (write-once): push local-only up, pull remote-only down.
    val local = contentFiles()
    for ((name, file) in local) if (name !in remote) {
      upsert(token, name, null, file.readBytes(), "text/plain"); pushed++
    }
    for (rf in remote.values) if (isContentName(rf.name) && rf.name !in local) {
      writeContent(rf.name, downloadBytes(token, rf.id)); pulled++
    }

    // Listening stats: one blob per device, summed on read (a G-counter). Push
    // our own blob, pull everyone else's. Isolated so a stats hiccup can't abort
    // the favorites/AI sync above.
    runCatching { syncStats(token, remote) }.onSuccess { (up, down) -> pushed += up; pulled += down }

    NerLanApp.instance.favorites.reload()
    NerLanApp.instance.ai.reloadIndex()
    NerLanApp.instance.podcasts.reload()
    return pushed to pulled
  }

  /** Mirror this device's stats blob up and pull every other device's down. */
  private fun syncStats(token: String, remote: Map<String, DriveFile>): Pair<Int, Int> {
    val stats = NerLanApp.instance.stats
    val ownName = stats.driveFileName
    upsert(token, ownName, remote[ownName]?.id, stats.ownJsonBytes(), "application/json")
    var pulled = 0
    for (rf in remote.values) {
      if (rf.name != ownName && rf.name.startsWith("stats-") && rf.name.endsWith(".json")) {
        stats.savePeer(rf.name, downloadBytes(token, rf.id)); pulled++
      }
    }
    stats.reloadPeers()
    return 1 to pulled
  }

  /** Merge one metadata file: download remote, run [merge], upload the result.
   *  Returns 1 (uploaded) or 0. */
  private fun syncMetadata(
    token: String,
    remote: Map<String, DriveFile>,
    driveName: String,
    merge: (ByteArray?) -> String,
  ): Int {
    val rf = remote[driveName]
    val mergedJson = merge(rf?.let { downloadBytes(token, it.id) })
    upsert(token, driveName, rf?.id, mergedJson.toByteArray(), "application/json")
    return 1
  }

  private fun <T> mergeById(local: List<T>, remote: List<T>, id: (T) -> String): List<T> =
    (remote + local).associateBy(id).values.sortedBy(id)

  // MARK: - Local file mapping

  private val favoritesFile get() = File(filesDir, "favorites.json")
  private val programsFile get() = File(filesDir, "favorite-programs.json")
  private val indexFile get() = File(filesDir, "ai/index.json")
  private val podcastsFile get() = File(filesDir, "podcasts.json")

  private inline fun <reified T> readList(file: File): List<T> =
    runCatching { json.decodeFromString<List<T>>(file.readText()) }.getOrNull() ?: emptyList()

  private inline fun <reified T> decodeList(bytes: ByteArray?): List<T> =
    bytes?.let { runCatching { json.decodeFromString<List<T>>(String(it)) }.getOrNull() } ?: emptyList()

  private fun readMap(file: File): Map<String, EpisodeRecord> =
    runCatching { json.decodeFromString<Map<String, EpisodeRecord>>(file.readText()) }.getOrNull() ?: emptyMap()

  private fun decodeMap(bytes: ByteArray?): Map<String, EpisodeRecord> =
    bytes?.let { runCatching { json.decodeFromString<Map<String, EpisodeRecord>>(String(it)) }.getOrNull() } ?: emptyMap()

  /** drive name -> local content file, for transcripts, handouts, and cue sidecars. */
  private fun contentFiles(): Map<String, File> = buildMap {
    File(filesDir, "ai/transcripts").listFiles()?.forEach { put("transcript-${it.nameWithoutExtension}.txt", it) }
    File(filesDir, "ai/handouts").listFiles()?.forEach { put("handout-${it.nameWithoutExtension}.html", it) }
    File(filesDir, "ai/cues").listFiles()?.forEach { put("cues-${it.nameWithoutExtension}.json", it) }
  }

  private fun isContentName(name: String) =
    (name.startsWith("transcript-") && name.endsWith(".txt")) ||
      (name.startsWith("handout-") && name.endsWith(".html")) ||
      (name.startsWith("cues-") && name.endsWith(".json"))

  private fun writeContent(name: String, bytes: ByteArray) {
    when {
      name.startsWith("transcript-") && name.endsWith(".txt") -> {
        val id = name.removePrefix("transcript-").removeSuffix(".txt")
        File(filesDir, "ai/transcripts").apply { mkdirs() }.let { File(it, "$id.txt").writeBytes(bytes) }
      }
      name.startsWith("handout-") && name.endsWith(".html") -> {
        val id = name.removePrefix("handout-").removeSuffix(".html")
        File(filesDir, "ai/handouts").apply { mkdirs() }.let { File(it, "$id.html").writeBytes(bytes) }
      }
      name.startsWith("cues-") && name.endsWith(".json") -> {
        val id = name.removePrefix("cues-").removeSuffix(".json")
        File(filesDir, "ai/cues").apply { mkdirs() }.let { File(it, "$id.json").writeBytes(bytes) }
      }
    }
  }

  // MARK: - Drive REST (over OkHttp)

  @Serializable private data class FileList(val files: List<DriveFile> = emptyList())
  @Serializable private data class DriveFile(val id: String, val name: String, val modifiedTime: String? = null)
  @Serializable private data class UploadMeta(val name: String, val parents: List<String>)

  private fun listFiles(token: String): List<DriveFile> {
    val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
      .addQueryParameter("spaces", "appDataFolder")
      .addQueryParameter("fields", "files(id,name,modifiedTime)")
      .addQueryParameter("pageSize", "1000")
      .build()
    val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("Drive list ${resp.code}")
      return json.decodeFromString<FileList>(resp.body!!.string()).files
    }
  }

  private fun downloadBytes(token: String, id: String): ByteArray {
    val url = "https://www.googleapis.com/drive/v3/files/$id".toHttpUrl().newBuilder()
      .addQueryParameter("alt", "media").build()
    val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("Drive download ${resp.code}")
      return resp.body!!.bytes()
    }
  }

  /** Create the file (when [existingId] is null) or replace its content. */
  private fun upsert(token: String, name: String, existingId: String?, bytes: ByteArray, mime: String) {
    val media = bytes.toRequestBody(mime.toMediaType())
    val req = if (existingId == null) {
      val meta = json.encodeToString(UploadMeta(name, listOf("appDataFolder")))
      val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
        .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        .addPart(media)
        .build()
      Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
        .header("Authorization", "Bearer $token").post(body).build()
    } else {
      Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media")
        .header("Authorization", "Bearer $token").patch(media).build()
    }
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("Drive upload ${resp.code}")
    }
  }

  companion object {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    fun signInClient(context: Context): GoogleSignInClient {
      val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(SCOPE))
        .build()
      return GoogleSignIn.getClient(context, options)
    }
  }
}
