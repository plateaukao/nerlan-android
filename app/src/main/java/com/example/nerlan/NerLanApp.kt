package com.example.nerlan

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.example.nerlan.data.AIContentStore
import com.example.nerlan.data.CatalogCache
import com.example.nerlan.data.DownloadManager
import com.example.nerlan.data.DriveSync
import com.example.nerlan.data.FavoritesStore
import com.example.nerlan.data.ListeningStatsStore
import com.example.nerlan.data.SettingsStore

class NerLanApp : Application(), ImageLoaderFactory {
  lateinit var favorites: FavoritesStore
    private set
  lateinit var downloads: DownloadManager
    private set
  lateinit var settings: SettingsStore
    private set
  lateinit var ai: AIContentStore
    private set
  lateinit var drive: DriveSync
    private set
  lateinit var catalog: CatalogCache
    private set
  lateinit var stats: ListeningStatsStore
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    favorites = FavoritesStore(filesDir)
    downloads = DownloadManager(filesDir)
    settings = SettingsStore(this)
    ai = AIContentStore(this)
    stats = ListeningStatsStore(this)
    drive = DriveSync(this)
    catalog = CatalogCache(cacheDir)
    // Pull/push on launch when sync is on (no-op if not signed in).
    if (settings.syncToDrive.value) drive.syncNow()
    // Flush changes when the app goes to the background (ProcessLifecycleOwner's
    // ON_STOP ignores rotation/config changes).
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onStop(owner: LifecycleOwner) {
        if (settings.syncToDrive.value) drive.syncNow()
      }
    })
  }

  /**
   * Coil's singleton ImageLoader (used by every AsyncImage). Cover art is static,
   * and the image endpoint sends no Cache-Control — so respectCacheHeaders(false)
   * makes Coil serve a fetched cover straight from its disk cache on later loads
   * (including across launches) instead of revalidating it over the network.
   */
  override fun newImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
      .respectCacheHeaders(false)
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("image_cache"))
          .maxSizeBytes(256L * 1024 * 1024)
          .build()
      }
      .build()

  companion object {
    lateinit var instance: NerLanApp
      private set
  }
}
