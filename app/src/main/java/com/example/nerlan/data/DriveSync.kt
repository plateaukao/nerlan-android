package com.example.nerlan.data

import android.content.Context
import android.content.Intent
import com.example.nerlan.NerLanApp
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

  /** Token source: GMS when it works, browser OAuth when it doesn't. The sync
   *  engine below is agnostic to which one produced the access token. */
  private val auth = DriveAuth(context)

  private val _accountEmail = MutableStateFlow(auth.email)
  val accountEmail: StateFlow<String?> = _accountEmail

  /** Human-readable last-sync status for the settings screen. */
  private val _status = MutableStateFlow<String?>(null)
  val status: StateFlow<String?> = _status

  fun onSignedIn(account: GoogleSignInAccount) {
    // A successful GMS sign-in means the broker works; clear any sticky browser
    // fallback so this device prefers GMS again.
    auth.resetToAuto()
    _accountEmail.value = account.email
    syncNow()
  }

  // MARK: - Browser OAuth fallback (GMS-less devices)

  /** Whether the browser sign-in path is configured (gradle placeholders filled). */
  val browserAuthConfigured: Boolean get() = auth.browserConfigured

  /** Intent that opens Google's consent page in a Custom Tab or the default
   *  browser, or null if the request can't be built (e.g. the device has no
   *  browser at all — AppAuth throws ActivityNotFoundException). Settings launches
   *  it via `StartActivityForResult`. */
  fun browserSignInIntent(): Intent? = try {
    auth.browserAuthIntent()
  } catch (e: Exception) {
    _status.value = "無法開啟瀏覽器登入：${e.message}"
    null
  }

  /** Classify a GMS sign-in failure so the UI can auto-offer the browser flow when
   *  the broker is structurally dead (the A7), but not on config/network errors. */
  fun classifyGmsSignIn(t: Throwable): GmsFailure = auth.classify(t)

  /** Complete the browser redirect (token exchange); kicks off a sync on success. */
  suspend fun completeBrowserSignIn(data: Intent?) {
    _status.value = "登入中…"
    if (auth.completeBrowserSignIn(data)) {
      _accountEmail.value = auth.email
      syncNow()
    } else {
      _status.value = "瀏覽器登入失敗"
    }
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
    auth.signOut()
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
          // GMS needs re-consent, or the browser session expired (which already
          // cleared itself). Refresh the email so a cleared browser session flips
          // the UI back to the login button.
          is UserRecoverableAuthException, is ReauthRequired -> {
            _accountEmail.value = auth.email
            "需要重新授權，請重新登入"
          }
          else -> "同步失敗：${it.message}"
        }
      }
  }

  // MARK: - Sync engine

  /** Per-file change tokens persisted locally between syncs: the remote's Drive
   *  `modifiedTime` (which advances whenever *any* device writes the file) and a
   *  hash of the bytes this device last wrote locally. When both still match,
   *  neither side changed since the last sync and the file is skipped entirely —
   *  no download, no upload. The first sync after install (no state) treats
   *  everything as changed and does a full pass, rebuilding the state. */
  @Serializable private data class FileState(val remoteModifiedTime: String? = null, val localHash: String? = null)
  @Serializable private data class SyncState(val files: Map<String, FileState> = emptyMap())

  /** What one file/group sync did: bytes moved (for the status line) and the new
   *  change tokens to persist for the files it touched. */
  private data class SyncOutcome(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val state: Map<String, FileState> = emptyMap(),
  )

  private suspend fun sync(): Pair<Int, Int> = coroutineScope {
    val token = auth.accessToken()
    val remote = listFiles(token).associateBy { it.name }
    val prev = loadState()

    // Independent files sync concurrently (OkHttp multiplexes them onto one HTTP/2
    // connection to googleapis.com). Each unit skips its transfers when nothing
    // changed and reports the change tokens to persist.
    val outcomes = listOf(
      async {
        syncMetadataFile(token, remote, prev.files["favorites.json"], "favorites.json", favoritesFile) { l, r ->
          json.encodeToString(mergeById(decodeList<EpisodeRecord>(l), decodeList<EpisodeRecord>(r)) { it.id }).toByteArray()
        }
      },
      async {
        syncMetadataFile(token, remote, prev.files["favorite-programs.json"], "favorite-programs.json", programsFile) { l, r ->
          json.encodeToString(mergeById(decodeList<Program>(l), decodeList<Program>(r)) { it.programId }).toByteArray()
        }
      },
      async {
        syncMetadataFile(token, remote, prev.files["ai-index.json"], "ai-index.json", indexFile) { l, r ->
          // local (l) overrides remote (r) on key conflict; toMap() keeps a plain
          // LinkedHashMap (serializing a TreeMap throws "Serializer not found").
          val merged: Map<String, EpisodeRecord> =
            (decodeMap(r) + decodeMap(l)).toList().sortedBy { it.first }.toMap()
          json.encodeToString(merged).toByteArray()
        }
      },
      // Podcast subscriptions: union-merge the feed data plus a last-writer-wins
      // subscription ledger, keeping only feeds the merged ledger marks subscribed.
      async { syncPodcasts(token, remote, prev) },
      // Content files (transcripts/handouts/cues), write-once.
      async { syncContentFiles(token, remote) },
      // Listening stats: one blob per device, summed on read (a G-counter).
      // Isolated so a stats hiccup can't abort the favorites/AI sync.
      async { runCatching { syncStats(token, remote, prev) }.getOrDefault(SyncOutcome()) },
    ).awaitAll()

    var pushed = 0
    var pulled = 0
    val newState = HashMap(prev.files) // retain untouched entries, overlay what changed
    for (o in outcomes) { pushed += o.pushed; pulled += o.pulled; newState.putAll(o.state) }
    saveState(SyncState(newState))

    // Only refresh the in-memory stores when a pull actually changed local files.
    if (pulled > 0) {
      NerLanApp.instance.favorites.reload()
      NerLanApp.instance.ai.reloadIndex()
      NerLanApp.instance.podcasts.reload()
    }
    pushed to pulled
  }

  private val stateFile get() = File(filesDir, "drive-sync-state.json")

  private fun loadState(): SyncState =
    runCatching { json.decodeFromString<SyncState>(stateFile.readText()) }.getOrNull() ?: SyncState()

  private fun saveState(state: SyncState) {
    runCatching { stateFile.writeText(json.encodeToString(state)) }
  }

  /** SHA-256 hex of [bytes], or null when absent — the local-change token. */
  private fun hash(bytes: ByteArray?): String? = bytes?.let {
    java.security.MessageDigest.getInstance("SHA-256").digest(it)
      .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
  }

  /** Mirror this device's stats blob up (only when it changed) and pull every
   *  other device's down (only those whose modifiedTime advanced since last sync). */
  private fun syncStats(token: String, remote: Map<String, DriveFile>, prev: SyncState): SyncOutcome {
    val stats = NerLanApp.instance.stats
    val ownName = stats.driveFileName
    val ownBytes = stats.ownJsonBytes()
    val ownHash = hash(ownBytes)
    val state = HashMap<String, FileState>()
    var pushed = 0
    var pulled = 0

    // Only this device writes its own blob, so a matching local hash means the
    // remote copy is already current — skip the upload.
    if (ownHash != prev.files[ownName]?.localHash) {
      val mt = upsert(token, ownName, remote[ownName]?.id, ownBytes, "application/json")
      state[ownName] = FileState(mt, ownHash)
      pushed++
    }

    var changedPeers = false
    for (rf in remote.values) {
      if (rf.name == ownName || !rf.name.startsWith("stats-") || !rf.name.endsWith(".json")) continue
      if (rf.modifiedTime != prev.files[rf.name]?.remoteModifiedTime) {
        stats.savePeer(rf.name, downloadBytes(token, rf.id))
        state[rf.name] = FileState(rf.modifiedTime, null)
        pulled++
        changedPeers = true
      }
    }
    if (changedPeers) stats.reloadPeers()
    return SyncOutcome(pushed, pulled, state)
  }

  /**
   * Sync one union-merge metadata file. Skips both transfers when the remote's
   * modifiedTime and the local hash both still match the last sync; otherwise
   * downloads the remote, runs [merge] over (localBytes, remoteBytes), writes the
   * result back to disk only if it changed, and uploads only if it differs from
   * the remote. [merge] is pure — the read/write/compare lives here.
   */
  private fun syncMetadataFile(
    token: String,
    remote: Map<String, DriveFile>,
    prev: FileState?,
    driveName: String,
    localFile: File,
    merge: (localBytes: ByteArray?, remoteBytes: ByteArray?) -> ByteArray,
  ): SyncOutcome {
    val rf = remote[driveName]
    val localBytes = localFile.takeIf { it.exists() }?.readBytes()
    val localHash = hash(localBytes)
    val remoteChanged = rf?.modifiedTime != prev?.remoteModifiedTime
    val localChanged = localHash != prev?.localHash

    if (rf == null && localBytes == null) return SyncOutcome()  // nothing anywhere
    if (!remoteChanged && !localChanged) return SyncOutcome()   // in sync since last time

    val remoteBytes = rf?.let { downloadBytes(token, it.id) }
    val merged = merge(localBytes, remoteBytes)
    var pushed = 0
    var pulled = 0
    if (!merged.contentEquals(localBytes)) {
      localFile.parentFile?.mkdirs()
      localFile.writeBytes(merged)
      pulled = 1
    }
    var modifiedTime = rf?.modifiedTime
    if (!merged.contentEquals(remoteBytes)) {
      modifiedTime = upsert(token, driveName, rf?.id, merged, "application/json")
      pushed = 1
    }
    return SyncOutcome(pushed, pulled, mapOf(driveName to FileState(modifiedTime, hash(merged))))
  }

  private fun <T> mergeById(local: List<T>, remote: List<T>, id: (T) -> String): List<T> =
    (remote + local).associateBy(id).values.sortedBy(id)

  /** Sync podcast subscriptions: union-merge the feed data and LWW-merge the
   *  subscription ledger, then keep only feeds the ledger marks subscribed (a
   *  missing entry defaults to subscribed, for shows added before the ledger
   *  existed). The two files are coupled (subscribed feeds depend on the ledger),
   *  so they're skipped/merged as a unit but uploaded individually. */
  private fun syncPodcasts(token: String, remote: Map<String, DriveFile>, prev: SyncState): SyncOutcome {
    val ledgerName = "podcast-subs.json"
    val feedsName = "podcasts.json"
    val ledgerRf = remote[ledgerName]
    val feedsRf = remote[feedsName]
    val ledgerLocal = subsFile.takeIf { it.exists() }?.readBytes()
    val feedsLocal = podcastsFile.takeIf { it.exists() }?.readBytes()
    val ledgerPrev = prev.files[ledgerName]
    val feedsPrev = prev.files[feedsName]

    val anyExists = ledgerRf != null || feedsRf != null || ledgerLocal != null || feedsLocal != null
    val changed =
      ledgerRf?.modifiedTime != ledgerPrev?.remoteModifiedTime ||
        feedsRf?.modifiedTime != feedsPrev?.remoteModifiedTime ||
        hash(ledgerLocal) != ledgerPrev?.localHash ||
        hash(feedsLocal) != feedsPrev?.localHash
    if (!anyExists || !changed) return SyncOutcome()

    val ledgerRemote = ledgerRf?.let { downloadBytes(token, it.id) }
    val feedsRemote = feedsRf?.let { downloadBytes(token, it.id) }
    val mergedLedger = mergeLedger(decodeLedger(ledgerLocal), decodeLedger(ledgerRemote))
    val unionFeeds = mergeById(decodeList<PodcastFeed>(feedsLocal), decodeList<PodcastFeed>(feedsRemote)) { it.id }
    val subscribed = unionFeeds.filter { mergedLedger[it.id]?.subscribed ?: true }

    val ledgerBytes = json.encodeToString(mergedLedger).toByteArray()
    val feedsBytes = json.encodeToString(subscribed).toByteArray()
    var pushed = 0
    var pulled = 0
    if (!ledgerBytes.contentEquals(ledgerLocal)) { subsFile.writeBytes(ledgerBytes); pulled++ }
    if (!feedsBytes.contentEquals(feedsLocal)) { podcastsFile.writeBytes(feedsBytes); pulled++ }
    var ledgerMt = ledgerRf?.modifiedTime
    var feedsMt = feedsRf?.modifiedTime
    if (!ledgerBytes.contentEquals(ledgerRemote)) { ledgerMt = upsert(token, ledgerName, ledgerRf?.id, ledgerBytes, "application/json"); pushed++ }
    if (!feedsBytes.contentEquals(feedsRemote)) { feedsMt = upsert(token, feedsName, feedsRf?.id, feedsBytes, "application/json"); pushed++ }

    return SyncOutcome(pushed, pulled, mapOf(
      ledgerName to FileState(ledgerMt, hash(ledgerBytes)),
      feedsName to FileState(feedsMt, hash(feedsBytes)),
    ))
  }

  private fun mergeLedger(a: Map<String, SubEntry>, b: Map<String, SubEntry>): Map<String, SubEntry> {
    val out = HashMap(a)
    for ((id, e) in b) {
      val cur = out[id]
      if (cur == null || e.ts > cur.ts) out[id] = e
    }
    return out
  }

  private fun decodeLedger(bytes: ByteArray?): Map<String, SubEntry> =
    bytes?.let { runCatching { json.decodeFromString<Map<String, SubEntry>>(String(it)) }.getOrNull() } ?: emptyMap()

  // MARK: - Local file mapping

  private val favoritesFile get() = File(filesDir, "favorites.json")
  private val programsFile get() = File(filesDir, "favorite-programs.json")
  private val indexFile get() = File(filesDir, "ai/index.json")
  private val podcastsFile get() = File(filesDir, "podcasts.json")
  private val subsFile get() = File(filesDir, "podcast-subs.json")

  private inline fun <reified T> decodeList(bytes: ByteArray?): List<T> =
    bytes?.let { runCatching { json.decodeFromString<List<T>>(String(it)) }.getOrNull() } ?: emptyList()

  private fun decodeMap(bytes: ByteArray?): Map<String, EpisodeRecord> =
    bytes?.let { runCatching { json.decodeFromString<Map<String, EpisodeRecord>>(String(it)) }.getOrNull() } ?: emptyMap()

  /** drive name -> local content file, for transcripts, handouts, cue sidecars,
   *  and translation sidecars. */
  private fun contentFiles(): Map<String, File> = buildMap {
    File(filesDir, "ai/transcripts").listFiles()?.forEach { put("transcript-${it.nameWithoutExtension}.txt", it) }
    File(filesDir, "ai/handouts").listFiles()?.forEach { put("handout-${it.nameWithoutExtension}.html", it) }
    File(filesDir, "ai/cues").listFiles()?.forEach { put("cues-${it.nameWithoutExtension}.json", it) }
    File(filesDir, "ai/translations").listFiles()?.forEach { put("translation-${it.nameWithoutExtension}.json", it) }
  }

  private fun isContentName(name: String) =
    (name.startsWith("transcript-") && name.endsWith(".txt")) ||
      (name.startsWith("handout-") && name.endsWith(".html")) ||
      (name.startsWith("cues-") && name.endsWith(".json")) ||
      (name.startsWith("translation-") && name.endsWith(".json"))

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
      name.startsWith("translation-") && name.endsWith(".json") -> {
        val id = name.removePrefix("translation-").removeSuffix(".json")
        File(filesDir, "ai/translations").apply { mkdirs() }.let { File(it, "$id.json").writeBytes(bytes) }
      }
    }
  }

  /** Content files (write-once): push local-only up, pull remote-only down, with
   *  bounded concurrency. Already-synced files transfer nothing. */
  private suspend fun syncContentFiles(token: String, remote: Map<String, DriveFile>): SyncOutcome = coroutineScope {
    val local = contentFiles()
    val gate = Semaphore(6) // be polite: cap concurrent transfers on the first sync
    val ups = local.filterKeys { it !in remote }.map { (name, file) ->
      async { gate.withPermit { upsert(token, name, null, file.readBytes(), "text/plain"); 1 } }
    }
    val downs = remote.values.filter { isContentName(it.name) && it.name !in local }.map { rf ->
      async { gate.withPermit { writeContent(rf.name, downloadBytes(token, rf.id)); 1 } }
    }
    SyncOutcome(pushed = ups.awaitAll().sum(), pulled = downs.awaitAll().sum())
  }

  // MARK: - Drive REST (over OkHttp)

  @Serializable private data class FileList(val files: List<DriveFile> = emptyList())
  @Serializable private data class DriveFile(val id: String, val name: String, val modifiedTime: String? = null)
  @Serializable private data class UploadMeta(val name: String, val parents: List<String>)
  @Serializable private data class UploadResult(val id: String = "", val modifiedTime: String? = null)

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

  /** Create the file (when [existingId] is null) or replace its content; returns
   *  the file's new Drive modifiedTime (the change token stored for next sync), or
   *  null if the response didn't carry it. */
  private fun upsert(token: String, name: String, existingId: String?, bytes: ByteArray, mime: String): String? {
    val media = bytes.toRequestBody(mime.toMediaType())
    val req = if (existingId == null) {
      val meta = json.encodeToString(UploadMeta(name, listOf("appDataFolder")))
      val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
        .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        .addPart(media)
        .build()
      Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,modifiedTime")
        .header("Authorization", "Bearer $token").post(body).build()
    } else {
      Request.Builder()
        .url("https://www.googleapis.com/upload/drive/v3/files/$existingId?uploadType=media&fields=id,modifiedTime")
        .header("Authorization", "Bearer $token").patch(media).build()
    }
    client.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) error("Drive upload ${resp.code}")
      return runCatching { json.decodeFromString<UploadResult>(resp.body!!.string()).modifiedTime }.getOrNull()
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
