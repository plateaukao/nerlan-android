package com.example.nerlan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.SettingsStore

/**
 * OpenAI credentials & model configuration, shown as a full-screen dialog from
 * the 節目 tab. Mirrors the iOS SettingsView.
 */
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
  val settings = NerLanApp.instance.settings
  val ai = NerLanApp.instance.ai
  val apiKey by settings.apiKey.collectAsState()
  val chatModel by settings.chatModel.collectAsState()
  val transcriptionModel by settings.transcriptionModel.collectAsState()
  var showClearConfirm by remember { mutableStateOf(false) }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
          IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "關閉") }
          Text(
            "設定",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
          )
        }

        Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
          Text("OpenAI API 金鑰", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
          OutlinedTextField(
            value = apiKey,
            onValueChange = settings::setApiKey,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("sk-…") },
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            "金鑰儲存在此裝置。逐字稿與 AI 講義會使用你的 OpenAI 額度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )

          Spacer(Modifier.height(16.dp))
          Text("模型", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
          OutlinedTextField(
            value = transcriptionModel,
            onValueChange = settings::setTranscriptionModel,
            singleLine = true,
            label = { Text("轉錄模型") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          )
          OutlinedTextField(
            value = chatModel,
            onValueChange = settings::setChatModel,
            singleLine = true,
            label = { Text("講義模型") },
            modifier = Modifier.fillMaxWidth(),
          )
          TextButton(onClick = {
            settings.setTranscriptionModel(SettingsStore.DEFAULT_TRANSCRIPTION_MODEL)
            settings.setChatModel(SettingsStore.DEFAULT_CHAT_MODEL)
          }) { Text("恢復預設模型") }

          Spacer(Modifier.height(16.dp))
          TextButton(onClick = { showClearConfirm = true }) {
            Text("清除所有 AI 內容", color = MaterialTheme.colorScheme.error)
          }
          Text(
            "刪除已儲存的逐字稿與 AI 講義。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = { showClearConfirm = false },
      title = { Text("清除所有 AI 內容？") },
      text = { Text("刪除已儲存的逐字稿與 AI 講義。") },
      confirmButton = {
        TextButton(onClick = { ai.clearAll(); showClearConfirm = false }) { Text("清除") }
      },
      dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
    )
  }
}
