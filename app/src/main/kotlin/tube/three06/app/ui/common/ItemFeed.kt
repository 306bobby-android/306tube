package tube.three06.app.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

const val FEED_STALE_AFTER_MILLIS = 30 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PagedColumn(
    state: PagedState<T>,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    emptyTitle: String = "Nothing here",
    emptyMessage: String? = null,
    staleAfterMillis: Long? = null,
    header: (LazyListScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val refreshState = rememberPullToRefreshState()
    if (refreshState.isRefreshing) {
        LaunchedEffect(Unit) { onRefresh() }
    }
    LaunchedEffect(state.refreshing, state.completedLoads) {
        if (!state.refreshing && refreshState.isRefreshing) refreshState.endRefresh()
    }

    val shouldLoadMore by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(listState, state.continuation) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    var stale by remember(state.loadedAt) { mutableStateOf(false) }
    if (staleAfterMillis != null && state.loadedAt != 0L) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(state.loadedAt, staleAfterMillis, lifecycleOwner) {
            // delay() stalls in deep sleep, so recompute what's left on every resume
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(state.loadedAt + staleAfterMillis - SystemClock.elapsedRealtime())
                stale = true
            }
        }
    }
    val scope = rememberCoroutineScope()

    Box(modifier.fillMaxSize().nestedScroll(refreshState.nestedScrollConnection)) {
        when {
            state.loading && state.items.isEmpty() -> LoadingBox()
            state.error != null && state.items.isEmpty() -> ErrorBox(state.error, onRetry = onRetry)
            state.isEmpty && header == null -> EmptyBox(emptyTitle, emptyMessage)
            else -> LazyColumn(state = listState, contentPadding = contentPadding) {
                header?.invoke(this)
                if (state.isEmpty && emptyTitle.isNotEmpty()) {
                    item("empty") { EmptyBox(emptyTitle, emptyMessage, Modifier.fillMaxSize()) }
                }
                content()
                if (state.loadingMore) {
                    item("loading-more") { LoadingRow() }
                }
                if (state.error != null && state.items.isNotEmpty()) {
                    item("error-more") { ErrorBox(state.error, Modifier.fillMaxSize(), onLoadMore) }
                }
            }
        }
        PullToRefreshContainer(refreshState, Modifier.align(Alignment.TopCenter))
        AnimatedVisibility(
            visible = stale && state.items.isNotEmpty() && !state.refreshing && !refreshState.isRefreshing,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Snackbar(
                modifier = Modifier.padding(12.dp),
                action = {
                    TextButton(
                        onClick = {
                            scope.launch { listState.scrollToItem(0) }
                            refreshState.startRefresh()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text("Refresh") }
                },
            ) { Text("This feed may be outdated") }
        }
    }
}
