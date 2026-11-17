package tube.three06.app.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.FEED_STALE_AFTER_MILLIS
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.hidingShorts
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.rememberItemClick
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.Video
import tube.three06.recommend.LocalRecommender
import tube.three06.recommend.WatchEvent

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val identity: Flow<String?> = graph.session.credentials.map { it.identity }.distinctUntilChanged()

    val feed: Paged<Item> = Paged(viewModelScope, ::itemKey) { continuation ->
        when {
            graph.session.current.signedIn -> graph.youTube.home(continuation)
            graph.settings.current.recommenderEnabled -> graph.recommender.homeFeed(continuation)
            else -> graph.youTube.trending(continuation)
        }
    }

    private val hidden: Flow<Pair<Set<String>, Set<String>>> =
        combine(graph.library.notInterestedIds, graph.library.blockedChannels) { ids, channels ->
            ids to channels.map { it.channelId }.toSet()
        }

    val items: StateFlow<List<Item>> =
        combine(feed.state, hidden) { state, (ids, channels) ->
            state.items.mapNotNull { it.filtered(ids, channels) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val progress: StateFlow<Map<String, Int>> =
        feed.state.map { it.items.videoIds() }
            .distinctUntilChanged()
            .flatMapLatest { graph.history.observeProgress(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun reasonOf(video: Video): String? =
        if (graph.session.current.signedIn) null
        else (graph.recommender as? LocalRecommender)?.reasonFor(video.id)

    fun refresh() {
        viewModelScope.launch {
            if (!graph.session.current.signedIn && graph.settings.current.recommenderEnabled) {
                graph.recommender.invalidate()
            }
            feed.refresh()
        }
    }

    init {
        feed.load()
        viewModelScope.launch { identity.drop(1).collect { feed.refresh() } }
        viewModelScope.launch {
            graph.settings.flow.map { it.recommenderEnabled }.distinctUntilChanged().drop(1).collect { feed.refresh() }
        }
    }

    private fun Item.filtered(ids: Set<String>, channels: Set<String>): Item? = when (this) {
        is Video -> takeIf { id !in ids && channel?.id !in channels }
        is Shelf -> copy(items = items.mapNotNull { it.filtered(ids, channels) }).takeIf { it.items.isNotEmpty() }
        else -> this
    }

    private fun List<Item>.videoIds(): List<String> = flatMap { item ->
        when (item) {
            is Video -> listOf(item.id)
            is Shelf -> item.items.filterIsInstance<Video>().map { it.id }
            else -> emptyList()
        }
    }.take(300)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val vm = graphViewModel { HomeViewModel(it) }
    val state by vm.feed.state.collectAsStateWithLifecycle()
    val allItems by vm.items.collectAsStateWithLifecycle()
    val items = allItems.hidingShorts()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val onItemClick = rememberItemClick(WatchEvent.Source.HOME)
    val reasonOf = remember(vm) { vm::reasonOf }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TubeTopBar("306Tube", scrollBehavior, showSearch = true) },
    ) { padding ->
        PagedColumn(
            state = state.copy(items = items),
            onRefresh = vm::refresh,
            onLoadMore = vm.feed::loadMore,
            onRetry = vm.feed::load,
            modifier = Modifier.fillMaxSize().padding(padding),
            emptyTitle = "Your feed is empty",
            staleAfterMillis = FEED_STALE_AFTER_MILLIS,
            emptyMessage = "Watch or search for something and recommendations will show up here.",
        ) {
            items(items, key = { itemKey(it) }) { item ->
                ItemView(item, onItemClick, progress, reasonOf)
            }
        }
    }
}
