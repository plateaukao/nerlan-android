package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage

/** Async cover image with rounded corners and a gray placeholder. */
@Composable
fun CoverImage(url: String?, size: Dp, modifier: Modifier = Modifier) {
  AsyncImage(
    model = url,
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier = modifier
      .size(size)
      .clip(RoundedCornerShape(size / 8))
      .background(MaterialTheme.colorScheme.surfaceVariant),
  )
}

fun formatTime(ms: Long): String {
  val s = (ms / 1000).toInt().coerceAtLeast(0)
  return "%d:%02d".format(s / 60, s % 60)
}

fun rateLabel(rate: Float): String =
  if (rate == rate.toInt().toFloat()) "${rate.toInt()}×" else "${rate}×".replace(".0×", "×")
