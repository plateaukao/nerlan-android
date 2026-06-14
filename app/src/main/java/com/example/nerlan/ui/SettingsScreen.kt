package com.example.nerlan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.text.format.Formatter
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.DriveSync
import com.example.nerlan.data.SettingsStore
import com.example.nerlan.player.AudioCache
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

/**
 * OpenAI credentials & model configuration, shown as a full-screen dialog from
 * the 節目 tab. Mirrors the iOS SettingsView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val settings = NerLanApp.instance.settings
  val ai = NerLanApp.instance.ai
  val drive = NerLanApp.instance.drive
  val apiKey by settings.apiKey.collectAsState()
  val chatModel by settings.chatModel.collectAsState()
  val transcriptionModel by settings.transcriptionModel.collectAsState()
  val cacheStreamedAudio by settings.cacheStreamedAudio.collectAsState()
  val syncToDrive by settings.syncToDrive.collectAsState()
  val driveEmail by drive.accountEmail.collectAsState()
  val driveStatus by drive.status.collectAsState()
  val signInLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    try {
      val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
      drive.onSignedIn(account)
    } catch (e: ApiException) {
      drive.reportSignInError(e.statusCode)
    }
  }
  var showClearConfirm by remember { mutableStateOf(false) }
  var showClearCacheConfirm by remember { mutableStateOf(false) }
  var modelMenuExpanded by remember { mutableStateOf(false) }
  var cacheBytes by remember { mutableStateOf(AudioCache.sizeBytes(NerLanApp.instance)) }

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
          ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = { modelMenuExpanded = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          ) {
            OutlinedTextField(
              value = transcriptionModel,
              onValueChange = {},
              readOnly = true,
              singleLine = true,
              label = { Text("轉錄模型") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
              modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(
              expanded = modelMenuExpanded,
              onDismissRequest = { modelMenuExpanded = false },
            ) {
              SettingsStore.TRANSCRIPTION_MODELS.forEach { model ->
                DropdownMenuItem(
                  text = { Text(model) },
                  onClick = {
                    settings.setTranscriptionModel(model)
                    modelMenuExpanded = false
                  },
                )
              }
            }
          }
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
          Text("串流快取", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("串流時自動快取", modifier = Modifier.weight(1f))
            Switch(checked = cacheStreamedAudio, onCheckedChange = settings::setCacheStreamedAudio)
          }
          Text(
            buildString {
              append("開啟後，串流播放過的音檔會自動保存，下次播放免再下載（不會顯示在「下載」分頁）。")
              if (cacheBytes > 0) {
                append("目前已快取 ${Formatter.formatShortFileSize(NerLanApp.instance, cacheBytes)}。")
              }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
          TextButton(onClick = { showClearCacheConfirm = true }, enabled = cacheBytes > 0) {
            Text("清除快取音檔", color = MaterialTheme.colorScheme.error)
          }

          Spacer(Modifier.height(16.dp))
          Text("Google 雲端同步", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))
          if (driveEmail == null) {
            Button(onClick = { signInLauncher.launch(DriveSync.signInClient(context).signInIntent) }) {
              Text("使用 Google 帳戶登入")
            }
          } else {
            Text("已登入：$driveEmail", style = MaterialTheme.typography.bodyMedium)
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
              Text("同步到 Google 雲端硬碟", modifier = Modifier.weight(1f))
              Switch(
                checked = syncToDrive,
                onCheckedChange = { settings.setSyncToDrive(it); if (it) drive.syncNow() },
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
              TextButton(onClick = { drive.syncNow() }, enabled = syncToDrive) { Text("立即同步") }
              TextButton(onClick = { drive.signOut(); settings.setSyncToDrive(false) }) {
                Text("登出", color = MaterialTheme.colorScheme.error)
              }
            }
          }
          Text(
            buildString {
              append("將收藏、逐字稿與 AI 講義同步到你的 Google 雲端硬碟私人應用程式資料夾（音檔不會同步）。")
              driveStatus?.let { append("\n$it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )

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

  if (showClearCacheConfirm) {
    AlertDialog(
      onDismissRequest = { showClearCacheConfirm = false },
      title = { Text("清除快取音檔？") },
      text = { Text("刪除串流時自動保存的音檔。") },
      confirmButton = {
        TextButton(onClick = {
          AudioCache.clear(NerLanApp.instance)
          cacheBytes = 0
          showClearCacheConfirm = false
        }) { Text("清除") }
      },
      dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
    )
  }
}
