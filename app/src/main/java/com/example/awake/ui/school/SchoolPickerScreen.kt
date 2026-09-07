package com.example.awake.ui.school

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.awake.data.remote.SchoolCatalog

private sealed class PickerRow {
    data class Header(val label: String) : PickerRow()
    data class School(val code: String) : PickerRow()
}

/**
 * 独立学校选择页：搜索、收藏、字母分组和右侧索引。
 *
 * 未来新增学校只需要更新 SchoolCatalog；该页面保持通用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolPickerScreen(
    onBack: () -> Unit,
    onSchoolSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val favoritesStore = remember { SchoolFavoritesStore(context) }
    var favorites by remember { mutableStateOf(favoritesStore.read()) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val filtered = SchoolCatalog.all.filter {
        query.isBlank() ||
            it.displayName.contains(query, ignoreCase = true) ||
            it.code.contains(query, ignoreCase = true)
    }
    val favoriteEntries = filtered.filter { it.code in favorites }
    val normalEntries = filtered.filter { it.code !in favorites }
    val grouped = normalEntries.groupBy { it.initial }.toSortedMap()

    val rows = buildList {
        if (favoriteEntries.isNotEmpty()) {
            add(PickerRow.Header("★"))
            favoriteEntries.forEach { add(PickerRow.School(it.code)) }
        }
        grouped.forEach { (initial, entries) ->
            add(PickerRow.Header(initial.toString()))
            entries.sortedBy { it.displayName }.forEach { add(PickerRow.School(it.code)) }
        }
    }

    val sectionPositions = remember(rows) {
        rows.mapIndexedNotNull { index, row ->
            when (row) {
                is PickerRow.Header -> row.label to index
                is PickerRow.School -> null
            }
        }.toMap()
    }
    val indexLetters = listOf("★") + ('A'..'Z').map { it.toString() }
    val availableLetters = sectionPositions.keys

    LaunchedEffect(sectionPositions) {
        // 列表结构变化后回到顶部，避免搜索结果仍停在旧索引位置。
        listState.scrollToItem(0)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索学校", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 24.dp)
                ) {
                    items(rows) { row ->
                        when (row) {
                            is PickerRow.Header -> {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (row.label == "★") MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 9.dp)
                                    )
                                }
                            }
                            is PickerRow.School -> {
                                val entry = SchoolCatalog.byCode(row.code) ?: return@items
                                val favorite = entry.code in favorites
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSchoolSelected(entry.code) }
                                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = entry.systemType,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            favoritesStore.toggle(entry.code)
                                            favorites = favoritesStore.read()
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = if (favorite) "取消收藏" else "收藏",
                                            tint = if (favorite) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 7.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    indexLetters.forEach { letter ->
                        val enabled = letter in availableLetters
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = if (enabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier
                                .size(width = 18.dp, height = 18.dp)
                                .clickable(enabled = enabled) {
                                    sectionPositions[letter]?.let { index ->
                                        listState.requestScrollToItem(index)
                                    }
                                },
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
