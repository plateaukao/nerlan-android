package com.example.nerlan.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class WidgetKind(val receiver: Class<out AppWidgetProvider>) {
  UP_NEXT(UpNextWidgetReceiver::class.java),
  RECENT_SHOWS(RecentShowsWidgetReceiver::class.java),
  SHOWS(ShowsWidgetReceiver::class.java),
  STATS(StatsWidgetReceiver::class.java),
}

/**
 * Builds the model once, then draws every requested widget instance at its real
 * size and pushes it to the launcher.
 *
 * Size: on API 31+ the launcher lists every size the widget can take
 * (portrait, landscape, and any it may be resized to), so a RemoteViews is built
 * per size and the launcher picks the matching one — the equivalent of Glance's
 * SizeMode.Exact. Below that the options only carry a portrait/landscape pair.
 */
object WidgetRenderer {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** From a receiver callback: render in the background but release the
   *  broadcast within its budget even if the render is slow. */
  fun update(context: Context, kind: WidgetKind, ids: IntArray, pending: BroadcastReceiver.PendingResult?) {
    val app = context.applicationContext
    scope.launch {
      val job = launch { runCatching { render(app, mapOf(kind to ids)) } }
      withTimeoutOrNull(9_000) { job.join() }
      pending?.finish()
    }
  }

  /** Every placed instance of every widget. */
  suspend fun renderAll(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val targets = WidgetKind.entries
      .associateWith { manager.getAppWidgetIds(ComponentName(context, it.receiver)) }
      .filterValues { it.isNotEmpty() }
    if (targets.isNotEmpty()) render(context, targets)
  }

  suspend fun render(context: Context, targets: Map<WidgetKind, IntArray>) {
    val manager = AppWidgetManager.getInstance(context)
    val model = WidgetModelBuilder.build(context)
    val covers = loadCovers(context, coverUrls(model, targets.keys), 176)
    for ((kind, ids) in targets) {
      for (id in ids) {
        runCatching {
          val views = forSizes(context, manager, id) { size -> draw(context, kind, id, size, model, covers) }
          manager.updateAppWidget(id, views)
        }
      }
    }
  }

  private fun coverUrls(model: WidgetModel, kinds: Set<WidgetKind>): List<String?> = buildList {
    if (WidgetKind.UP_NEXT in kinds) {
      add(model.lead?.coverUrl)
      model.upNext.take(UpNextWidget.MAX_NEXT + 1).forEach { add(it.coverUrl) }
    }
    if (WidgetKind.RECENT_SHOWS in kinds) model.recents.take(RecentShowsWidget.MAX_ROWS).forEach { add(it.coverUrl) }
    if (WidgetKind.SHOWS in kinds) (model.shows + model.recents).take(24).forEach { add(it.coverUrl) }
  }

  private fun draw(
    context: Context, kind: WidgetKind, id: Int, size: SizeF, model: WidgetModel, covers: Map<String, Bitmap>,
  ): RemoteViews = when (kind) {
    WidgetKind.UP_NEXT -> UpNextWidget.render(context, size, model, covers)
    WidgetKind.RECENT_SHOWS -> RecentShowsWidget.render(context, size, model, covers)
    WidgetKind.SHOWS -> ShowsWidget.render(context, id, size, model, covers)
    WidgetKind.STATS -> StatsWidget.render(context, size, model)
  }

  private fun forSizes(
    context: Context, manager: AppWidgetManager, id: Int, build: (SizeF) -> RemoteViews,
  ): RemoteViews {
    val options: Bundle = manager.getAppWidgetOptions(id)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
      if (!sizes.isNullOrEmpty()) return RemoteViews(sizes.associateWith { build(it) })
    }
    // Fallback: the provider's minimums, in dp.
    val density = context.resources.displayMetrics.density
    val info = manager.getAppWidgetInfo(id)
    val defaultW = (info?.minWidth ?: 0) / density
    val defaultH = (info?.minHeight ?: 0) / density
    fun dim(key: String, default: Float): Float =
      options.getInt(key, 0).takeIf { it > 0 }?.toFloat() ?: default
    val portrait = SizeF(
      dim(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultW),
      dim(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defaultH),
    )
    val landscape = SizeF(
      dim(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, defaultW),
      dim(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultH),
    )
    return if (portrait == landscape) build(portrait) else RemoteViews(build(landscape), build(portrait))
  }
}

/** The manifest-registered providers; each just names its widget. */
abstract class NerLanWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    WidgetRenderer.update(context, kind, appWidgetIds, goAsync())
  }

  override fun onAppWidgetOptionsChanged(
    context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
  ) {
    WidgetRenderer.update(context, kind, intArrayOf(appWidgetId), goAsync())
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    if (kind == WidgetKind.SHOWS) appWidgetIds.forEach { ShowsWidgetPrefs.clear(context, it) }
  }
}

class UpNextWidgetReceiver : NerLanWidgetProvider(WidgetKind.UP_NEXT)
class RecentShowsWidgetReceiver : NerLanWidgetProvider(WidgetKind.RECENT_SHOWS)
class ShowsWidgetReceiver : NerLanWidgetProvider(WidgetKind.SHOWS)
class StatsWidgetReceiver : NerLanWidgetProvider(WidgetKind.STATS)
