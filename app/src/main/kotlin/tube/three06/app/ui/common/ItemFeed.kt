package tube.three06.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

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
    }
}
