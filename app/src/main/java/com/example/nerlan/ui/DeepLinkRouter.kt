package com.example.nerlan.ui

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Routes `nerlan://` URIs (opened by the home-screen widgets) into the UI.
 *
 * [MainScreen] owns its navigation as plain local state, so rather than model the
 * whole tree this publishes one-shot *requests* that the screen consumes and
 * clears — mirroring the iOS DeepLinkRouter.
 */
object DeepLinkRouter {
  /** Tab index to select (0 節目 / 1 收藏 / 2 下載 / 3 AI). */
  private val _tab = MutableStateFlow<Int?>(null)
  val tab: StateFlow<Int?> = _tab

  /** A show to open: id plus whether it's a podcast feed rather than a program. */
  private val _pendingShow = MutableStateFlow<Pair<String, Boolean>?>(null)
  val pendingShow: StateFlow<Pair<String, Boolean>?> = _pendingShow

  /** Bumped to ask the screen to present the full player sheet. */
  private val _openPlayer = MutableStateFlow(0)
  val openPlayer: StateFlow<Int> = _openPlayer

  fun handle(uri: Uri?) {
    if (uri == null || uri.scheme != "nerlan") return
    when (uri.host) {
      "player" -> _openPlayer.value += 1
      "show" -> {
        val id = uri.getQueryParameter("id") ?: return
        _tab.value = 0
        _pendingShow.value = id to (uri.getQueryParameter("podcast") == "1")
      }
      "tab" -> _tab.value = when (uri.getQueryParameter("name")) {
        "favorites" -> 1
        "downloads" -> 2
        "ai" -> 3
        else -> 0
      }
    }
  }

  fun consumeTab() { _tab.value = null }

  fun consumeShow() { _pendingShow.value = null }
}
