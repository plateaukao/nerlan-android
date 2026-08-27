package com.example.nerlan.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.nerlan.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-instance configuration for the 我的節目 widget: pick exactly which shows
 * appear, in the order you tick them. This is the Android counterpart of the iOS
 * widget's "Edit Widget" show picker — Android has no system-provided editor, so
 * the widget declares this activity via `android:configure`.
 *
 * Picking nothing keeps the automatic order (recently played, then listening
 * time), which is the sensible default for a freshly-dropped widget.
 */
class ShowsWidgetConfigActivity : ComponentActivity() {
  private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    appWidgetId = intent?.extras?.getInt(
      AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
    ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

    // Cancelled unless the user confirms — the launcher removes the widget if the
    // configuration activity returns RESULT_CANCELED.
    setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
      finish()
      return
    }

    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          ConfigScreen()
        }
      }
    }
  }

  @androidx.compose.runtime.Composable
  private fun ConfigScreen() {
    val shows by produceState(initialValue = emptyList<WidgetShow>()) {
      value = withContext(Dispatchers.Default) { WidgetModelBuilder.build(this@ShowsWidgetConfigActivity).shows }
    }
    // Selection order is meaningful, so this is a list, not a set.
    val selected = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
      bottomBar = {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(onClick = { selected.clear() }) { Text("自動排序") }
          Button(enabled = !saving, onClick = { saving = true; save(selected.toList()) }) {
            Text(if (selected.isEmpty()) "使用自動排序" else "完成（${selected.size}）")
          }
        }
      }
    ) { padding ->
      Column(Modifier.padding(padding)) {
        Text(
          "選擇要顯示的節目；不選則依最近播放與收聽時間排序。",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
          items(shows, key = { it.id }) { show ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = show.id in selected,
                onCheckedChange = { on -> if (on) selected.add(show.id) else selected.remove(show.id) },
              )
              Column(Modifier.padding(start = 8.dp)) {
                Text(show.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                  if (show.isPodcast) "Podcast" else show.language,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }

  private fun save(ids: List<String>) {
    lifecycleScope.launch {
      ShowsWidgetPrefs.save(this@ShowsWidgetConfigActivity, appWidgetId, ids)
      // The widget can be dragged off the home screen while this screen sits
      // open; losing the selection is fine, crashing is not.
      runCatching {
        WidgetRenderer.render(applicationContext, mapOf(WidgetKind.SHOWS to intArrayOf(appWidgetId)))
      }
      setResult(
        Activity.RESULT_OK,
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
      )
      finish()
    }
  }
}
