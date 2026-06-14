package com.example.nerlan.ui

import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Renders the saved HTML handout *fragment* in a WebView, shown as a full-screen
 * dialog over the player. The fragment is wrapped in a styled document at display
 * time so it can pick up the current light/dark theme (no extra WebView dep).
 */
@Composable
fun HandoutDialog(title: String, fragment: String, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      HandoutContent(title, fragment, onDismiss)
    }
  }
}

/** The handout body, shared by the phone dialog and the large-screen panel. */
@Composable
fun HandoutContent(title: String, fragment: String, onClose: () -> Unit) {
  val dark = isSystemInDarkTheme()
  val html = remember(fragment, title, dark) { handoutDocument(fragment, title, dark) }

  Column(Modifier.fillMaxSize()) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
      IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
      )
    }
    AndroidView(
      factory = { ctx ->
        WebView(ctx).apply {
          settings.javaScriptEnabled = false
          setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
      },
      update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
      modifier = Modifier.fillMaxSize(),
    )
  }
}

private fun handoutDocument(fragment: String, title: String, dark: Boolean): String {
  val bg = if (dark) "#1c1c1e" else "#ffffff"
  val fg = if (dark) "#f2f2f7" else "#1c1c1e"
  val border = if (dark) "#3a3a3c" else "#d1d1d6"
  val accent = "#0a84ff"
  return """
    <!DOCTYPE html>
    <html lang="zh-Hant">
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body { font-family: -apple-system, "PingFang TC", system-ui, sans-serif; font-size: 17px;
             line-height: 1.7; margin: 0; padding: 16px; color: $fg; background: $bg; word-wrap: break-word; }
      h1 { font-size: 1.4em; margin: 0 0 .6em; }
      h2 { font-size: 1.2em; margin-top: 1.6em; padding-bottom: .3em; border-bottom: 2px solid $accent; color: $accent; }
      h3 { font-size: 1.05em; }
      table { border-collapse: collapse; width: 100%; margin: .6em 0; }
      th, td { border: 1px solid $border; padding: 6px 8px; text-align: left; vertical-align: top; }
      th { background: rgba(10,132,255,0.12); }
      ul, ol { padding-left: 1.3em; } li { margin: .25em 0; }
      ruby rt { font-size: .6em; }
      code { background: rgba(120,120,128,0.2); padding: .1em .3em; border-radius: 4px; }
    </style>
    </head>
    <body>
    <h1>${escapeHtml(title)}</h1>
    $fragment
    </body>
    </html>
  """.trimIndent()
}

private fun escapeHtml(s: String): String =
  s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
