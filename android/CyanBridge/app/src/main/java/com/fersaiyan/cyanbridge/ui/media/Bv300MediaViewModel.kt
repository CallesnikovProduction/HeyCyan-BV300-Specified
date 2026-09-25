package com.fersaiyan.cyanbridge.ui.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaItem
import com.fersaiyan.cyanbridge.media.bv300.Bv300MediaRepository
import com.fersaiyan.cyanbridge.media.bv300.afterLocalDelete
import com.fersaiyan.cyanbridge.media.bv300.downloadNewIds
import com.fersaiyan.cyanbridge.media.bv300.localDeleteTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class Bv300MediaPhase { DISCONNECTED, CONNECTING_TO_MEDIA, LOADING_CATALOG, READY, TRANSFERRING, DELETING_LOCAL, PARTIAL_ERROR, ERROR, WAITING }

internal data class Bv300MediaUiState(
    val items: List<Bv300MediaItem> = emptyList(),
    val phase: Bv300MediaPhase = Bv300MediaPhase.DISCONNECTED,
    val detail: String? = null,
    val currentId: String? = null,
    val currentNumber: Int = 0,
    val totalNumber: Int = 0,
    val bytesDownloaded: Long = 0,
    val bytesExpected: Long = -1,
    val failures: Map<String, String> = emptyMap(),
    val remoteDeleteSupported: Boolean = false,
    val connected: Boolean = false,
)

internal class Bv300MediaViewModel(context: Context) : ViewModel() {
    private val repository = Bv300MediaRepository(context)
    private val _state = MutableStateFlow(Bv300MediaUiState(
        remoteDeleteSupported = repository.remoteDeleteSupported,
        connected = repository.connected(),
    ))
    val state: StateFlow<Bv300MediaUiState> = _state.asStateFlow()
    private var task: Job? = null

    init {
        viewModelScope.launch {
            MoyoungW620Manager.getInstance(context).state.collect {
                val connected = repository.connected()
                _state.update { current ->
                    if (connected) current.copy(connected = true)
                    else current.copy(connected = false, items = current.items.map { it.copy(remoteAvailable = false) },
                        phase = if (task?.isActive == true) current.phase else Bv300MediaPhase.DISCONNECTED)
                }
            }
        }
        viewModelScope.launch {
            val cached = repository.cached()
            _state.update { it.copy(items = cached) }
        }
    }

    fun refresh() {
        if (task?.isActive == true) return
        task = viewModelScope.launch {
            _state.update { it.copy(phase = Bv300MediaPhase.CONNECTING_TO_MEDIA, detail = "Подключаем медиатеку BV300…") }
            try {
                val items = repository.refresh {
                    _state.update { it.copy(phase = Bv300MediaPhase.WAITING, detail = "Ждём завершения другой операции с очками…") }
                }
                _state.update { it.copy(items = items, phase = Bv300MediaPhase.READY, detail = null, failures = emptyMap()) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(phase = idlePhase(), detail = "Обновление отменено") }
            } catch (error: Exception) {
                _state.update { it.copy(phase = if (repository.connected()) Bv300MediaPhase.ERROR else Bv300MediaPhase.DISCONNECTED,
                    detail = error.message ?: "Не удалось открыть медиатеку BV300") }
            }
        }
    }

    fun download(ids: Set<String>) {
        if (ids.isEmpty() || task?.isActive == true) return
        task = viewModelScope.launch {
            _state.update { it.copy(phase = Bv300MediaPhase.CONNECTING_TO_MEDIA, detail = "Подключаем медиатеку BV300…", failures = emptyMap()) }
            try {
                val summary = repository.download(
                    requestedIds = ids,
                    onWaiting = {
                        _state.update { it.copy(phase = Bv300MediaPhase.WAITING, detail = "Ждём завершения другой операции с очками…") }
                    },
                    onCatalog = { catalog ->
                        _state.update { it.copy(items = catalog, phase = Bv300MediaPhase.LOADING_CATALOG, detail = "Каталог загружен") }
                    },
                    onItemStarted = { item, number, total ->
                        _state.update { it.copy(phase = Bv300MediaPhase.TRANSFERRING, currentId = item.id,
                            currentNumber = number, totalNumber = total, bytesDownloaded = 0, bytesExpected = -1,
                            detail = "Загрузка $number из $total") }
                    },
                    onProgress = { _, done, total ->
                        _state.update { it.copy(bytesDownloaded = done, bytesExpected = total) }
                    },
                    onItemFinished = { item, failure ->
                        _state.update { state ->
                            val items = if (failure == null) state.items.map { if (it.id == item.id) item else it } else state.items
                            state.copy(items = items, failures = if (failure == null) state.failures - item.id
                            else state.failures + (item.id to failure))
                        }
                    },
                )
                _state.update { it.copy(phase = if (summary.failed > 0) Bv300MediaPhase.PARTIAL_ERROR else Bv300MediaPhase.READY,
                    currentId = null, detail = "Загружено ${summary.downloaded} • ошибок ${summary.failed} • уже на телефоне ${summary.skipped}") }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(phase = idlePhase(), currentId = null, detail = "Загрузка отменена. Готовые файлы сохранены.") }
            } catch (error: Exception) {
                _state.update { it.copy(phase = if (repository.connected()) Bv300MediaPhase.ERROR else Bv300MediaPhase.DISCONNECTED,
                    currentId = null, detail = error.message ?: "Передача прервана. Уже загруженные файлы сохранены.") }
            }
        }
    }

    fun downloadNew() = download(downloadNewIds(state.value.items))

    fun deleteLocal(ids: Set<String>) {
        if (ids.isEmpty() || task?.isActive == true) return
        val currentItems = state.value.items
        if (localDeleteTargets(currentItems, ids).isEmpty()) return
        task = viewModelScope.launch {
            _state.update { it.copy(phase = Bv300MediaPhase.DELETING_LOCAL, detail = "Удаляем выбранные копии с телефона…") }
            try {
                val result = repository.deleteLocal(ids, currentItems)
                _state.update { current ->
                    current.copy(
                        items = afterLocalDelete(current.items, result.deletedUris),
                        phase = if (result.failed > 0) Bv300MediaPhase.PARTIAL_ERROR else idlePhase(),
                        detail = "Удалено с телефона: ${result.deletedUris.size} • не удалено: ${result.failed}. Файлы на BV300 не затронуты.",
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(phase = Bv300MediaPhase.ERROR,
                    detail = error.message ?: "Не удалось удалить файлы с телефона") }
            }
        }
    }

    fun cancel() { task?.cancel() }

    private fun idlePhase(): Bv300MediaPhase = if (repository.connected()) Bv300MediaPhase.READY else Bv300MediaPhase.DISCONNECTED

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Bv300MediaViewModel(context) as T
    }
}
