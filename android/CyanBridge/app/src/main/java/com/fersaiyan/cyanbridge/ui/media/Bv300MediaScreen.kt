package com.fersaiyan.cyanbridge.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaItem
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaType

private enum class MediaFilter(val label: String) { ALL("Все"), PHOTO("Фото"), VIDEO("Видео"), AUDIO("Аудио") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Bv300MediaScreen(
    state: Bv300MediaUiState,
    loadThumbnail: suspend (String) -> ImageBitmap?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (Set<String>) -> Unit,
    onDeleteLocal: (Set<String>) -> Unit,
    onDownloadNew: () -> Unit,
    onCancel: () -> Unit,
    onOpen: (Bv300MediaItem) -> Unit,
    onCredentials: () -> Unit,
    onOpenOldLibrary: () -> Unit,
) {
    var filter by remember { mutableStateOf(MediaFilter.ALL) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val busy = state.phase in setOf(Bv300MediaPhase.CONNECTING_TO_MEDIA, Bv300MediaPhase.LOADING_CATALOG,
        Bv300MediaPhase.TRANSFERRING, Bv300MediaPhase.WAITING, Bv300MediaPhase.DELETING_LOCAL)
    val shown = state.items.filter { filter == MediaFilter.ALL || it.type.name == filter.name }
    val selectedLocal = state.items.filter { it.id in selected && it.localUri != null }.mapTo(mutableSetOf(), Bv300MediaItem::id)
    val selectedRemote = state.items.filter { it.id in selected && it.remoteAvailable }.mapTo(mutableSetOf(), Bv300MediaItem::id)
    val onGlasses = state.items.count { it.remoteAvailable }
    val onPhone = state.items.count { it.localUri != null }
    val newItems = state.items.count { it.remoteAvailable && it.localUri == null }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Медиа BV300") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад") }
        }, actions = {
            IconButton(onClick = onRefresh, enabled = !busy) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить медиатеку")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.connected) "BV300 • подключены" else "BV300 • не подключены",
                        style = MaterialTheme.typography.titleMedium)
                    Text("$onGlasses на очках • $onPhone на телефоне", style = MaterialTheme.typography.bodyMedium)
                    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == Bv300MediaPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (busy) {
                        if (state.phase == Bv300MediaPhase.TRANSFERRING && state.bytesExpected > 0) {
                            LinearProgressIndicator(progress = { (state.bytesDownloaded.toFloat() / state.bytesExpected).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            Text("${state.currentNumber}/${state.totalNumber} • ${state.bytesDownloaded / 1024} / ${state.bytesExpected / 1024} КБ",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (state.phase != Bv300MediaPhase.DELETING_LOCAL) {
                            TextButton(onClick = onCancel) { Text("Отменить") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownloadNew, enabled = !busy && newItems > 0) { Text("Скачать новые ($newItems)") }
                        OutlinedButton(onClick = onCredentials, enabled = !busy) { Text("Wi-Fi") }
                    }
                    if (!state.remoteDeleteSupported) Text("Удаление с очков пока недоступно: безопасная команда не подтверждена.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaFilter.entries.forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        label = { Text(option.label) })
                }
            }
            TextButton(onClick = { selectionMode = !selectionMode; selected = emptySet() }, enabled = !busy,
                modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(if (selectionMode) "Готово" else "Выбрать файлы")
            }

            if (selectionMode) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Выбрано: ${selected.size}")
                    TextButton(onClick = { selected = shown.mapTo(mutableSetOf(), Bv300MediaItem::id) }, enabled = !busy) { Text("Все") }
                    TextButton(onClick = { selected = shown.filter { it.remoteAvailable && it.localUri == null }
                        .mapTo(mutableSetOf(), Bv300MediaItem::id) }, enabled = !busy) { Text("Новые") }
                    TextButton(onClick = { selected = emptySet() }, enabled = !busy) { Text("Сбросить") }
                }
                Button(onClick = { onDownload(selectedRemote); selected = emptySet(); selectionMode = false },
                    enabled = !busy && selectedRemote.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("Скачать выбранные (${selectedRemote.size})") }
                OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy && selectedLocal.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Text("Удалить выбранные с телефона (${selectedLocal.size})")
                }
            }

            when {
                state.items.isEmpty() && busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.phase == Bv300MediaPhase.DISCONNECTED) "Подключите BV300 и обновите список. Копии на телефоне появятся здесь."
                    else "Здесь пока нет файлов. Обновите список после создания фото, видео или записи.")
                }
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(132.dp), modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = Bv300MediaItem::id) { item ->
                        var thumbnail by remember(item.localUri) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(item.localUri) {
                            thumbnail = item.localUri?.let { loadThumbnail(it) }
                        }
                        MediaTile(item, thumbnail, item.id in selected, item.id == state.currentId,
                            state.failures[item.id], onClick = {
                                if (selectionMode && !busy) {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                } else if (item.localUri != null) {
                                    onOpen(item)
                                } else if (item.remoteAvailable && !busy) {
                                    onDownload(setOf(item.id))
                                }
                            }, onLongClick = {
                                if (!busy) {
                                    selectionMode = true
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                }
                            })
                    }
                }
            }
            TextButton(onClick = onOpenOldLibrary, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Старые записи и синхронизированные файлы")
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить выбранные файлы с телефона? (${selectedLocal.size})") },
            text = { Text("Локальные копии будут удалены без возможности отмены. Файлы на BV300 останутся на очках.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (selectedLocal.isNotEmpty()) onDeleteLocal(selectedLocal)
                    selected = emptySet()
                    selectionMode = false
                }, enabled = selectedLocal.isNotEmpty() && !busy) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun MediaTile(item: Bv300MediaItem, thumbnail: ImageBitmap?, selected: Boolean, downloading: Boolean,
    error: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (thumbnail != null) Image(thumbnail, item.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(when (item.type) {
                    Bv300MediaType.PHOTO -> Icons.Outlined.Image
                    Bv300MediaType.VIDEO -> Icons.Outlined.Videocam
                    Bv300MediaType.AUDIO -> Icons.Outlined.Mic
                }, contentDescription = null, modifier = Modifier.size(38.dp))
                if (selected || item.localUri != null || downloading || error != null) {
                    Icon(when {
                        selected || item.localUri != null -> Icons.Outlined.Check
                        error != null -> Icons.Outlined.WarningAmber
                        else -> Icons.Outlined.Download
                    }, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp))
                }
            }
            Text(item.fileName, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Text(when {
                error != null -> "Ошибка • повторить"
                downloading -> "Загружается…"
                item.localUri != null && item.remoteAvailable -> "На очках и телефоне"
                item.localUri != null -> "На телефоне"
                item.remoteAvailable -> "На очках • скачать"
                else -> "Недоступно до обновления"
            }, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        }
    }
}
