package com.example.nerlan

import android.app.Application
import com.example.nerlan.data.DownloadManager
import com.example.nerlan.data.FavoritesStore

class NerLanApp : Application() {
  lateinit var favorites: FavoritesStore
    private set
  lateinit var downloads: DownloadManager
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    favorites = FavoritesStore(filesDir)
    downloads = DownloadManager(filesDir)
  }

  companion object {
    lateinit var instance: NerLanApp
      private set
  }
}
