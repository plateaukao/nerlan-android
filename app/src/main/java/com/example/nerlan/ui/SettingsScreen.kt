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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.text.format.Formatter
import com.example.nerlan.NerLanApp
import com.example.nerlan.data.DriveSync
import com.example.nerlan.data.GmsFailure
import com.example.nerlan.data.OpenAIService
import com.example.nerlan.data.SettingsStore
import com.example.nerlan.player.AudioCache
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

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
  val apiProvider by settings.apiProvider.collectAsState()
  val customTranscriptionUrl by settings.customTranscriptionUrl.collectAsState()
  val customTranscriptionModel by settings.customTranscriptionModel.collectAsState()
  val customTranscriptionKey by settings.customTranscriptionKey.collectAsState()
  val customChatUrl by settings.customChatUrl.collectAsState()
  val customChatModel by settings.customChatModel.collectAsState()
  val customChatKey by settings.customChatKey.collectAsState()
  val customChatNoThink by settings.customChatNoThink.collectAsState()
  val cacheStreamedAudio by settings.cacheStreamedAudio.collectAsState()
  val translationLanguage by settings.translationLanguage.collectAsState()
  val scrollAnimated by settings.transcriptScrollAnimated.collectAsState()
  val syncToDrive by settings.syncToDrive.collectAsState()
  val driveEmail by drive.accountEmail.collectAsState()
  val driveStatus by drive.status.collectAsState()
  val scope = rememberCoroutineScope()
  val browserSignInLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    scope.launch { drive.completeBrowserSignIn(result.data) }
  }
  val signInLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    try {
      val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
      drive.onSignedIn(account)
    } catch (e: ApiException) {
      val failure = drive.classifyGmsSignIn(e)
      // The user backing out of the picker isn't an error — say nothing.
      if (failure != GmsFailure.CANCELLED) {
        drive.reportSignInError(e.statusCode)
        // A structurally dead broker (the A7) — not config/network/cancel — means GMS
        // can't ever complete here; auto-offer the browser flow if it's configured.
        if (drive.browserAuthConfigured && failure == GmsFailure.BROKEN) {
          drive.browserSignInIntent()?.let { browserSignInLauncher.launch(it) }
        }
      }
    }
  }
  var showClearConfirm by remember { mutableStateOf(false) }
  var showClearCacheConfirm by remember { mutableStateOf(false) }
  var showUsageStats by remember { mutableStateOf(false) }
  var showDataStats by remember { mutableStateOf(false) }
  var modelMenuExpanded by remember { mutableStateOf(false) }
  var translationMenuExpanded by remember { mutableStateOf(false) }
  var cacheBytes by remember { mutableStateOf(AudioCache.sizeBytes(NerLanApp.instance)) }
  // Verification probe results; any edit to that server's fields resets to Idle.
  var transcriptionProbe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }
  var chatProbe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

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
          Text("API 來源", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
          val providerLabels = listOf("OpenAI 官方", "自訂")
          val customSelected = apiProvider == SettingsStore.PROVIDER_CUSTOM
          SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            providerLabels.forEachIndexed { index, label ->
              SegmentedButton(
                selected = customSelected == (index == 1),
                onClick = {
                  settings.setApiProvider(
                    if (index == 1) SettingsStore.PROVIDER_CUSTOM else SettingsStore.PROVIDER_OPENAI)
                },
                shape = SegmentedButtonDefaults.itemShape(index, providerLabels.size),
              ) { Text(label) }
            }
          }
          Text(
            "選擇 AI 逐字稿與講義要使用的伺服器。「自訂」可指向你自己（通常較便宜或本機）的 OpenAI 相容伺服器。兩組設定都會分別保存，可隨時切換。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )

          if (!customSelected) {
            Spacer(Modifier.height(16.dp))
            Text("OpenAI API 金鑰", style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.padding(bottom = 4.dp))
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
          } else {
            Spacer(Modifier.height(16.dp))
            Text("轉錄伺服器", style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.padding(bottom = 4.dp))
            OutlinedTextField(
              value = customTranscriptionUrl,
              onValueChange = { settings.setCustomTranscriptionUrl(it); transcriptionProbe = ProbeState.Idle },
              singleLine = true,
              label = { Text("伺服器網址") },
              placeholder = { Text(SettingsStore.DEFAULT_CUSTOM_SERVER_URL) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
              value = customTranscriptionModel,
              onValueChange = { settings.setCustomTranscriptionModel(it); transcriptionProbe = ProbeState.Idle },
              singleLine = true,
              label = { Text("轉錄模型") },
              placeholder = { Text(SettingsStore.DEFAULT_TRANSCRIPTION_MODEL) },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
              value = customTranscriptionKey,
              onValueChange = { settings.setCustomTranscriptionKey(it); transcriptionProbe = ProbeState.Idle },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              label = { Text("API 金鑰") },
              placeholder = { Text(customKeyPlaceholder(customTranscriptionUrl)) },
              modifier = Modifier.fillMaxWidth(),
            )
            VerifyRow("驗證轉錄伺服器", transcriptionProbe) {
              transcriptionProbe = ProbeState.Checking
              scope.launch {
                transcriptionProbe = try {
                  OpenAIService.verifyTranscription(settings.transcriptionConfig())
                  ProbeState.Ok
                } catch (e: Exception) {
                  ProbeState.Failed(e.message ?: "驗證失敗")
                }
              }
            }
            Text(
              "與 OpenAI 相容的伺服器網址（到 /v1 為止），用於 /audio/transcriptions。本機伺服器通常不需金鑰。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text("講義／翻譯伺服器", style = MaterialTheme.typography.titleSmall,
              modifier = Modifier.padding(bottom = 4.dp))
            OutlinedTextField(
              value = customChatUrl,
              onValueChange = { settings.setCustomChatUrl(it); chatProbe = ProbeState.Idle },
              singleLine = true,
              label = { Text("伺服器網址") },
              placeholder = { Text(SettingsStore.DEFAULT_CUSTOM_SERVER_URL) },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
              value = customChatModel,
              onValueChange = { settings.setCustomChatModel(it); chatProbe = ProbeState.Idle },
              singleLine = true,
              label = { Text("講義／翻譯模型") },
              placeholder = { Text(SettingsStore.DEFAULT_CHAT_MODEL) },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            OutlinedTextField(
              value = customChatKey,
              onValueChange = { settings.setCustomChatKey(it); chatProbe = ProbeState.Idle },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              label = { Text("API 金鑰") },
              placeholder = { Text(customKeyPlaceholder(customChatUrl)) },
              modifier = Modifier.fillMaxWidth(),
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
              Text("停用思考模式（no think）", modifier = Modifier.weight(1f))
              Switch(checked = customChatNoThink, onCheckedChange = {
                settings.setCustomChatNoThink(it)
                chatProbe = ProbeState.Idle
              })
            }
            VerifyRow("驗證講義／翻譯伺服器", chatProbe) {
              chatProbe = ProbeState.Checking
              scope.launch {
                chatProbe = try {
                  OpenAIService.verifyChat(settings.chatConfig())
                  ProbeState.Ok
                } catch (e: Exception) {
                  ProbeState.Failed(e.message ?: "驗證失敗")
                }
              }
            }
            Text(
              "與 OpenAI 相容的伺服器網址（到 /v1 為止），用於 /chat/completions。講義、翻譯與句子斷句都會使用這個伺服器。\n" +
                "本機 Ollama 的思考模型（qwen3、deepseek-r1 等）預設會輸出思考過程，開啟「停用思考模式」會傳送 reasoning_effort=none 將其關閉。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Spacer(Modifier.height(16.dp))
          Text("翻譯", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
          ExposedDropdownMenuBox(
            expanded = translationMenuExpanded,
            onExpandedChange = { translationMenuExpanded = it },
            modifier = Modifier.fillMaxWidth(),
          ) {
            OutlinedTextField(
              value = translationLanguage,
              onValueChange = {},
              readOnly = true,
              singleLine = true,
              label = { Text("翻譯語言") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = translationMenuExpanded) },
              modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(
              expanded = translationMenuExpanded,
              onDismissRequest = { translationMenuExpanded = false },
            ) {
              SettingsStore.TRANSLATION_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                  text = { Text(lang) },
                  onClick = {
                    settings.setTranslationLanguage(lang)
                    translationMenuExpanded = false
                  },
                )
              }
            }
          }
          Text(
            "逐字稿畫面的「翻譯」按鈕會把內容翻譯成這個語言（使用你的 OpenAI 額度，並會同步）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )

          Spacer(Modifier.height(16.dp))
          Text("逐字稿", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("置中捲動動畫", modifier = Modifier.weight(1f))
            Switch(checked = scrollAnimated, onCheckedChange = settings::setTranscriptScrollAnimated)
          }
          Text(
            "播放時將朗讀中的句子平滑捲動到畫面中央。電子紙裝置建議關閉，以避免殘影。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )

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
            // Manual escape for devices with broken/absent Google Play Services
            // (auto-classification can't always tell a dead broker from bad network).
            if (drive.browserAuthConfigured) {
              TextButton(onClick = {
                drive.browserSignInIntent()?.let { browserSignInLauncher.launch(it) }
              }) {
                Text("改用瀏覽器登入（無 Google Play 服務）")
              }
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

          Spacer(Modifier.height(16.dp))
          Text("統計", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp))
          TextButton(onClick = { showUsageStats = true }) { Text("使用統計") }
          TextButton(onClick = { showDataStats = true }) { Text("資料統計") }
          Spacer(Modifier.height(16.dp))
        }
      }
    }
  }

  if (showUsageStats) UsageStatsScreen(onDismiss = { showUsageStats = false })
  if (showDataStats) DataStatsScreen(onDismiss = { showDataStats = false })

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
          NerLanApp.instance.downloads.clearCachedRecords()
          cacheBytes = 0
          showClearCacheConfirm = false
        }) { Text("清除") }
      },
      dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
    )
  }
}

/** Result of a custom-server verification probe. */
private sealed interface ProbeState {
  data object Idle : ProbeState
  data object Checking : ProbeState
  data object Ok : ProbeState
  data class Failed(val message: String) : ProbeState
}

/** Tappable "verify this server" row with a trailing status: spinner while
 *  checking, check on success, cross + the server's error text on failure.
 *  Mirrors the iOS SettingsView verifyRow. */
@Composable
private fun VerifyRow(label: String, state: ProbeState, onVerify: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    TextButton(onClick = onVerify, enabled = state != ProbeState.Checking) { Text(label) }
    Spacer(Modifier.weight(1f))
    when (state) {
      ProbeState.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
      ProbeState.Ok -> Icon(Icons.Filled.CheckCircle, contentDescription = "驗證成功",
        tint = MaterialTheme.colorScheme.primary)
      is ProbeState.Failed -> Icon(Icons.Filled.Cancel, contentDescription = "驗證失敗",
        tint = MaterialTheme.colorScheme.error)
      ProbeState.Idle -> {}
    }
  }
  if (state is ProbeState.Failed) {
    Text(
      state.message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
}

/** Key-field placeholder: while the URL still points at the official OpenAI
 *  server a blank key falls back to the OpenAI-mode key; anywhere else a blank
 *  key sends no key (local servers are typically keyless). */
private fun customKeyPlaceholder(url: String): String =
  if (SettingsStore.customUrlIsOfficial(url)) "與 OpenAI 模式相同（可留空）" else "API 金鑰（可留空）"
