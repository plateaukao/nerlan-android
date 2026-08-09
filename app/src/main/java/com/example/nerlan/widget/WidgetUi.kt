package com.example.nerlan.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.example.nerlan.R

// Shared pieces for the four widgets, so they read as one family.

/** Cover art with the app's music-note placeholder. */
@Composable
fun WidgetCover(bitmap: Bitmap?, sizeDp: Int, corner: Int = 8, onClick: Action? = null) {
  var modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(corner.dp)
  if (onClick != null) modifier = modifier.clickable(onClick)
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    if (bitmap != null) {
      Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = GlanceModifier.fillMaxSize(),
      )
    } else {
      Box(
        modifier = GlanceModifier.fillMaxSize()
          .background(GlanceTheme.colors.secondaryContainer)
          .cornerRadius(corner.dp),
        contentAlignment = Alignment.Center,
      ) {
        Image(
          provider = ImageProvider(R.drawable.ic_widget_note),
          contentDescription = null,
          modifier = GlanceModifier.size((sizeDp / 2.6f).dp),
        )
      }
    }
  }
}

/** Round icon button, the widgets' only control affordance. */
@Composable
fun WidgetIconButton(resId: Int, onClick: Action, sizeDp: Int = 36, filled: Boolean = true) {
  Box(
    modifier = GlanceModifier
      .size(sizeDp.dp)
      .cornerRadius((sizeDp / 2).dp)
      .then(if (filled) GlanceModifier.background(GlanceTheme.colors.secondaryContainer) else GlanceModifier)
      .clickable(onClick),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      provider = ImageProvider(resId),
      contentDescription = null,
      modifier = GlanceModifier.size((sizeDp * 0.5f).dp),
    )
  }
}

/** Thin progress track. Glance's LinearProgressIndicator can't be given a height,
 *  so this is a two-Box bar — which also keeps it visually identical to iOS. */
@Composable
fun WidgetProgressBar(progress: Float?, widthDp: Int, heightDp: Int = 4) {
  Box(
    modifier = GlanceModifier
      .width(widthDp.dp)
      .height(heightDp.dp)
      .cornerRadius((heightDp / 2).dp)
      .background(GlanceTheme.colors.surfaceVariant)
  ) {
    val filled = ((progress ?: 0f).coerceIn(0f, 1f) * widthDp).toInt()
    if (filled > 0) {
      Box(
        modifier = GlanceModifier
          .width(filled.dp)
          .height(heightDp.dp)
          .cornerRadius((heightDp / 2).dp)
          .background(GlanceTheme.colors.primary)
      ) {}
    }
  }
}

@Composable
fun WidgetTitle(text: String, maxLines: Int = 2) {
  Text(
    text = text,
    maxLines = maxLines,
    style = TextStyle(
      color = GlanceTheme.colors.onSurface,
      fontSize = 13.sp,
      fontWeight = FontWeight.Medium,
    ),
  )
}

@Composable
fun WidgetCaption(text: String, maxLines: Int = 1) {
  Text(
    text = text,
    maxLines = maxLines,
    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
  )
}

@Composable
fun WidgetHeader(text: String) {
  Text(
    text = text,
    style = TextStyle(
      color = GlanceTheme.colors.onSurfaceVariant,
      fontSize = 12.sp,
      fontWeight = FontWeight.Medium,
    ),
    modifier = GlanceModifier.padding(bottom = 6.dp),
  )
}

/** Shared "nothing to show yet" panel, so every widget fails the same way. */
@Composable
fun WidgetEmptyState(message: String, onClick: Action) {
  Column(
    modifier = GlanceModifier.fillMaxSize().padding(12.dp).clickable(onClick),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
      provider = ImageProvider(R.drawable.ic_widget_note),
      contentDescription = null,
      modifier = GlanceModifier.size(28.dp),
    )
    Spacer(GlanceModifier.height(6.dp))
    Text(
      text = message,
      style = TextStyle(
        color = GlanceTheme.colors.onSurfaceVariant,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
      ),
    )
  }
}

/** Outer frame every widget shares: rounded surface, uniform padding, and a
 *  small app badge in the top-end corner (tapping it opens the app) so the
 *  widgets are recognizably NerLan's at a glance. */
@Composable
fun WidgetSurface(content: @Composable () -> Unit) {
  Box(
    modifier = GlanceModifier
      .fillMaxSize()
      .background(GlanceTheme.colors.widgetBackground)
      .cornerRadius(16.dp)
      .padding(12.dp),
    contentAlignment = Alignment.TopEnd,
  ) {
    Box(modifier = GlanceModifier.fillMaxSize()) { content() }
    Image(
      provider = ImageProvider(R.mipmap.ic_launcher_round),
      contentDescription = "NerLan",
      modifier = GlanceModifier.size(16.dp).clickable(openTabAction("programs")),
    )
  }
}

@Composable
fun RowSpacer(dp: Int) = Spacer(GlanceModifier.width(dp.dp))

@Composable
fun ColSpacer(dp: Int) = Spacer(GlanceModifier.height(dp.dp))

/** Fetch every distinct cover once, keyed by URL. */
suspend fun loadCovers(context: Context, urls: List<String?>, sizePx: Int): Map<String, Bitmap> {
  val out = HashMap<String, Bitmap>()
  for (url in urls.filterNotNull().distinct()) {
    loadCoverBitmap(context, url, sizePx)?.let { out[url] = it }
  }
  return out
}

fun Map<String, Bitmap>.cover(url: String?): Bitmap? = url?.let { this[it] }

/** Row layout shared by the queue and recent-show lists. */
@Composable
fun WidgetListRow(
  bitmap: Bitmap?,
  title: String,
  caption: String,
  progress: Float?,
  openAction: Action,
  buttonRes: Int,
  buttonAction: Action,
) {
  Row(
    modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    WidgetCover(bitmap, sizeDp = 40, onClick = openAction)
    RowSpacer(10)
    Column(modifier = GlanceModifier.defaultWeight().clickable(openAction)) {
      WidgetCaption(caption)
      WidgetTitle(title, maxLines = 1)
      if (progress != null) {
        ColSpacer(3)
        WidgetProgressBar(progress, widthDp = 120, heightDp = 3)
      }
    }
    RowSpacer(8)
    WidgetIconButton(buttonRes, buttonAction, sizeDp = 32)
  }
}
