package com.example.nerlan.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.nerlan.BuildConfig
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationManagementActivity
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * The auth seam for Drive sync (see ADR `nerlan-android-drive-sync-without-gms`).
 *
 * Splits *"get a `drive.appdata` Bearer token"* from *"how we obtained it"* so a
 * device with broken/absent Google Play Services can fall back to a browser OAuth
 * flow while GMS devices keep their existing session (no cross-device re-login).
 * Both paths converge on the same access token against the same Drive REST API and
 * the same `appDataFolder` — so the sync engine in [DriveSync] never changes.
 *
 * The selection is **failure-driven, not availability-driven**: a partial GMS stub
 * (the Hisense A7) passes the static availability probe yet dies at the auth
 * broker, so we *attempt* GMS and fall back on the broker-failure signature,
 * remembering the verdict. A manual override is the guaranteed escape when
 * auto-classification can't tell a dead broker from a flaky network.
 *
 * Owner setup for the browser path: a custom-scheme OAuth client created in the
 * **same GCP project** as the Android client, wired through the `DRIVE_OAUTH_*`
 * fields in `build.gradle.kts`. Until then [BrowserTokenProvider.isConfigured] is
 * false and the UI hides the browser option.
 */

/** How the token was (or would be) obtained. Persisted as the sticky verdict. */
enum class AuthMode { AUTO, GMS, BROWSER }

/** Classification of a failed GMS sign-in or token fetch — decides the response. */
enum class GmsFailure {
  /** The user backed out of the sign-in picker (`SIGN_IN_CANCELLED`). Do nothing —
   *  not an error, and definitely not a reason to launch the browser flow. */
  CANCELLED,
  /** Network-ish; retry, stay on GMS. (See the IOException caveat in [DriveAuth].) */
  TRANSIENT,
  /** Needs a consent/resolution UI; surface it, stay on GMS. */
  RECOVERABLE,
  /** `DEVELOPER_ERROR` — wrong SHA-1/client in GCP. A config bug, NOT a device
   *  problem: the browser flow would fail too, so do not fall back. */
  CONFIG,
  /** The broker is dead (the A7 case): `SecurityException`, `INTERNAL_ERROR`,
   *  `SIGN_IN_FAILED`, etc. Fall back to the browser and remember it. */
  BROKEN,
}

/** Thrown when a stored browser session can't be refreshed (refresh token revoked
 *  or expired) and the user must sign in through the browser again — the browser
 *  analog of GMS's [UserRecoverableAuthException]. */
class ReauthRequired(cause: Throwable) : Exception(cause)

/** The one thing the sync engine needs. An implementation refreshes silently when
 *  it can and throws when interactive sign-in is required; [DriveAuth] classifies
 *  the throwable. */
interface TokenProvider {
  /** Account label for the Settings UI, or null when signed out. */
  val email: String?

  /** A valid access token for [DriveSync.SCOPE]. Throws on failure. */
  suspend fun accessToken(): String

  fun signOut()
}

/** Current behavior, extracted behind the seam: a GoogleSignIn session plus a
 *  [GoogleAuthUtil] token. Unchanged for the Pixel / GoColor7 / any healthy GMS. */
class GmsTokenProvider(private val context: Context) : TokenProvider {
  override val email: String?
    get() = GoogleSignIn.getLastSignedInAccount(context)?.email

  override suspend fun accessToken(): String = withContext(Dispatchers.IO) {
    val account = GoogleSignIn.getLastSignedInAccount(context)?.account
      ?: error("尚未登入 Google 帳戶")
    GoogleAuthUtil.getToken(context, account, "oauth2:${DriveSync.SCOPE}")
  }

  override fun signOut() {
    DriveSync.signInClient(context).signOut()
  }
}

/**
 * Browser Authorization-Code + PKCE via AppAuth, for GMS-less devices. AppAuth
 * picks a Custom Tab when a capable browser exists and **falls back to the default
 * standalone browser otherwise** (no Chrome required). The refresh token lives in
 * app-private prefs (as a serialized [AuthState]); the access token is renewed
 * without opening a browser.
 */
class BrowserTokenProvider(private val context: Context) : TokenProvider {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val authService by lazy { AuthorizationService(context) }
  private var authState: AuthState = loadState()

  /** False until the owner fills in the `DRIVE_OAUTH_*` placeholders in gradle. */
  val isConfigured: Boolean
    get() = !BuildConfig.DRIVE_OAUTH_CLIENT_ID.startsWith("REPLACE")

  override val email: String?
    get() = prefs.getString(KEY_EMAIL, null)

  override suspend fun accessToken(): String {
    check(authState.isAuthorized) { "尚未透過瀏覽器登入 Google 帳戶" }
    return suspendCancellableCoroutine { cont ->
      authState.performActionWithFreshTokens(authService) { token, _, ex ->
        persist()
        when {
          token != null -> cont.resume(token)
          // A token-endpoint error (invalid_grant: refresh token revoked or expired
          // — e.g. the consent screen is still in "Testing") is permanent: drop the
          // dead session so the UI prompts a fresh browser login. Network blips are
          // TYPE_GENERAL_ERROR and are left intact to retry.
          ex != null && ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> {
            signOut()
            cont.resumeWithException(ReauthRequired(ex))
          }
          else -> cont.resumeWithException(ex ?: IOException("瀏覽器授權更新失敗"))
        }
      }
    }
  }

  override fun signOut() {
    authState = AuthState()
    prefs.edit().remove(KEY_STATE).remove(KEY_EMAIL).apply()
  }

  /** Step 1 of interactive auth: the Intent that opens the authorize URL. Settings
   *  launches it via `StartActivityForResult`.
   *
   *  Rather than let AppAuth pick one browser for us (`getAuthorizationRequestIntent`
   *  targets the default browser / a Custom Tab), we wrap a package-less ACTION_VIEW
   *  in `Intent.createChooser` so the user explicitly picks which browser handles the
   *  Google sign-in. We still hand it to AppAuth's `createStartForResultIntent` — the
   *  same for-result entry point — so the redirect/response handling is unchanged. */
  fun authRequestIntent(): Intent {
    check(isConfigured) { "尚未設定瀏覽器 OAuth 用戶端（build.gradle DRIVE_OAUTH_CLIENT_ID）" }
    val request = AuthorizationRequest.Builder(
      SERVICE_CONFIG,
      BuildConfig.DRIVE_OAUTH_CLIENT_ID,
      ResponseTypeValues.CODE,
      Uri.parse(BuildConfig.DRIVE_OAUTH_REDIRECT),
    )
      .setScopes(DriveSync.SCOPE, "openid", "email")
      // consent (via the dedicated builder — AppAuth rejects "prompt" in the
      // additional-parameters map) + access_type=offline so Google returns a
      // refresh token we can renew silently.
      .setPromptValues(AuthorizationRequest.Prompt.CONSENT)
      .setAdditionalParameters(mapOf("access_type" to "offline"))
      .build()
    val chooser = Intent.createChooser(
      Intent(Intent.ACTION_VIEW, request.toUri()),
      "選擇瀏覽器登入 Google 帳戶",
    )
    return AuthorizationManagementActivity.createStartForResultIntent(context, request, chooser)
  }

  /** Step 2: handle the custom-scheme redirect, exchange code+verifier for tokens,
   *  persist the refresh token. Returns true on success. */
  suspend fun handleAuthResponse(data: Intent?): Boolean {
    val resp = data?.let { AuthorizationResponse.fromIntent(it) } ?: return false
    authState.update(resp, data.let { AuthorizationException.fromIntent(it) })
    persist()
    return suspendCancellableCoroutine { cont ->
      authService.performTokenRequest(resp.createTokenExchangeRequest()) { tokenResp, ex ->
        authState.update(tokenResp, ex)
        persist()
        if (tokenResp != null) {
          tokenResp.idToken?.let { saveEmail(it) }
          cont.resume(true)
        } else {
          cont.resume(false)
        }
      }
    }
  }

  private fun persist() {
    prefs.edit().putString(KEY_STATE, authState.jsonSerializeString()).apply()
  }

  private fun loadState(): AuthState =
    prefs.getString(KEY_STATE, null)
      ?.let { runCatching { AuthState.jsonDeserialize(it) }.getOrNull() }
      ?: AuthState()

  /** Pull the `email` claim out of the id_token JWT and cache it for the UI. */
  private fun saveEmail(idToken: String) {
    emailFromIdToken(idToken)?.let { prefs.edit().putString(KEY_EMAIL, it).apply() }
  }

  private fun emailFromIdToken(idToken: String): String? = runCatching {
    val payload = idToken.split(".")[1]
    val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    Json.parseToJsonElement(String(bytes)).jsonObject["email"]?.jsonPrimitive?.contentOrNull
  }.getOrNull()

  companion object {
    private val SERVICE_CONFIG = AuthorizationServiceConfiguration(
      Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
      Uri.parse("https://oauth2.googleapis.com/token"),
    )
    private const val KEY_STATE = "drive_browser_auth_state"
    private const val KEY_EMAIL = "drive_browser_email"
  }
}

/**
 * Picks the provider, classifies GMS failures, and remembers a structural failure
 * (or a manual override) so we stop probing a dead broker. [DriveSync] holds one of
 * these and calls [accessToken]; the Settings screen drives [browserAuthIntent] /
 * [completeBrowserSignIn] / [forceBrowser].
 */
class DriveAuth(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val appContext = context.applicationContext
  private val gms = GmsTokenProvider(appContext)
  private val browser by lazy { BrowserTokenProvider(appContext) }

  /** Consecutive non-classifiable IOExceptions from the GMS token fetch. The crux
   *  the A7 exposes: `GoogleAuthUtil.getToken` throws plain `IOException` for BOTH a
   *  flaky network AND a dead broker, so we can't tell them apart from one failure.
   *  We retry a few times (could be network), then treat it as [GmsFailure.BROKEN]
   *  so a genuinely dead broker eventually falls back instead of failing forever. */
  private var consecutiveGmsIo = 0

  var mode: AuthMode
    get() = runCatching { AuthMode.valueOf(prefs.getString(KEY_AUTH_MODE, null) ?: "") }
      .getOrDefault(AuthMode.AUTO)
    private set(value) {
      prefs.edit().putString(KEY_AUTH_MODE, value.name).apply()
    }

  val email: String? get() = activeProvider().email

  /** Whether the browser path is set up (gradle placeholders replaced). */
  val browserConfigured: Boolean get() = browser.isConfigured

  fun signOut() {
    activeProvider().signOut()
    consecutiveGmsIo = 0
    if (mode == AuthMode.BROWSER) resetToAuto()
  }

  /** Whether a sync right now would use the browser provider. Mode-based so GMS-only
   *  devices never touch the `browser` lazy (no needless AppAuth/Custom Tabs init). */
  private fun useBrowser(): Boolean = when (mode) {
    AuthMode.BROWSER -> true
    AuthMode.GMS -> false
    AuthMode.AUTO -> !gmsLooksUsable()
  }

  /** The provider a (silent) sync would use right now. */
  fun activeProvider(): TokenProvider = if (useBrowser()) browser else gms

  /**
   * A valid access token, falling back GMS -> browser on a structural failure.
   * This is the only method [DriveSync] needs (replacing its old `:120-121`).
   */
  suspend fun accessToken(): String {
    if (useBrowser()) return browser.accessToken()
    return try {
      gms.accessToken().also { consecutiveGmsIo = 0 }
    } catch (t: Throwable) {
      when (classify(t)) {
        // CANCELLED can't arise from a silent token fetch, but keep it in the
        // surface-don't-fall-back group for exhaustiveness.
        GmsFailure.CANCELLED, GmsFailure.RECOVERABLE, GmsFailure.CONFIG -> throw t
        GmsFailure.TRANSIENT ->
          if (++consecutiveGmsIo >= IO_FALLBACK_THRESHOLD) fallBackToBrowser() else throw t
        GmsFailure.BROKEN -> fallBackToBrowser()
      }
    }
  }

  private suspend fun fallBackToBrowser(): String {
    mode = AuthMode.BROWSER // sticky: stop probing the dead broker
    return browser.accessToken()
  }

  // Interactive browser sign-in, driven by the Settings screen.
  fun browserAuthIntent(): Intent = browser.authRequestIntent()

  suspend fun completeBrowserSignIn(data: Intent?): Boolean =
    browser.handleAuthResponse(data).also { if (it) mode = AuthMode.BROWSER }

  /** Manual override from Settings ("改用瀏覽器登入"), and the way back. */
  fun forceBrowser() { mode = AuthMode.BROWSER }
  fun resetToAuto() { mode = AuthMode.AUTO; consecutiveGmsIo = 0 }

  /**
   * Static fast-skip ONLY. A definitive MISSING/INVALID/DISABLED means don't even
   * try GMS. SUCCESS does NOT prove the broker works (the A7 returns SUCCESS for a
   * broken stub) — that only surfaces at [accessToken] and is handled by [classify].
   */
  private fun gmsLooksUsable(): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

  /** Classify a throwable from a GMS sign-in result or token fetch. Order matters:
   *  [UserRecoverableAuthException] extends [GoogleAuthException], so test it first.
   *  The exact structural code set should be tuned on the real A7. */
  fun classify(t: Throwable): GmsFailure = when {
    t is UserRecoverableAuthException -> GmsFailure.RECOVERABLE
    t is ApiException && t.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> GmsFailure.CANCELLED
    t is ApiException && t.statusCode == GoogleSignInStatusCodes.DEVELOPER_ERROR -> GmsFailure.CONFIG
    t is ApiException && t.statusCode == CommonStatusCodes.NETWORK_ERROR -> GmsFailure.TRANSIENT
    t is GoogleAuthException -> GmsFailure.BROKEN // non-recoverable broker/auth failure
    t is IOException -> GmsFailure.TRANSIENT // ambiguous: network OR dead broker — see consecutiveGmsIo
    else -> GmsFailure.BROKEN // other ApiException codes, SecurityException, etc.
  }

  companion object {
    private const val KEY_AUTH_MODE = "drive_auth_mode"

    /** Retries before an ambiguous IOException is treated as a dead broker. */
    private const val IO_FALLBACK_THRESHOLD = 3
  }
}
