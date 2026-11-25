package tube.three06.innertube

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tube.three06.innertube.internal.applyAbout
import tube.three06.innertube.internal.bool
import tube.three06.innertube.internal.browseIdOf
import tube.three06.innertube.internal.channelAboutContinuation
import tube.three06.innertube.internal.channelTabKind
import tube.three06.innertube.internal.continuationToken
import tube.three06.innertube.internal.encodeSearchParams
import tube.three06.innertube.internal.execute
import tube.three06.innertube.internal.findAll
import tube.three06.innertube.internal.findFirst
import tube.three06.innertube.internal.get
import tube.three06.innertube.internal.int
import tube.three06.innertube.internal.isLoginPrompt
import tube.three06.innertube.internal.isUnavailablePrompt
import tube.three06.innertube.internal.json
import tube.three06.innertube.internal.list
import tube.three06.innertube.internal.long
import tube.three06.innertube.internal.pageOf
import tube.three06.innertube.internal.parseAccountIdentities
import tube.three06.innertube.internal.parseAccountInfo
import tube.three06.innertube.internal.parseChannelHeader
import tube.three06.innertube.internal.parseChannelTabs
import tube.three06.innertube.internal.parseComments
import tube.three06.innertube.internal.parseGuideSubscriptions
import tube.three06.innertube.internal.parseItems
import tube.three06.innertube.internal.parsePlayer
import tube.three06.innertube.internal.parsePlaylistHeader
import tube.three06.innertube.internal.parseVideoDetails
import tube.three06.innertube.internal.receivedItems
import tube.three06.innertube.internal.requestedLoginError
import tube.three06.innertube.internal.sapisidHash
import tube.three06.innertube.internal.selectedTab
import tube.three06.innertube.internal.str
import tube.three06.innertube.internal.tabs
import tube.three06.innertube.internal.text
import tube.three06.innertube.internal.thumbnails
import tube.three06.innertube.model.AccountIdentity
import tube.three06.innertube.model.AccountInfo
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.ChannelPage
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.ChannelTab
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Comment
import tube.three06.innertube.model.Endpoint
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.LibraryPage
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.PlaybackTracking
import tube.three06.innertube.model.PlayerResponse
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.PlaylistPage
import tube.three06.innertube.model.SearchFilter
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.Subscription
import tube.three06.innertube.model.Video
import tube.three06.innertube.model.VideoDetails
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit

class InnerTubeClient(
    override val session: Session,
    private val http: OkHttpClient = defaultHttp(),
) : YouTube {
    private val bootstrapMutex = Mutex()

    @Volatile
    private var bootstrapped = false

    @Volatile
    var signatureTimestamp: Int? = null
        private set

    private val channelCache: MutableMap<String, Pair<Channel, List<ChannelTab>>> =
        Collections.synchronizedMap(lruMap(32))
    private val playlistCache: MutableMap<String, Pair<Playlist, Boolean>> =
        Collections.synchronizedMap(lruMap(32))

    suspend fun ensureBootstrapped() {
        if (bootstrapped && session.current.visitorData != null) return
        bootstrapMutex.withLock {
            if (bootstrapped && session.current.visitorData != null) return
            try {
                bootstrapFromPage()
            } catch (_: IOException) {
            }
            if (session.current.visitorData == null) {
                try {
                    fetchVisitorId()
                } catch (_: IOException) {
                } catch (_: InnerTubeException) {
                }
            }
            bootstrapped = true
        }
    }

    private suspend fun bootstrapFromPage() {
        val creds = session.current
        val locale = session.locale
        val cookie =
            buildString {
                append("SOCS=CAI; PREF=hl=${locale.hl}&gl=${locale.gl}")
                creds.cookie?.takeIf { it.isNotBlank() }?.let { append("; ").append(it) }
            }
        val request =
            Request
                .Builder()
                .url("$BASE/?hl=${locale.hl}&gl=${locale.gl}")
                .header("User-Agent", Clients.WEB_USER_AGENT)
                .header("Accept-Language", "${locale.hl},en;q=0.8")
                .header("Cookie", cookie)
                .build()
        val res = http.execute(request)
        if (res.code != 200) return
        val body = res.body

        fun find(pattern: String): String? = Regex(pattern).find(body)?.groupValues?.get(1)

        find("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"")?.let { Clients.updateWebVersion(it) }
        find("\"STS\":(\\d+)")?.toIntOrNull()?.let { signatureTimestamp = it }
        val visitor = find("\"VISITOR_DATA\":\"([^\"]+)\"")
        val delegated = find("\"DELEGATED_SESSION_ID\":\"([^\"]+)\"")
        val dataSync = find("\"DATASYNC_ID\":\"([^\"]+)\"")
        session.update { c ->
            c.copy(
                visitorData = c.visitorData ?: visitor,
                dataSyncId = c.dataSyncId ?: delegated?.takeIf { c.signedIn },
                accountDataSyncId = dataSync?.takeIf { c.signedIn } ?: c.accountDataSyncId,
            )
        }
    }

    private suspend fun fetchVisitorId() {
        val res = call("visitor_id", buildJsonObject { }, bootstrap = false)
        val visitor = res["responseContext"]["visitorData"].str ?: return
        session.update { it.copy(visitorData = it.visitorData ?: visitor) }
    }

    private fun context(
        client: ClientInfo,
        creds: Credentials,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("client") {
                put("clientName", client.clientName)
                put("clientVersion", client.clientVersion)
                put("hl", session.locale.hl)
                put("gl", session.locale.gl)
                put("userAgent", client.userAgent)
                client.osName?.let { put("osName", it) }
                client.osVersion?.let { put("osVersion", it) }
                client.deviceMake?.let { put("deviceMake", it) }
                client.deviceModel?.let { put("deviceModel", it) }
                client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                client.platform?.let { put("platform", it) }
                creds.visitorData?.let { put("visitorData", it) }
                put("timeZone", "UTC")
                put("utcOffsetMinutes", 0)
            }
            putJsonObject("user") {
                put("lockedSafetyMode", false)
                if (creds.signedIn && client.supportsCookies) {
                    creds.dataSyncId?.let { put("onBehalfOfUser", it) }
                }
            }
            putJsonObject("request") {
                put("useSsl", true)
                putJsonArray("internalExperimentFlags") { }
            }
        }

    private fun Request.Builder.applyHeaders(
        client: ClientInfo,
        creds: Credentials,
    ): Request.Builder {
        header("User-Agent", client.userAgent)
        header("Accept-Language", "${session.locale.hl},en;q=0.8")
        header("X-YouTube-Client-Name", client.clientId.toString())
        header("X-YouTube-Client-Version", client.clientVersion)
        header("Origin", BASE)
        header("X-Origin", BASE)
        header("Referer", "$BASE/")
        creds.visitorData?.let { header("X-Goog-Visitor-Id", it) }
        if (creds.signedIn && client.supportsCookies) {
            val cookie = creds.cookie!!
            header("Cookie", cookie)
            sapisidHash(cookie)?.let { header("Authorization", it) }
            header("X-Goog-AuthUser", creds.authUser.toString())
            creds.dataSyncId?.let { header("X-Goog-PageId", it) }
            header("X-Youtube-Bootstrap-Logged-In", "true")
        } else {
            header("Cookie", "SOCS=CAI; PREF=hl=${session.locale.hl}")
        }
        return this
    }

    private suspend fun call(
        endpoint: String,
        body: JsonObject,
        client: ClientInfo = Clients.WEB,
        bootstrap: Boolean = true,
    ): JsonElement {
        if (bootstrap) ensureBootstrapped()
        val creds = session.current
        val payload =
            buildJsonObject {
                put("context", context(client, creds))
                body.forEach { (k, v) -> put(k, v) }
            }
        val request =
            Request
                .Builder()
                .url("$API$endpoint?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .applyHeaders(client, creds)
                .build()
        val res = http.execute(request)
        if (res.code == 401 || res.code == 403) {
            throw LoginRequiredException("$endpoint: HTTP ${res.code}")
        }
        val parsed =
            try {
                json.parseToJsonElement(res.body)
            } catch (e: Exception) {
                throw ParseException("$endpoint: response is not JSON (HTTP ${res.code}): ${res.body.take(200)}", e)
            }
        if (res.code >= 400) {
            if (requestedLoginError(parsed)) {
                throw LoginRequiredException("$endpoint: ${parsed["error"]["message"].str}")
            }
            throw ApiException(
                res.code,
                parsed["error"]["status"].str,
                "$endpoint: HTTP ${res.code} ${parsed["error"]["message"].str ?: ""}".trim(),
            )
        }
        return parsed
    }

    private inline fun <T> parsing(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: InnerTubeException) {
            throw e
        } catch (e: Exception) {
            throw ParseException("$what: ${e.message}", e)
        }

    private fun requireSignedIn(action: String) {
        if (!session.current.signedIn) throw LoginRequiredException("$action requires a signed-in session")
    }

    private suspend fun browseRaw(
        browseId: String?,
        params: String? = null,
        continuation: String? = null,
        query: String? = null,
    ): JsonElement =
        call(
            "browse",
            buildJsonObject {
                if (continuation != null) {
                    put("continuation", continuation)
                } else {
                    put("browseId", browseId ?: error("browseId or continuation required"))
                    params?.let { put("params", it) }
                    query?.let { put("query", it) }
                }
            },
        )

    suspend fun browse(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
    ): Page<Item> {
        val res = browseRaw(browseId, params, continuation)
        return parsing("browse $browseId") {
            if (browseId in ACCOUNT_FEEDS && isLoginPrompt(res)) {
                throw LoginRequiredException("$browseId needs a signed-in session")
            }
            pageOf(res)
        }
    }

    override suspend fun home(continuation: String?): Page<Item> = browse("FEwhat_to_watch", continuation = continuation)

    override suspend fun trending(continuation: String?): Page<Item> {
        if (continuation != null) return browse("FEtrending", continuation = continuation)
        return try {
            browse("FEtrending")
        } catch (e: ApiException) {
            if (e.httpCode != 400) throw e
            Page(
                coroutineScope {
                    TRENDING_FALLBACK
                        .map { (title, id) ->
                            async {
                                val videos =
                                    try {
                                        browse(id)
                                            .items
                                            .flatMap { if (it is Shelf) it.items else listOf(it) }
                                            .filterIsInstance<Video>()
                                            .take(24)
                                    } catch (_: ApiException) {
                                        emptyList()
                                    }
                                Shelf(title, videos)
                            }
                        }.map { it.await() }
                        .filter { it.items.isNotEmpty() }
                },
                null,
            )
        }
    }

    override suspend fun shorts(continuation: String?): Page<Item> {
        if (continuation != null) return reelSequence(continuation)
        if (session.current.signedIn) {
            try {
                return browse("FEshorts")
            } catch (e: ApiException) {
                if (e.httpCode != 400) throw e
            }
        }
        val seed =
            call(
                "reel/reel_item_watch",
                buildJsonObject {
                    put("params", "CA8%3D")
                    put("inputType", "REEL_WATCH_INPUT_TYPE_SEEDLESS")
                    put("disablePlayerResponse", true)
                },
            )
        return parsing("shorts seed") {
            val first = seed["replacementEndpoint"]["reelWatchEndpoint"]?.let { reelEntryVideo(it, seed["overlay"]) }
            val sequence = seed["sequenceContinuation"].str
            val rest = if (sequence != null) reelSequence(sequence) else Page(emptyList(), null)
            Page(
                listOfNotNull(first) + rest.items.filter { (it as? Video)?.id != first?.id },
                rest.continuation,
            )
        }
    }

    private suspend fun reelSequence(sequenceParams: String): Page<Item> {
        val res = call("reel/reel_watch_sequence", buildJsonObject { put("sequenceParams", sequenceParams) })
        return parsing("reel sequence") {
            Page(
                res["entries"].list.mapNotNull { entry ->
                    entry["command"]["reelWatchEndpoint"]?.let { reelEntryVideo(it, null) }
                },
                res["continuationEndpoint"]["continuationCommand"]["token"].str,
            )
        }
    }

    private fun reelEntryVideo(
        endpoint: JsonElement,
        overlay: JsonElement?,
    ): Video? {
        val videoId = endpoint["videoId"].str ?: return null
        val details = endpoint["unserializedPrefetchData"]["playerResponse"]["videoDetails"]
        val header = (overlay ?: endpoint["overlay"]).findFirst("reelPlayerHeaderRenderer")
        val channelBar = (overlay ?: endpoint["overlay"]).findFirst("reelChannelBarViewModel")
        val title =
            details["title"].str
                ?: header["reelTitleText"].text
                ?: (overlay ?: endpoint["overlay"]).findFirst("shortsVideoTitleViewModel")["text"].text
                ?: ""
        val channelName =
            details["author"].str
                ?: header["channelTitleText"].text
                ?: channelBar["channelName"].text
        val channel =
            channelName?.let { name ->
                ChannelRef(
                    details["channelId"].str
                        ?: browseIdOf(header["channelNavigationEndpoint"])
                        ?: browseIdOf(channelBar),
                    name,
                    thumbnails(header["channelThumbnail"]).ifEmpty {
                        thumbnails(channelBar.findFirst("avatarViewModel")["image"])
                    },
                )
            }
        return Video(
            id = videoId,
            title = title,
            channel = channel,
            thumbnails = thumbnails(endpoint["thumbnail"]),
            durationSeconds = details["lengthSeconds"].long,
            viewCountText = details["viewCount"].str?.let { "$it views" },
            publishedText = header["timestampText"].text,
            isShort = true,
        )
    }

    override suspend fun subscriptionsFeed(continuation: String?): Page<Item> {
        requireSignedIn("subscriptions feed")
        return browse("FEsubscriptions", continuation = continuation)
    }

    override suspend fun history(continuation: String?): Page<Item> {
        requireSignedIn("history")
        return browse("FEhistory", continuation = continuation)
    }

    override suspend fun library(): LibraryPage {
        requireSignedIn("library")
        val items = browse("FElibrary").items
        val flat = items.flatMap { if (it is Shelf) it.items else listOf(it) }
        val history =
            items
                .filterIsInstance<Shelf>()
                .firstOrNull { it.title?.contains("History", ignoreCase = true) == true }
        val playlists = flat.filterIsInstance<Playlist>()
        return LibraryPage(
            history = history?.items?.filterIsInstance<Video>() ?: emptyList(),
            watchLater = playlists.firstOrNull { it.id == "WL" || it.id == "VLWL" },
            likedVideos = playlists.firstOrNull { it.id == "LL" || it.id == "VLLL" },
            playlists = playlists.filterNot { it.id in setOf("WL", "VLWL", "LL", "VLLL") },
        )
    }

    override suspend fun userPlaylists(continuation: String?): Page<Playlist> {
        requireSignedIn("playlists")
        val page = browse("FEplaylist_aggregation", continuation = continuation)
        return Page(
            page.items.flatMap { if (it is Shelf) it.items else listOf(it) }.filterIsInstance<Playlist>(),
            page.continuation,
        )
    }

    override suspend fun subscriptions(): List<Subscription> {
        if (!session.current.signedIn) return emptyList()
        val res = call("guide", buildJsonObject { })
        return parsing("guide") { parseGuideSubscriptions(res) }
    }

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        continuation: String?,
    ): Page<Item> {
        val res =
            call(
                "search",
                buildJsonObject {
                    if (continuation != null) {
                        put("continuation", continuation)
                    } else {
                        put("query", query)
                        encodeSearchParams(filter)?.let { put("params", it) }
                    }
                },
            )
        return parsing("search") { pageOf(res) }
    }

    override suspend fun searchSuggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val url =
            "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&xhr=t" +
                "&hl=${session.locale.hl}&gl=${session.locale.gl}&q=${URLEncoder.encode(query, "UTF-8")}"
        val res =
            http.execute(
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", Clients.WEB_USER_AGENT)
                    .build(),
            )
        if (res.code != 200) throw ApiException(res.code, null, "suggestions: HTTP ${res.code}")
        return parsing("suggestions") {
            val text =
                res.body.trim().let {
                    if (it.startsWith("window.")) it.substringAfter('(').substringBeforeLast(')') else it
                }
            json.parseToJsonElement(text)[1].list.mapNotNull { it[0].str ?: it.str }
        }
    }

    private suspend fun resolveChannelId(idOrHandle: String): String {
        if (idOrHandle.startsWith("UC") && idOrHandle.length == 24) return idOrHandle
        val url =
            when {
                idOrHandle.startsWith("http") -> idOrHandle
                idOrHandle.startsWith("@") -> "$BASE/$idOrHandle"
                idOrHandle.startsWith("/") -> "$BASE$idOrHandle"
                else -> "$BASE/@$idOrHandle"
            }
        return (resolve(url) as? Endpoint.Browse)?.browseId?.takeIf { it.startsWith("UC") }
            ?: throw NotFoundException("No channel for $idOrHandle")
    }

    override suspend fun channel(
        idOrHandle: String,
        tab: ChannelTabKind,
        params: String?,
        continuation: String?,
    ): ChannelPage {
        val channelId = resolveChannelId(idOrHandle)

        if (continuation != null) {
            val res = browseRaw(null, continuation = continuation)
            val cached = channelCache[channelId]
            return parsing("channel continuation") {
                ChannelPage(
                    cached?.first ?: Channel(channelId, ""),
                    cached?.second ?: emptyList(),
                    tab,
                    pageOf(res),
                )
            }
        }

        if (tab == ChannelTabKind.ABOUT) {
            val home = browseRaw(channelId)
            return parsing("channel about") {
                var header = parseChannelHeader(home, channelId)
                val tabs = parseChannelTabs(home)
                channelAboutContinuation(home)?.let { token ->
                    header = applyAbout(header, browseRaw(null, continuation = token))
                }
                channelCache[channelId] = header to tabs
                ChannelPage(header, tabs, ChannelTabKind.ABOUT, Page(emptyList(), null))
            }
        }

        val res = browseRaw(channelId, params ?: TAB_PARAMS[tab])
        return parsing("channel $channelId") {
            if (isUnavailablePrompt(res) != null && res["header"] == null) {
                throw NotFoundException("Channel $channelId is unavailable")
            }
            val header = parseChannelHeader(res, channelId)
            val channelTabs = parseChannelTabs(res)
            channelCache[channelId] = header to channelTabs
            val selected = tabs(res).firstOrNull { it["tabRenderer"]["selected"].bool == true }["tabRenderer"]
            val selectedKind = channelTabKind(selected["title"].str) ?: tab
            val content = selected["content"] ?: selectedTab(res)["content"]
            ChannelPage(
                header,
                channelTabs,
                selectedKind,
                Page(parseItems(listOfNotNull(content)), continuationToken(content)),
            )
        }
    }

    override suspend fun playlist(
        playlistId: String,
        continuation: String?,
    ): PlaylistPage {
        val id = playlistId.removePrefix("VL")
        if (continuation != null) {
            val res = browseRaw(null, continuation = continuation)
            val cached = playlistCache[id]
            return parsing("playlist continuation") {
                val page = pageOf(res)
                PlaylistPage(
                    cached?.first ?: Playlist(id, ""),
                    Page(page.items.filterIsInstance<Video>(), page.continuation),
                    cached?.second ?: false,
                )
            }
        }
        val res = browseRaw("VL$id")
        return parsing("playlist $id") {
            val alert = res["alerts"].findFirst("alertRenderer")
            if (res["contents"] == null && alert["type"].str == "ERROR") {
                throw NotFoundException(alert["text"].text ?: "Playlist $id is unavailable")
            }
            if (isLoginPrompt(res)) throw LoginRequiredException("Playlist $id needs a signed-in session")
            val (playlist, editable) = parsePlaylistHeader(res, id)
            playlistCache[id] = playlist to editable
            val content = selectedTab(res)["content"]
            val page = pageOf(res)
            PlaylistPage(
                playlist,
                Page(page.items.filterIsInstance<Video>(), page.continuation ?: continuationToken(content)),
                editable,
            )
        }
    }

    override suspend fun resolve(url: String): Endpoint {
        val normalized =
            when {
                url.startsWith("@") -> "$BASE/$url"
                url.startsWith("youtu") || url.startsWith("www.") || url.startsWith("m.") -> "https://$url"
                else -> url
            }
        val httpUrl = normalized.toHttpUrlOrNull() ?: return Endpoint.Url(url)
        val host = httpUrl.host.removePrefix("www.").removePrefix("m.")
        val segments = httpUrl.pathSegments.filter { it.isNotEmpty() }
        val q: (String) -> String? = { name -> httpUrl.queryParameter(name) }
        val watch: (String?) -> Endpoint.Watch? = { id ->
            id?.takeIf { it.length == 11 }?.let {
                Endpoint.Watch(
                    it,
                    q("list"),
                    parseStartSeconds(q("t") ?: q("start") ?: httpUrl.fragment?.removePrefix("t=")),
                    q("index")?.toIntOrNull(),
                )
            }
        }

        if (host == "youtu.be") watch(segments.firstOrNull())?.let { return it }
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
            when (segments.firstOrNull()) {
                null -> {
                    return Endpoint.Browse("FEwhat_to_watch")
                }

                "watch" -> {
                    watch(q("v"))?.let { return it }
                }

                "shorts", "embed", "live", "v", "e" -> {
                    watch(segments.getOrNull(1))?.let { return it }
                }

                "playlist" -> {
                    q("list")?.let { return Endpoint.Browse("VL$it") }
                }

                "results" -> {
                    q("search_query")?.let { return Endpoint.Search(it, q("sp")) }
                }

                "channel" -> {
                    segments.getOrNull(1)?.takeIf { it.startsWith("UC") }?.let { return Endpoint.Browse(it) }
                }

                "feed" -> {
                    segments.getOrNull(1)?.let { feed ->
                        return Endpoint.Browse(
                            when (feed) {
                                "subscriptions" -> "FEsubscriptions"
                                "trending" -> "FEtrending"
                                "history" -> "FEhistory"
                                "library" -> "FElibrary"
                                "playlists" -> "FEplaylist_aggregation"
                                else -> "FEwhat_to_watch"
                            },
                        )
                    }
                }
            }
        }

        val res = call("navigation/resolve_url", buildJsonObject { put("url", normalized) })
        return parsing("resolve") {
            val endpoint = res["endpoint"]
            endpoint["browseEndpoint"]?.let { browse ->
                val id = browse["browseId"].str ?: return Endpoint.Url(url)
                return Endpoint.Browse(
                    id,
                    browse["params"].str,
                    browse["canonicalBaseUrl"].str?.let { "$BASE$it" },
                )
            }
            endpoint["watchEndpoint"]?.let { w ->
                val id = w["videoId"].str ?: return Endpoint.Url(url)
                return Endpoint.Watch(id, w["playlistId"].str, w["startTimeSeconds"].long, w["index"].int)
            }
            endpoint["searchEndpoint"]?.let { search ->
                return Endpoint.Search(search["query"].str ?: "", search["params"].str)
            }
            endpoint["urlEndpoint"]["url"].str?.let { target ->
                if (target != normalized && target.contains("youtube.com/watch")) return resolve(target)
                return Endpoint.Url(target)
            }
            Endpoint.Url(url)
        }
    }

    private fun parseStartSeconds(t: String?): Long? {
        if (t.isNullOrBlank()) return null
        t.toLongOrNull()?.let { return it }
        val input = t.removeSuffix("s").let { if (it.any(Char::isLetter)) t else it + "s" }
        val (h, m, s) = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(input)?.destructured ?: return null
        return (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
    }

    override suspend fun video(
        videoId: String,
        playlistId: String?,
        playlistIndex: Int?,
    ): VideoDetails {
        val res =
            call(
                "next",
                buildJsonObject {
                    put("videoId", videoId)
                    playlistId?.let { put("playlistId", it) }
                    playlistIndex?.let { put("playlistIndex", it) }
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                },
            )
        return parsing("next $videoId") {
            isUnavailablePrompt(res)?.let { throw NotFoundException("$videoId: $it") }
            parseVideoDetails(res, videoId)
        }
    }

    override suspend fun related(continuation: String): Page<Item> {
        val res = call("next", buildJsonObject { put("continuation", continuation) })
        return parsing("related") { pageOf(res) }
    }

    override suspend fun comments(continuation: String): Page<Comment> {
        val res = call("next", buildJsonObject { put("continuation", continuation) })
        return parsing("comments") { parseComments(res) }
    }

    override suspend fun commentReplies(continuation: String): Page<Comment> = comments(continuation)

    override suspend fun player(
        videoId: String,
        client: ClientKind,
        playlistId: String?,
    ): PlayerResponse = player(videoId, client, playlistId, null)

    suspend fun player(
        videoId: String,
        client: ClientKind,
        playlistId: String?,
        signatureTimestamp: Int?,
        poToken: String? = null,
    ): PlayerResponse {
        // The page bootstrap is what fills in this.signatureTimestamp, so it has to run before the body is built.
        ensureBootstrapped()
        val info = Clients.get(client)
        val sts = signatureTimestamp ?: this.signatureTimestamp?.takeIf { info.wantsSignatureTimestamp }
        val res =
            call(
                "player",
                buildJsonObject {
                    put("videoId", videoId)
                    playlistId?.let { put("playlistId", it) }
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    putJsonObject("playbackContext") {
                        putJsonObject("contentPlaybackContext") {
                            put("html5Preference", "HTML5_PREF_WANTS")
                            sts?.let { put("signatureTimestamp", it) }
                        }
                    }
                    poToken?.let { putJsonObject("serviceIntegrityDimensions") { put("poToken", it) } }
                },
                info,
            )
        return parsing("player $videoId") {
            val response = parsePlayer(res, videoId, info.clientName)
            if (response.playability.status == "ERROR") {
                throw NotFoundException("$videoId: ${response.playability.reason ?: "unavailable"}")
            }
            response
        }
    }

    override suspend fun accountInfo(): AccountInfo? {
        if (!session.current.signedIn) return null
        val res = call("account/account_menu", buildJsonObject { })
        return parsing("account_menu") { parseAccountInfo(res) }
    }

    override suspend fun accounts(): List<AccountIdentity> {
        if (!session.current.signedIn) return emptyList()
        ensureBootstrapped()
        val creds = session.current.copy(dataSyncId = null)
        val request =
            Request
                .Builder()
                .url("$BASE/getAccountSwitcherEndpoint")
                .applyHeaders(Clients.WEB, creds)
                .build()
        val fromSwitcher =
            runCatching {
                val res = http.execute(request)
                check(res.code == 200) { "HTTP ${res.code}" }
                // Body starts with the anti-XSSI guard.
                parseAccountIdentities(json.parseToJsonElement(res.body.removePrefix(")]}'").trimStart()))
            }.getOrDefault(emptyList())
        if (fromSwitcher.isNotEmpty()) return fromSwitcher
        val res = call("account/accounts_list", buildJsonObject { })
        return parsing("accounts_list") { parseAccountIdentities(res) }
    }

    override suspend fun setLike(
        videoId: String,
        status: LikeStatus,
    ) {
        requireSignedIn("like")
        val endpoint =
            when (status) {
                LikeStatus.LIKE -> "like/like"
                LikeStatus.DISLIKE -> "like/dislike"
                LikeStatus.INDIFFERENT -> "like/removelike"
            }
        call(endpoint, buildJsonObject { putJsonObject("target") { put("videoId", videoId) } })
    }

    override suspend fun subscribe(
        channelId: String,
        subscribed: Boolean,
    ) {
        requireSignedIn("subscribe")
        call(
            if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
            buildJsonObject {
                putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
                put("params", if (subscribed) "EgIIAhgA" else "CgIIAhgA")
            },
        )
    }

    override suspend fun createPlaylist(
        title: String,
        videoIds: List<String>,
        privacy: PlaylistPrivacy,
    ): String {
        requireSignedIn("create playlist")
        val res =
            call(
                "playlist/create",
                buildJsonObject {
                    put("title", title)
                    put("privacyStatus", privacy.name)
                    if (videoIds.isNotEmpty()) {
                        put("videoIds", buildJsonArray { videoIds.forEach { add(JsonPrimitive(it)) } })
                    }
                },
            )
        return res["playlistId"].str ?: throw ParseException("playlist/create returned no playlistId")
    }

    override suspend fun deletePlaylist(playlistId: String) {
        requireSignedIn("delete playlist")
        call("playlist/delete", buildJsonObject { put("playlistId", playlistId.removePrefix("VL")) })
    }

    override suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
    ) {
        requireSignedIn("add to playlist")
        editPlaylist(
            playlistId,
            buildJsonObject {
                put("action", "ACTION_ADD_VIDEO")
                put("addedVideoId", videoId)
            },
        )
    }

    override suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String?,
    ) {
        requireSignedIn("remove from playlist")
        editPlaylist(
            playlistId,
            buildJsonObject {
                if (setVideoId != null) {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                } else {
                    put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                    put("removedVideoId", videoId)
                }
            },
            confirmRemoval = true,
        )
    }

    private suspend fun editPlaylist(
        playlistId: String,
        action: JsonObject,
        confirmRemoval: Boolean = false,
    ) {
        val res =
            call(
                "browse/edit_playlist",
                buildJsonObject {
                    put("playlistId", playlistId.removePrefix("VL"))
                    putJsonArray("actions") { add(action) }
                    if (confirmRemoval && playlistId.removePrefix("VL") == "WL") put("params", "CAFAAQ%3D%3D")
                },
            )
        val status = res["status"].str
        if ((confirmRemoval && status != "STATUS_SUCCEEDED") || (status != null && status != "STATUS_SUCCEEDED")) {
            throw InnerTubeException("edit_playlist: ${status ?: "removal not confirmed"}")
        }
    }

    override suspend fun removeFromHistory(videoId: String) {
        requireSignedIn("remove from history")
        var continuation: String? = null
        for (page in 0 until 5) {
            val res = browseRaw("FEhistory", continuation = continuation)
            if (isLoginPrompt(res)) throw LoginRequiredException("FEhistory needs a signed-in session")
            val candidates = receivedItems(res).ifEmpty { listOfNotNull(selectedTab(res)["content"]) }
            val token =
                candidates
                    .asSequence()
                    .flatMap { it.findAll("videoRenderer").asSequence() + it.findAll("lockupViewModel").asSequence() }
                    .firstOrNull { (it["videoId"].str ?: it["contentId"].str) == videoId }
                    ?.findFirst("feedbackToken")
                    .str
            if (token != null) {
                call(
                    "feedback",
                    buildJsonObject {
                        putJsonArray("feedbackTokens") { add(JsonPrimitive(token)) }
                        put("isFeedbackTokenUnencrypted", false)
                        put("shouldMerge", false)
                    },
                )
                return
            }
            continuation = candidates.firstNotNullOfOrNull { continuationToken(it) } ?: break
        }
        throw NotFoundException("$videoId not found in recent history")
    }

    override suspend fun reportPlayback(
        tracking: PlaybackTracking,
        positionSeconds: Long,
        lengthSeconds: Long?,
        cpn: String,
    ) {
        ensureBootstrapped()
        val creds = session.current

        suspend fun ping(
            base: String?,
            watchtime: Boolean,
        ) {
            val url = base?.toHttpUrlOrNull()?.newBuilder() ?: return
            url.setQueryParameter("ver", "2")
            url.setQueryParameter("cpn", cpn)
            url.setQueryParameter("cmt", positionSeconds.toString())
            url.setQueryParameter("el", "detailpage")
            lengthSeconds?.let { url.setQueryParameter("len", it.toString()) }
            if (watchtime) {
                url.setQueryParameter("st", "0")
                url.setQueryParameter("et", positionSeconds.toString())
            }
            val res =
                http.execute(
                    Request
                        .Builder()
                        .url(url.build())
                        .applyHeaders(Clients.WEB, creds)
                        .build(),
                )
            if (res.code == 401 || res.code == 403) throw LoginRequiredException("videostats: HTTP ${res.code}")
            if (res.code >= 400) throw ApiException(res.code, null, "videostats: HTTP ${res.code}")
        }

        ping(tracking.videostatsPlaybackUrl, false)
        ping(tracking.videostatsWatchtimeUrl, true)
    }

    companion object {
        private const val BASE = "https://www.youtube.com"
        private const val API = "https://www.youtube.com/youtubei/v1/"
        private val JSON_MEDIA = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

        private val ACCOUNT_FEEDS = setOf("FEsubscriptions", "FEhistory", "FElibrary", "FEplaylist_aggregation")

        private val TRENDING_FALLBACK =
            listOf(
                "Music" to "UC-9-kyTW8ZkZNDHQJ6FgpwQ",
                "Gaming" to "UCOpNcN46UbXVtpKMrmU4Abg",
                "Live" to "UC4R8DWoMoI7CAwX8_LjQHig",
            )

        private val TAB_PARAMS =
            mapOf(
                ChannelTabKind.VIDEOS to "EgZ2aWRlb3PyBgQKAjoA",
                ChannelTabKind.SHORTS to "EgZzaG9ydHPyBgUKA5oBAA%3D%3D",
                ChannelTabKind.LIVE to "EgdzdHJlYW1z8gYECgJ6AA%3D%3D",
                ChannelTabKind.PLAYLISTS to "EglwbGF5bGlzdHPyBgQKAkIA",
                ChannelTabKind.COMMUNITY to "Egljb21tdW5pdHnyBgQKAkoA",
            )
    }
}

private fun <K, V> lruMap(max: Int): MutableMap<K, V> =
    object : LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
    }
