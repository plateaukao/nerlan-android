package com.example.nerlan

import android.app.Application
import com.example.nerlan.data.AIContentStore
import com.example.nerlan.data.DownloadManager
import com.example.nerlan.data.FavoritesStore
import com.example.nerlan.data.SettingsStore

class NerLanApp : Application() {
  lateinit var favorites: FavoritesStore
    private set
  lateinit var downloads: DownloadManager
    private set
  lateinit var settings: SettingsStore
    private set
  lateinit var ai: AIContentStore
    private set

  override fun onCreate() {
    super.onCreate()
    instance = this
    favorites = FavoritesStore(filesDir)
    downloads = DownloadManager(filesDir)
    settings = SettingsStore(this)
    ai = AIContentStore(this)
  }

  companion object {
    lateinit var instance: NerLanApp
      private set
  }
}
