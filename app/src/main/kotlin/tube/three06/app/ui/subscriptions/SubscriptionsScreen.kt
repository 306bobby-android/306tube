package tube.three06.app.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.ChannelRefCard
import tube.three06.app.ui.common.EmptyBox
import tube.three06.app.ui.common.FEED_STALE_AFTER_MILLIS
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.parsePublishedMillis
import tube.three06.app.ui.common.rememberItemClick
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.Subscription
import tube.three06.innertube.model.Thumbnail
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

class SubscriptionsViewModel(private val graph: AppGraph) : ViewModel() {
    val signedIn: StateFlow<Boolean> = graph.session.credentials
        .map { it.signedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.session.current.signedIn)

    private val identity = graph.session.credentials.map { it.identity }.distinctUntilChanged()

    val channels = MutableStateFlow<List<Subscription>>(emptyList())

    private val localChannels: StateFlow<List<ChannelRef>> = graph.library.subscriptions
        .map { list ->
            list.map {
                ChannelRef(
                    id = it.channelId,
                    name = it.name,
                    avatar = it.avatarUrl?.let { url -> listOf(Thumbnail(url)) } ?: emptyList(),
                    handle = it.handle,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val feed = Paged<Item>(viewModelScope, ::itemKey) { continuation ->
        if (graph.session.current.signedIn) graph.youTube.subscriptionsFeed(continuation) else localFeed(continuation)
    }

    init {
        refresh()
        viewModelScope.launch { identity.drop(1).collect { refresh() } }
        viewModelScope.launch { localChannels.drop(1).collect { if (!signedIn.value) refresh() } }
    }

    fun refresh() {
        feed.refresh()
        viewModelScope.launch {
            if (signedIn.value) {
                channels.value = runCatching { graph.youTube.subscriptions() }.getOrDefault(emptyList())
            }
        }
    }

    private suspend fun loadLocalChannels(): List<Subscription> = localChannels.value.map { Subscription(it) }

    private suspend fun localFeed(continuation: String?): Page<Item> {
        if (continuation != null) return Page(emptyList())
        val subs = loadLocalChannels()
        channels.value = subs
        if (subs.isEmpty()) return Page(emptyList())
        val limiter = Semaphore(4)
        val now = System.currentTimeMillis()
        val videos = coroutineScope {
            subs.take(50).map { sub ->
                async {
                    limiter.withPermit {
                        runCatching { graph.youTube.channel(sub.channel.id!!, ChannelTabKind.VIDEOS) }
                            .getOrNull()
                            ?.items?.items
                            ?.filterIsInstance<Video>()
                            ?.take(10)
                            ?.map { it.copy(channel = it.channel ?: sub.channel) }
                            ?: emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return Page(videos.sortedByDescending { parsePublishedMillis(it.publishedText, now) ?: 0L }.take(150))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen() {
    val vm = graphViewModel { SubscriptionsViewModel(it) }
    val state by vm.feed.state.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val onItemClick = rememberItemClick(WatchEvent.Source.SUBSCRIPTIONS)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TubeTopBar("Subscriptions", scrollBehavior, showSearch = true) },
    ) { padding ->
        if (state.loadedOnce && state.items.isEmpty() && channels.isEmpty() && state.error == null) {
            EmptyBox(
                if (signedIn) "No subscriptions yet" else "No subscriptions on this device",
                if (signedIn) "Channels you subscribe to on YouTube appear here."
                else "Open a channel and tap Subscribe, or sign in to see your YouTube subscriptions.",
                Modifier.fillMaxSize().padding(padding),
                action = if (signedIn) null else "Sign in" to { nav.openLogin() },
            )
            return@Scaffold
        }
        PagedColumn(
            state = state,
            onRefresh = vm::refresh,
            onLoadMore = vm.feed::loadMore,
            onRetry = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
            emptyTitle = "No recent uploads",
            staleAfterMillis = FEED_STALE_AFTER_MILLIS,
            header = {
                if (channels.isNotEmpty()) {
                    item(key = "channels") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(channels, key = { it.channel.id ?: it.channel.name }) { sub ->
                                ChannelRefCard(
                                    sub.channel,
                                    onClick = { sub.channel.id?.let(nav::openChannel) },
                                    badge = sub.hasNewContent,
                                )
                            }
                        }
                    }
                }
            },
        ) {
            items(state.items, key = { itemKey(it) }) { item ->
                ItemView(item, onItemClick)
            }
        }
    }
}
