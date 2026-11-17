package tube.three06.app.ui.common

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tube.three06.innertube.model.Page

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

val <T> Load<T>.valueOrNull: T? get() = (this as? Load.Ready)?.value

fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadedOnce: Boolean = false,
    val completedLoads: Int = 0,
    // elapsedRealtime of the last good first-page load, 0 = never
    val loadedAt: Long = 0,
) {
    val isEmpty: Boolean get() = loadedOnce && items.isEmpty() && error == null
    val canLoadMore: Boolean get() = continuation != null && !loadingMore && !loading
}

class Paged<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any = { it as Any },
    private val loader: suspend (continuation: String?) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private var job: Job? = null

    fun load() {
        if (_state.value.loading) return
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            runCatching { loader(null) }
                .onSuccess { page ->
                    _state.update {
                        PagedState(
                            items = page.items.distinctBy(key),
                            continuation = page.continuation,
                            loadedOnce = true,
                            completedLoads = it.completedLoads + 1,
                            loadedAt = SystemClock.elapsedRealtime(),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.describe(),
                            loadedOnce = true,
                            completedLoads = it.completedLoads + 1,
                        )
                    }
                }
        }
    }

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(refreshing = true, error = null) }
        job = scope.launch {
            runCatching { loader(null) }
                .onSuccess { page ->
                    _state.update {
                        PagedState(
                            items = page.items.distinctBy(key),
                            continuation = page.continuation,
                            loadedOnce = true,
                            completedLoads = it.completedLoads + 1,
                            loadedAt = SystemClock.elapsedRealtime(),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = e.describe(),
                            loadedOnce = true,
                            completedLoads = it.completedLoads + 1,
                        )
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val token = current.continuation ?: return
        if (!current.canLoadMore) return
        _state.update { it.copy(loadingMore = true) }
        scope.launch {
            runCatching { loader(token) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            items = (it.items + page.items).distinctBy(key),
                            continuation = page.continuation,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loadingMore = false, error = e.describe()) }
                }
        }
    }

    fun seed(page: Page<T>) {
        job?.cancel()
        _state.value = PagedState(
            items = page.items.distinctBy(key),
            continuation = page.continuation,
            loadedOnce = true,
            loadedAt = SystemClock.elapsedRealtime(),
        )
    }

    fun update(transform: (List<T>) -> List<T>) {
        _state.update { it.copy(items = transform(it.items)) }
    }

    fun clear() {
        job?.cancel()
        _state.value = PagedState()
    }
}
