package com.example.nerlan.widget

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.res.ResourcesCompat
import com.example.nerlan.R
import kotlin.math.roundToInt

/**
 * Shared pieces for the four widgets, so they read as one family. Plain
 * RemoteViews: the layouts live in res/layout/widget_*.xml and these helpers fill
 * them in. (Glance used to do this; it cost ~900 generated layouts and the
 * WorkManager/Room/DataStore stack — a fifth of the APK — for four widgets.)
 */
object WidgetViews {
  fun dp(context: Context, value: Float): Int =
    (value * context.resources.displayMetrics.density).roundToInt()

  /**
   * Cover art rounded to [cornerDp], or the app's music-note placeholder, as a
   * bitmap that displays at exactly [sizeDp] — the bitmap's density is set so a
   * wrap_content ImageView sizes itself to it. Pixels are capped at [maxPx]: the
   * host scales up, and RemoteViews ferries every bitmap across processes.
   */
  fun cover(context: Context, source: Bitmap?, sizeDp: Int, cornerDp: Int = 8, maxPx: Int = 176): Bitmap {
    val wantPx = dp(context, sizeDp.toFloat()).coerceAtLeast(1)
    val px = minOf(wantPx, maxPx)
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val rect = RectF(0f, 0f, px.toFloat(), px.toFloat())
    val radius = cornerDp * px.toFloat() / sizeDp
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (source != null && source.width > 0 && source.height > 0) {
      val scale = maxOf(px.toFloat() / source.width, px.toFloat() / source.height)
      val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate((px - source.width * scale) / 2f, (px - source.height * scale) / 2f)
      }
      paint.shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { setLocalMatrix(matrix) }
      canvas.drawRoundRect(rect, radius, radius, paint)
    } else {
      paint.color = context.getColor(R.color.widget_secondary_container)
      canvas.drawRoundRect(rect, radius, radius, paint)
      val icon = (px / 2.6f).roundToInt()
      ResourcesCompat.getDrawable(context.resources, R.drawable.ic_widget_note, context.theme)?.let {
        val left = (px - icon) / 2
        it.setBounds(left, left, left + icon, left + icon)
        it.draw(canvas)
      }
    }
    // Display size = px * (device dpi / bitmap density); solve for sizeDp.
    out.density = (px * 160f / sizeDp).roundToInt()
    return out
  }

  /** Outer frame every widget shares; [content] goes inside the padding. */
  fun frame(context: Context, content: RemoteViews): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_frame).apply {
      removeAllViews(R.id.widget_content)
      addView(R.id.widget_content, content)
      setOnClickPendingIntent(R.id.widget_badge, WidgetIntents.openTab(context, "programs"))
    }

  /** Shared "nothing to show yet" panel, so every widget fails the same way. */
  fun empty(context: Context, message: String, onClick: PendingIntent): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_empty).apply {
      setTextViewText(R.id.empty_text, message)
      setOnClickPendingIntent(R.id.empty_root, onClick)
    }

  /** Row layout shared by the queue and recent-show lists. */
  fun listRow(
    context: Context,
    bitmap: Bitmap?,
    title: String,
    caption: String,
    progress: Float?,
    open: PendingIntent,
    buttonRes: Int,
    buttonClick: PendingIntent,
  ): RemoteViews = RemoteViews(context.packageName, R.layout.widget_list_row).apply {
    setImageViewBitmap(R.id.row_cover, cover(context, bitmap, sizeDp = 40))
    setOnClickPendingIntent(R.id.row_cover, open)
    setTextViewText(R.id.row_caption, caption)
    setTextViewText(R.id.row_title, title)
    setOnClickPendingIntent(R.id.row_text, open)
    progress(this, R.id.row_progress, progress)
    setImageViewResource(R.id.row_button, buttonRes)
    setOnClickPendingIntent(R.id.row_button, buttonClick)
  }

  /** Thin progress track; hidden when there is nothing to show. */
  fun progress(views: RemoteViews, viewId: Int, progress: Float?) {
    if (progress == null) {
      views.setViewVisibility(viewId, View.GONE)
    } else {
      views.setViewVisibility(viewId, View.VISIBLE)
      views.setProgressBar(viewId, 1000, (progress.coerceIn(0f, 1f) * 1000).roundToInt(), false)
    }
  }
}

/** Fetch every distinct cover once, keyed by URL. */
suspend fun loadCovers(context: Context, urls: List<String?>, sizePx: Int): Map<String, Bitmap> {
  val out = HashMap<String, Bitmap>()
  for (url in urls.filterNotNull().distinct()) {
    loadCoverBitmap(context, url, sizePx)?.let { out[url] = it }
  }
  return out
}

fun Map<String, Bitmap>.cover(url: String?): Bitmap? = url?.let { this[it] }
