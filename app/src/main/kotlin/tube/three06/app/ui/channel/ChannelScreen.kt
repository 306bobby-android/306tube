package tube.three06.app.ui.channel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.Avatar
import tube.three06.app.ui.common.ErrorBox
import tube.three06.app.ui.common.ItemView
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.LoadingBox
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.PagedColumn
import tube.three06.app.ui.common.best
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.common.graphViewModel
import tube.three06.app.ui.common.itemKey
import tube.three06.app.ui.common.rememberItemClick
import tube.three06.app.ui.nav.TubeTopBar
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.ChannelPage
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.ChannelTab
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Item
import tube.three06.recommend.WatchEvent

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModel(private val graph: AppGraph, private val idOrHandle: String) : ViewModel() {
    val page = MutableStateFlow<Load<ChannelPage>>(Load.Loading)
    val selectedTab = MutableStateFlow(ChannelTabKind.VIDEOS)

    private val remoteSubscribed = MutableStateFlow<Boolean?>(null)
    private val channelId: Flow<String?> = page.map { (it as? Load.Ready)?.value?.channel?.id }

    val signedIn: Flow<Boolean> = graph.session.credentials.map { it.signedIn }

    val subscribed: StateFlow<Boolean> = combine(
        signedIn,
        remoteSubscribed,
        channelId.flatMapLatest { if (it != null) graph.library.isSubscribed(it) else flowOf(false) },
    ) { signedIn, remote, local -> if (signedIn) remote == true else local }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val items = Paged<Item>(viewModelScope, ::itemKey) { continuation ->
        val tab = selectedTab.value
        val params = (page.value as? Load.Ready)?.value?.tabs?.firstOrNull { it.kind == tab }?.params
        val result = graph.youTube.channel(idOrHandle, tab, params, continuation)
        if (continuation == null && page.value !is Load.Ready) page.value = Load.Ready(result)
        result.items
    }

    init {
        load()
    }

    fun load() {
        page.value = Load.Loading
        viewModelScope.launch {
            runCatching { graph.youTube.channel(idOrHandle, ChannelTabKind.VIDEOS) }
                .onSuccess {
                    page.value = Load.Ready(it)
                    remoteSubscribed.value = it.channel.subscribed
                    selectedTab.value = it.selectedTab ?: ChannelTabKind.VIDEOS
                    items.seed(it.items)
                }
                .onFailure { page.value = Load.Error(it.describe()) }
        }
    }

    fun selectTab(kind: ChannelTabKind) {
        if (selectedTab.value == kind) return
        selectedTab.value = kind
        items.clear()
        if (kind != ChannelTabKind.ABOUT) items.load()
    }

    fun toggleSubscribe(onError: (String) -> Unit) {
        val channel = (page.value as? Load.Ready)?.value?.channel ?: return
        viewModelScope.launch {
            val next = !subscribed.value
            if (graph.session.current.signedIn) {
                runCatching { graph.youTube.subscribe(channel.id, next) }
                    .onSuccess { remoteSubscribed.value = next }
                    .onFailure { onError(it.describe()) }
            } else {
                if (next) {
                    graph.library.subscribe(
                        ChannelRef(
                            channel.id,
                            channel.name,
                            channel.avatar,
                            channel.verified,
                            channel.handle,
                            channel.subscriberCountText,
                        ),
                    )
                } else {
                    graph.library.unsubscribe(channel.id)
                }
                graph.recommender.invalidate()
            }
        }
    }
}

private val shownTabs = listOf(
    ChannelTabKind.VIDEOS,
    ChannelTabKind.SHORTS,
    ChannelTabKind.LIVE,
    ChannelTabKind.PLAYLISTS,
    ChannelTabKind.ABOUT,
)

@Composable
fun ChannelScreen(idOrHandle: String) {
    val vm = graphViewModel(key = "channel:$idOrHandle") { ChannelViewModel(it, idOrHandle) }
    val page by vm.page.collectAsStateWithLifecycle()
    val tab by vm.selectedTab.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val items by vm.items.state.collectAsStateWithLifecycle()
    val messages = LocalMessages.current
    val onItemClick = rememberItemClick(WatchEvent.Source.CHANNEL)

    Scaffold(
        topBar = { TubeTopBar((page as? Load.Ready)?.value?.channel?.name ?: "Channel", showBack = true) },
    ) { padding ->
        when (val p = page) {
            Load.Loading -> LoadingBox(Modifier.fillMaxSize().padding(padding))
            is Load.Error -> ErrorBox(p.message, Modifier.fillMaxSize().padding(padding), vm::load)
            is Load.Ready -> {
                val channel = p.value.channel
                val tabs = p.value.tabs.filter { it.kind in shownTabs }.ifEmpty {
                    shownTabs.map { ChannelTab(it, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, null) }
                }
                PagedColumn(
                    state = if (tab == ChannelTabKind.ABOUT) {
                        items.copy(items = emptyList(), loading = false, error = null, loadedOnce = true)
                    } else {
                        items
                    },
                    onRefresh = vm::load,
                    onLoadMore = vm.items::loadMore,
                    onRetry = vm.items::load,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    emptyTitle = if (tab == ChannelTabKind.ABOUT) "" else "Nothing here",
                    header = {
                        item(key = "header") {
                            ChannelHeader(channel, subscribed) { vm.toggleSubscribe(messages) }
                        }
                        item(key = "tabs") {
                            ScrollableTabRow(
                                selectedTabIndex = tabs.indexOfFirst { it.kind == tab }.coerceAtLeast(0),
                                edgePadding = 8.dp,
                            ) {
                                tabs.forEach { t ->
                                    Tab(selected = t.kind == tab, onClick = { vm.selectTab(t.kind) }, text = { Text(t.title) })
                                }
                            }
                        }
                        if (tab == ChannelTabKind.ABOUT) {
                            item(key = "about") {
                                Column(Modifier.padding(16.dp)) {
                                    Text(channel.description ?: "No description.", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.padding(8.dp))
                                    listOfNotNull(channel.handle, channel.subscriberCountText, channel.videoCountText).forEach {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    },
                ) {
                    if (tab != ChannelTabKind.ABOUT) {
                        items(items.items, key = { itemKey(it) }) { item ->
                            ItemView(item, onItemClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelHeader(channel: Channel, subscribed: Boolean, onSubscribe: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        channel.banner.best(640)?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(3.2f),
            )
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(channel.avatar.best(160), 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge)
                val meta = listOfNotNull(channel.handle, channel.subscriberCountText, channel.videoCountText).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            if (subscribed) {
                OutlinedButton(onClick = onSubscribe) { Text("Subscribed") }
            } else {
                Button(onClick = onSubscribe) { Text("Subscribe") }
            }
        }
    }
}
