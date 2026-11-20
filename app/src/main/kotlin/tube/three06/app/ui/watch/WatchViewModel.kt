package tube.three06.app.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tube.three06.app.data.Settings
import tube.three06.app.data.api.Branding
import tube.three06.app.data.api.RydVotes
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.common.Load
import tube.three06.app.ui.common.Paged
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.common.itemKey
import tube.three06.innertube.model.Comment
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.Video
import tube.three06.innertube.model.VideoDetails
import tube.three06.player.PlayQueue
import tube.three06.player.PlayerState

@OptIn(ExperimentalCoroutinesApi::class)
class WatchViewModel(private val graph: AppGraph) : ViewModel() {

    val playerState: StateFlow<PlayerState> = graph.player.state
    val queue: StateFlow<PlayQueue> = graph.player.queue
    val settings: StateFlow<Settings> = graph.settings.state
    val signedIn: StateFlow<Boolean> = graph.session.credentials
        .map { it.signedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.session.current.signedIn)

    val details = MutableStateFlow<Load<VideoDetails>?>(null)

    /** Description kept with a download, for when [details] can't load. */
    val savedDescription = MutableStateFlow<String?>(null)
    val ryd = MutableStateFlow<RydVotes?>(null)
    val branding = MutableStateFlow<Branding?>(null)
    val likeStatus = MutableStateFlow<LikeStatus?>(null)
    private val remoteSubscribed = MutableStateFlow<Boolean?>(null)

    val related: Paged<Item> = Paged(viewModelScope, ::itemKey) { c ->
        if (c == null) Page(emptyList()) else graph.youTube.related(c)
    }

    val comments: Paged<Comment> = Paged(viewModelScope, { it.id }) { c ->
        val token = c ?: (details.value as? Load.Ready)?.value?.commentsContinuation
        if (token == null) Page(emptyList()) else graph.youTube.comments(token)
    }

    data class Replies(
        val items: List<Comment> = emptyList(),
        val continuation: String? = null,
        val loading: Boolean = true,
    )

    val replies = MutableStateFlow<Map<String, Replies>>(emptyMap())

    private val channelId: Flow<String?> = details
        .map { (it as? Load.Ready)?.value?.channel?.id }
        .distinctUntilChanged()

    val subscribed: StateFlow<Boolean> = combine(
        signedIn,
        remoteSubscribed,
        channelId.flatMapLatest { if (it != null) graph.library.isSubscribed(it) else flowOf(false) },
    ) { signed, remote, local ->
        if (signed) remote == true else local
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var loadJob: Job? = null
    private var loadedId: String? = null

    init {
        viewModelScope.launch {
            playerState
                .map { it.current }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { video ->
                    if (video == null) {
                        reset()
                    } else if (video.id != loadedId) {
                        load(video)
                    }
                }
        }
    }

    private fun reset() {
        loadJob?.cancel()
        loadedId = null
        details.value = null
        savedDescription.value = null
        ryd.value = null
        branding.value = null
        likeStatus.value = null
        remoteSubscribed.value = null
        related.clear()
        comments.clear()
        replies.value = emptyMap()
    }

    fun load(target: Video? = null) {
        val video = target ?: playerState.value.current ?: return
        reset()
        loadedId = video.id
        details.value = Load.Loading
        loadJob = viewModelScope.launch {
            val s = settings.value
            launch(Dispatchers.IO) { savedDescription.value = graph.downloads.description(video.id) }
            launch {
                runCatching { graph.youTube.video(video.id) }
                    .onSuccess { d ->
                        details.value = Load.Ready(d)
                        likeStatus.value = d.likeStatus
                        remoteSubscribed.value = d.subscribed
                        related.seed(d.related)
                        pickNextUp(video.id, d)
                    }
                    .onFailure { details.value = Load.Error(it.describe()) }
            }
            if (s.returnYouTubeDislike) {
                launch { ryd.value = runCatching { graph.returnYouTubeDislike.votes(video.id) }.getOrNull() }
            }
            if (s.deArrow) {
                launch { branding.value = graph.deArrowCache.branding(video.id) }
            }
        }
    }

    private suspend fun pickNextUp(videoId: String, d: VideoDetails) {
        val fromRecommender = if (!signedIn.value && settings.value.recommenderEnabled) {
            runCatching { graph.recommender.relatedTo(videoId) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val next = fromRecommender.firstOrNull { it.id != videoId }
            ?: d.related.items.filterIsInstance<Video>().firstOrNull { it.id != videoId && !it.isShort }
        graph.playbackObserver.nextUp.value = next
    }

    fun loadComments() {
        if (comments.state.value.loadedOnce || comments.state.value.loading) return
        comments.load()
    }

    fun loadReplies(comment: Comment) {
        val current = replies.value[comment.id]
        val token = (if (current == null) comment.repliesContinuation else current.continuation) ?: return
        if (current?.loading == true) return
        replies.update { it + (comment.id to (current ?: Replies()).copy(loading = true)) }
        viewModelScope.launch {
            val page = runCatching { graph.youTube.commentReplies(token) }.getOrNull()
            replies.update { map ->
                val existing = map[comment.id] ?: Replies()
                map + (comment.id to Replies(existing.items + (page?.items ?: emptyList()), page?.continuation, false))
            }
        }
    }

    fun setLike(target: LikeStatus, onError: (String) -> Unit) {
        val id = loadedId ?: return
        val next = if (likeStatus.value == target) LikeStatus.INDIFFERENT else target
        val previous = likeStatus.value
        likeStatus.value = next
        viewModelScope.launch {
            runCatching { graph.youTube.setLike(id, next) }
                .onFailure {
                    likeStatus.value = previous
                    onError(it.describe())
                }
        }
    }

    fun toggleSubscribe(onError: (String) -> Unit) {
        val d = (details.value as? Load.Ready)?.value ?: return
        val channelId = d.channel.id ?: return
        viewModelScope.launch {
            val next = !subscribed.value
            if (signedIn.value) {
                runCatching { graph.youTube.subscribe(channelId, next) }
                    .onSuccess { remoteSubscribed.value = next }
                    .onFailure { onError(it.describe()) }
            } else {
                if (next) graph.library.subscribe(d.channel) else graph.library.unsubscribe(channelId)
                graph.recommender.invalidate()
            }
        }
    }

    fun setAutoplay(enabled: Boolean) {
        graph.player.setAutoplay(enabled)
        viewModelScope.launch { graph.settings.update { it.copy(autoplay = enabled) } }
    }
}
