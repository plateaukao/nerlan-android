package com.example.nerlan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.Attachment
import com.example.nerlan.data.ChannelPlusApi
import com.example.nerlan.data.DownloadManager
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Reader for an episode's PDF handout (講義). Shown as a full-screen dialog over
 * the player so the user can read along while the episode keeps playing. Prefers
 * the downloaded copy; otherwise fetches the PDF on demand. Pages are rendered
 * lazily with the platform [PdfRenderer] — no third-party dependency.
 */
@Composable
fun AttachmentViewer(title: String, attachments: List<Attachment>, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      AttachmentContent(title, attachments, onDismiss)
    }
  }
}

/** The PDF reader body, shared by the phone dialog and the large-screen panel. */
@Composable
fun AttachmentContent(
  title: String,
  attachments: List<Attachment>,
  onClose: () -> Unit,
  leading: @Composable () -> Unit = {},
) {
  Column(Modifier.fillMaxSize()) {
    var selected by remember(attachments) { mutableStateOf(attachments.firstOrNull()) }
    var switcherOpen by remember { mutableStateOf(false) }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
      leading()
      IconButton(onClick = onClose) {
        Icon(Icons.Filled.Close, contentDescription = "關閉")
      }
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
      )
      // Let the user switch between handouts when an episode has several.
      if (attachments.size > 1) {
        Box {
          IconButton(onClick = { switcherOpen = true }) {
            Icon(Icons.Filled.UnfoldMore, contentDescription = "選擇附件")
          }
          DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
            attachments.forEach { attachment ->
              DropdownMenuItem(
                text = { Text(attachment.displayName + if (attachment == selected) " ✓" else "") },
                onClick = {
                  selected = attachment
                  switcherOpen = false
                },
              )
            }
          }
        }
      }
    }

    val attachment = selected
    if (attachment == null) {
      CenteredMessage("沒有附件")
    } else {
      PdfReader(attachment)
    }
  }
}

@Composable
private fun PdfReader(attachment: Attachment) {
  val context = LocalContext.current
  val downloads = NerLanApp.instance.downloads
  val widthPx = with(LocalDensity.current) {
    context.resources.configuration.screenWidthDp.dp.roundToPx()
  }

  // Open the document once per attachment; close it when this leaves composition.
  val state by produceState<PdfLoadState>(PdfLoadState.Loading, attachment.attachmentKey) {
    val file = withContext(Dispatchers.IO) { resolveFile(context, attachment, downloads) }
    val document = file?.let { runCatching { PdfDocument(it) }.getOrNull() }
    value = if (document != null) PdfLoadState.Ready(document) else PdfLoadState.Failed
    awaitDispose { document?.close() }
  }

  when (val s = state) {
    PdfLoadState.Loading -> CenteredMessage(content = { CircularProgressIndicator() })
    PdfLoadState.Failed -> CenteredMessage("無法載入附件：${attachment.displayName}")
    is PdfLoadState.Ready -> LazyColumn(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      items(s.document.pageCount) { index ->
        val page by produceState<ImageBitmap?>(null, attachment.attachmentKey, index, widthPx) {
          value = runCatching { s.document.renderPage(index, widthPx) }.getOrNull()
        }
        val bitmap = page
        if (bitmap != null) {
          Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          // Placeholder sized to a typical A4 page until the real page renders.
          Box(
            Modifier.fillMaxWidth().aspectRatio(0.707f),
            contentAlignment = Alignment.Center,
          ) { CircularProgressIndicator() }
        }
      }
    }
  }
}

@Composable
private fun CenteredMessage(text: String? = null, content: @Composable () -> Unit = {}) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if (text != null) {
      Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
      content()
    }
  }
}

private sealed interface PdfLoadState {
  data object Loading : PdfLoadState
  data object Failed : PdfLoadState
  data class Ready(val document: PdfDocument) : PdfLoadState
}

/**
 * Wraps [PdfRenderer], which is not thread-safe and allows only one open page at
 * a time — a [Mutex] serializes page rendering.
 */
private class PdfDocument(file: File) : Closeable {
  private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  private val renderer = PdfRenderer(descriptor)
  private val mutex = Mutex()

  val pageCount: Int get() = renderer.pageCount

  suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap = mutex.withLock {
    withContext(Dispatchers.IO) {
      renderer.openPage(index).use { page ->
        val scale = widthPx.toFloat() / page.width
        val heightPx = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE) // PDF pages can be transparent; composite on white
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap.asImageBitmap()
      }
    }
  }

  override fun close() {
    runCatching { renderer.close() }
    runCatching { descriptor.close() }
  }
}

/** Resolve a local PDF file: the downloaded copy if present, else fetch to cache. */
private fun resolveFile(context: Context, attachment: Attachment, downloads: DownloadManager): File? {
  downloads.localAttachmentPath(attachment)?.let { return File(it) }
  val url = attachment.remoteUrl ?: return null
  val cached = File(context.cacheDir, "att-${attachment.attachmentKey}.${attachment.fileExtension}")
  if (cached.exists() && cached.length() > 0) return cached
  return try {
    val request = Request.Builder().url(url).build()
    ChannelPlusApi.client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return null
      val body = response.body ?: return null
      body.byteStream().use { input -> cached.outputStream().use { output -> input.copyTo(output) } }
    }
    cached
  } catch (_: Exception) {
    cached.delete()
    null
  }
}
