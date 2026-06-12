package com.example.nerlan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nerlan.data.ChannelPlusApi
import com.example.nerlan.data.LanguageGroup
import com.example.nerlan.data.Program

/**
 * Browse language-learning programs, filterable by language.
 * The full catalog loads in one request; chips derive from loaded data
 * and filter instantly client-side. Chips wrap into rows (no carousel).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgramListScreen(onProgramClick: (Program) -> Unit) {
  var groups by remember { mutableStateOf<List<LanguageGroup>>(emptyList()) }
  var selectedLanguage by remember { mutableStateOf<String?>(null) }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    if (groups.isEmpty()) {
      isLoading = true
      try {
        val programs = ChannelPlusApi.programs()
        groups = programs
          .groupBy { it.language }
          .map { (lang, progs) -> LanguageGroup(lang, progs) }
        errorMessage = null
      } catch (e: Exception) {
        errorMessage = e.message ?: "載入失敗"
      }
      isLoading = false
    }
  }

  val visibleGroups = selectedLanguage?.let { sel -> groups.filter { it.language == sel } } ?: groups

  Box(Modifier.fillMaxSize()) {
    when {
      isLoading -> LoadingIndicator(Modifier.align(Alignment.Center).size(56.dp))
      errorMessage != null ->
        Text("載入失敗：$errorMessage", Modifier.align(Alignment.Center).padding(24.dp))
      else -> LazyColumn(Modifier.fillMaxSize()) {
        item {
          Text(
            "語言學習",
            style = MaterialTheme.typography.headlineMediumEmphasized,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
          )
        }
        item {
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          ) {
            LanguageChip("全部", selectedLanguage == null) { selectedLanguage = null }
            groups.forEach { group ->
              LanguageChip(group.language, selectedLanguage == group.language) {
                selectedLanguage = group.language
              }
            }
          }
        }
        visibleGroups.forEach { group ->
          item(key = "header-${group.language}") {
            Text(
              group.language,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
          }
          items(group.programs.size, key = { "prog-${group.programs[it].programId}" }) { i ->
            ProgramRow(group.programs[i], onClick = { onProgramClick(group.programs[i]) })
          }
        }
      }
    }
  }
}

@Composable
private fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    leadingIcon = if (selected) {
      { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
    } else null,
  )
}

@Composable
fun ProgramRow(program: Program, onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    CoverImage(program.coverUrl, 56.dp)
    Column(Modifier.padding(start = 12.dp)) {
      Text(
        program.name,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        program.level?.let { level ->
          Text(
            level,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer)
              .padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
        program.episodeCount?.let { count ->
          Text(
            "共 $count 集",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
          )
        }
      }
    }
  }
}
