package tube.three06.innertube.internal

import java.net.URLDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import tube.three06.innertube.model.AccountIdentity
import tube.three06.innertube.model.AccountInfo
import tube.three06.innertube.model.AudioTrack
import tube.three06.innertube.model.ByteRange
import tube.three06.innertube.model.CaptionTrack
import tube.three06.innertube.model.Channel
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.ChannelTab
import tube.three06.innertube.model.ChannelTabKind
import tube.three06.innertube.model.Chapter
import tube.three06.innertube.model.Comment
import tube.three06.innertube.model.Item
import tube.three06.innertube.model.LikeStatus
import tube.three06.innertube.model.LinkTarget
import tube.three06.innertube.model.Page
import tube.three06.innertube.model.Playability
import tube.three06.innertube.model.PlaybackTracking
import tube.three06.innertube.model.PlayerResponse
import tube.three06.innertube.model.Playlist
import tube.three06.innertube.model.PlaylistPanel
import tube.three06.innertube.model.Shelf
import tube.three06.innertube.model.ShelfKind
import tube.three06.innertube.model.StreamFormat
import tube.three06.innertube.model.Subscription
import tube.three06.innertube.model.TextLink
import tube.three06.innertube.model.Thumbnail
import tube.three06.innertube.model.Video
import tube.three06.innertube.model.VideoDetails

internal fun thumbnails(e: JsonElement?): List<Thumbnail> {
    if (e == null) return emptyList()
    val arr = when {
        e.arr != null -> e.arr
        e["thumbnails"] != null -> e["thumbnails"].arr
        e["sources"] != null -> e["sources"].arr
        e["image"] != null -> return thumbnails(e["image"])
        e["thumbnail"] != null -> return thumbnails(e["thumbnail"])
        else -> null
    } ?: return emptyList()
    return arr.mapNotNull { t ->
        val url = t["url"].str ?: return@mapNotNull null
        Thumbnail(
            url = if (url.startsWith("//")) "https:$url" else url,
            width = t["width"].int,
            height = t["height"].int,
        )
    }
}

internal fun defaultThumbnails(videoId: String): List<Thumbnail> = listOf(
    Thumbnail("https://i.ytimg.com/vi/$videoId/mqdefault.jpg", 320, 180),
    Thumbnail("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", 480, 360),
)

internal fun durationSeconds(text: String?): Long? {
    val parts = text?.trim()?.split(':') ?: return null
    if (parts.isEmpty() || parts.size > 3) return null
    if (!parts.all { p -> p.isNotBlank() && p.all { it.isDigit() } }) return null
    return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() }
}

internal fun parseCount(text: String?): Long? {
    val match = text?.trim()?.replace(",", "")?.replace(".", "#")
        ?.let { Regex("(\\d+)(?:#(\\d+))?\\s*([KMB])?", RegexOption.IGNORE_CASE).find(it) }
        ?: return null
    val whole = match.groupValues[1].toDouble()
    val fraction = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { "0.$it".toDouble() } ?: 0.0
    val multiplier = when (match.groupValues[3].uppercase()) {
        "K" -> 1_000.0
        "M" -> 1_000_000.0
        "B" -> 1_000_000_000.0
        else -> 1.0
    }
    return ((whole + fraction) * multiplier).toLong()
}

internal fun browseIdOf(e: JsonElement?): String? =
    e.findFirst("browseEndpoint")["browseId"].str

internal fun handleOf(e: JsonElement?): String? =
    e.findFirst("browseEndpoint")["canonicalBaseUrl"].str
        ?.removePrefix("/")
        ?.takeIf { it.startsWith("@") }

private fun verifiedBadge(badges: JsonElement?): Boolean =
    badges.list.any { (it["metadataBadgeRenderer"]["style"].str ?: "").contains("VERIFIED") }

internal fun channelRef(
    byline: JsonElement?,
    avatar: JsonElement? = null,
    badges: JsonElement? = null,
): ChannelRef? {
    val run = byline["runs"][0] ?: return null
    val name = run["text"].str ?: return null
    return ChannelRef(
        id = browseIdOf(run["navigationEndpoint"]) ?: browseIdOf(byline),
        name = name,
        avatar = thumbnails(avatar),
        verified = verifiedBadge(badges),
        handle = handleOf(run["navigationEndpoint"]),
    )
}

internal fun continuationToken(e: JsonElement?): String? {
    val item = e.findFirst("continuationItemRenderer") ?: e.findFirst("continuationItemViewModel")
    return item?.findFirst("continuationCommand")["token"].str
        ?: item?.findFirst("token").str
        ?: e.findFirst("nextContinuationData")["continuation"].str
}

internal fun receivedItems(response: JsonElement?): List<JsonElement> {
    val out = ArrayList<JsonElement>()
    for (key in listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints")) {
        for (action in response[key].list) {
            out += action["appendContinuationItemsAction"]["continuationItems"].list
            out += action["reloadContinuationItemsCommand"]["continuationItems"].list
        }
    }
    response["continuationContents"].obj?.values?.forEach { c ->
        out += c["contents"].list
        out += c["items"].list
    }
    return out
}

private val CONTAINER_KEYS = setOf(
    "itemSectionRenderer",
    "sectionListRenderer",
    "richGridRenderer",
    "gridRenderer",
    "richItemRenderer",
    "richSectionRenderer",
    "horizontalListRenderer",
    "verticalListRenderer",
    "expandedShelfContentsRenderer",
    "tabRenderer",
    "showingResultsForRenderer",
)

internal fun parseItems(elements: List<JsonElement>): List<Item> {
    val out = ArrayList<Item>()
    elements.forEach { collect(it, out) }
    return out
}

private fun collect(e: JsonElement?, out: MutableList<Item>) {
    val obj = e.obj ?: return
    val keys = obj.keys.filter { it != "trackingParams" && it != "clickTrackingParams" }
    if (keys.size > 1 && keys.all { it.endsWith("Renderer") || it.endsWith("ViewModel") }) {
        for (key in keys) collect(JsonObject(mapOf(key to obj.getValue(key))), out)
        return
    }
    val name = keys.firstOrNull() ?: return
    val body = obj[name]
    when (name) {
        "itemSectionRenderer", "sectionListRenderer", "richGridRenderer",
        "expandedShelfContentsRenderer", "showingResultsForRenderer",
        -> body["contents"].list.forEach { collect(it, out) }

        "gridRenderer", "horizontalListRenderer", "verticalListRenderer",
        -> body["items"].list.forEach { collect(it, out) }

        "richItemRenderer", "richSectionRenderer" -> collect(body["content"], out)

        "tabRenderer" -> collect(body["content"], out)

        "shelfRenderer", "richShelfRenderer", "reelShelfRenderer", "gridShelfViewModel",
        "horizontalCardListRenderer",
        -> parseShelf(name, body)?.let { out += it }

        else -> {
            val item = parseItem(e)
            if (item != null) {
                out += item
            } else if (name.endsWith("ResultsRenderer") || name == "primaryContents" ||
                name == "tabs" || name.endsWith("TabRenderer")
            ) {
                body["primaryContents"]?.let { collect(it, out) }
                body["contents"].list.forEach { collect(it, out) }
                body["items"].list.forEach { collect(it, out) }
                body["content"]?.let { collect(it, out) }
                body["tabs"].list
                    .firstOrNull { it["tabRenderer"]["selected"].bool == true }
                    ?.let { collect(it, out) }
            }
        }
    }
}

private fun parseShelf(name: String, body: JsonElement?): Shelf? {
    if (name == "horizontalCardListRenderer") return null
    val title = body["title"].text ?: body["header"].findFirst("title").text
    val elements = when (name) {
        "shelfRenderer" -> body["content"].obj?.values?.firstOrNull()
            ?.let { inner -> inner["items"].list.ifEmpty { inner["contents"].list } }
            ?: emptyList()
        "reelShelfRenderer" -> body["items"].list
        else -> body["contents"].list.ifEmpty { body["items"].list }
    }
    val items = parseItems(elements)
    if (items.isEmpty()) return null
    val kind = when {
        name == "reelShelfRenderer" || items.all { it is Video && it.isShort } -> ShelfKind.SHORTS
        items.all { it is Playlist && it.id.startsWith("RD") } -> ShelfKind.MIX
        items.all { it is Playlist } -> ShelfKind.PLAYLISTS
        items.all { it is Channel } -> ShelfKind.CHANNELS
        else -> ShelfKind.GENERIC
    }
    return Shelf(
        title = title,
        items = items,
        continuation = continuationToken(body["content"] ?: body["contents"]),
        kind = kind,
    )
}

internal fun parseItem(e: JsonElement?): Item? {
    val obj = e.obj ?: return null
    val name = obj.rendererName ?: return null
    val r = obj[name] ?: return null
    return when (name) {
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer",
        "playlistPanelVideoRenderer", "endScreenVideoRenderer", "videoWithContextRenderer",
        "movieRenderer",
        -> parseVideoRenderer(r)

        "reelItemRenderer" -> parseReelItem(r)
        "shortsLockupViewModel" -> parseShortsLockup(r)
        "lockupViewModel" -> parseLockup(r)

        "channelRenderer", "gridChannelRenderer", "compactChannelRenderer" -> parseChannelRenderer(r)

        "playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer", "radioRenderer",
        "compactRadioRenderer", "compactStationRenderer",
        -> parsePlaylistRenderer(r)

        in CONTAINER_KEYS, "shelfRenderer", "richShelfRenderer", "reelShelfRenderer",
        "gridShelfViewModel",
        -> parseItems(listOf(obj)).singleOrNull()

        else -> null
    }
}

private fun parseVideoRenderer(r: JsonElement): Video? {
    val id = r["videoId"].str
        ?: r["navigationEndpoint"].findFirst("videoId").str
        ?: return null
    val overlays = r["thumbnailOverlays"].list
    val timeStatus = overlays.firstNotNullOfOrNull { it["thumbnailOverlayTimeStatusRenderer"] }
    val style = timeStatus["style"].str
    val badgeTexts = r["badges"].list.mapNotNull {
        it["metadataBadgeRenderer"]["label"].str ?: it["metadataBadgeRenderer"]["style"].str
    }
    val isLive = style == "LIVE" ||
        badgeTexts.any { it.contains("LIVE", ignoreCase = true) } ||
        r["viewCountText"].text?.contains("watching") == true
    val isUpcoming = style == "UPCOMING" || r["upcomingEventData"] != null
    val byline = r["ownerText"] ?: r["shortBylineText"] ?: r["longBylineText"]
    val avatar = r["channelThumbnailSupportedRenderers"].findFirst("thumbnail")
        ?: r["channelThumbnail"]
        ?: r["avatar"].findFirst("image")
    val lengthText = r["lengthText"].text ?: timeStatus["text"].text
    return Video(
        id = id,
        title = r["title"].text ?: r["headline"].text ?: "",
        channel = channelRef(byline, avatar, r["ownerBadges"]),
        thumbnails = thumbnails(r["thumbnail"]),
        durationSeconds = durationSeconds(lengthText) ?: r["lengthSeconds"].long,
        viewCountText = r["viewCountText"].text ?: r["shortViewCountText"].text,
        publishedText = r["publishedTimeText"].text,
        isLive = isLive,
        isUpcoming = isUpcoming,
        isShort = style == "SHORTS" || r["navigationEndpoint"]["reelWatchEndpoint"] != null,
        descriptionSnippet = r["detailedMetadataSnippets"][0]["snippetText"].text
            ?: r["descriptionSnippet"].text,
        progressPercent = overlays.firstNotNullOfOrNull {
            it["thumbnailOverlayResumePlaybackRenderer"]["percentDurationWatched"].int
        },
        resumeSeconds = r["navigationEndpoint"]["watchEndpoint"]["startTimeSeconds"].long,
        badges = r["badges"].list.mapNotNull { it["metadataBadgeRenderer"]["label"].str },
    )
}

private fun parseReelItem(r: JsonElement): Video? {
    val id = r["videoId"].str ?: return null
    return Video(
        id = id,
        title = r["headline"].text ?: "",
        thumbnails = thumbnails(r["thumbnail"]),
        viewCountText = r["viewCountText"].text,
        isShort = true,
    )
}

private fun parseShortsLockup(r: JsonElement): Video? {
    val id = r["onTap"].findFirst("reelWatchEndpoint")["videoId"].str
        ?: r["entityId"].str?.substringAfterLast("shorts-shelf-item-")?.takeIf { it.length == 11 }
        ?: r["inlinePlayerData"].findFirst("videoId").str
        ?: return null
    return Video(
        id = id,
        title = r["overlayMetadata"]["primaryText"].text
            ?: r["accessibilityText"].str?.substringBefore(", ")
            ?: "",
        thumbnails = thumbnails(r["thumbnail"] ?: r["thumbnailViewModel"].findFirst("image"))
            .ifEmpty { defaultThumbnails(id) },
        viewCountText = r["overlayMetadata"]["secondaryText"].text,
        isShort = true,
    )
}

private class BadgeInfo(val duration: Long?, val live: Boolean, val countText: String?)

private fun lockupBadges(image: JsonElement?): BadgeInfo {
    var duration: Long? = null
    var live = false
    var countText: String? = null
    for (badge in image.findAll("thumbnailBadgeViewModel")) {
        val text = badge["text"].str
        if ((badge["badgeStyle"].str ?: "").contains("LIVE")) live = true
        durationSeconds(text)?.let { duration = it }
        if (text != null && Regex("\\d[\\d,.]*\\S* (videos|episodes|songs|lessons)").containsMatchIn(text)) {
            countText = text
        }
    }
    return BadgeInfo(duration, live, countText)
}

private val BIDI_MARKS = Regex("[\\u200E\\u200F\\u2066-\\u2069]")

private fun looksLikeStat(text: String): Boolean =
    text.contains("view") || text.contains("watching") || text.contains(" ago") ||
        text.startsWith("Streamed") || text.startsWith("Scheduled") || text.startsWith("Premiere") ||
        text.endsWith("videos") || text.contains("subscriber")

private fun parseLockup(r: JsonElement): Item? {
    val meta = r["metadata"]["lockupMetadataViewModel"] ?: return null
    val type = r["contentType"].str ?: ""
    val id = r["contentId"].str ?: return null
    val title = meta["title"].text ?: ""
    val rows = meta["metadata"]["contentMetadataViewModel"]["metadataRows"].list
    val parts = rows.flatMap { it["metadataParts"].list }
    val firstRow = rows.firstOrNull()["metadataParts"].list
    val avatar = meta["image"]["decoratedAvatarViewModel"]

    val bylinePart = firstRow.firstOrNull {
        it["text"] != null && (it["text"]["commandRuns"] != null || avatar != null)
    } ?: firstRow.firstOrNull { part ->
        rows.size > 1 && part["text"].text?.let { !looksLikeStat(it) } == true
    }
    val bylineText = bylinePart["text"].text
    val channelId = browseIdOf(avatar["rendererContext"])
        ?: browseIdOf(bylinePart["text"]["commandRuns"])
    val verified = firstRow.any { part ->
        part["text"]["attachmentRuns"].list.any { run ->
            val icon = run.findFirst("imageName").str ?: ""
            icon.contains("CHECK_CIRCLE") || icon.contains("AUDIO_BADGE")
        }
    }
    val channel = bylineText?.let {
        ChannelRef(
            id = channelId,
            name = it,
            avatar = thumbnails(avatar.findFirst("avatarViewModel")["image"]),
            verified = verified,
            handle = handleOf(avatar["rendererContext"])
                ?: handleOf(bylinePart["text"]["commandRuns"]),
        )
    }

    fun partText(predicate: (String) -> Boolean): String? = parts.firstNotNullOfOrNull { part ->
        val text = part["text"].text ?: return@firstNotNullOfOrNull null
        val label = part["accessibilityLabel"].str
        when {
            predicate(text) -> text
            label != null && predicate(label) -> label
            else -> null
        }
    }

    val image = r["contentImage"]
    val badges = lockupBadges(image)

    return when {
        type.contains("VIDEO") || (type.isEmpty() && id.length == 11) -> Video(
            id = id,
            title = title,
            channel = channel,
            thumbnails = thumbnails(image["thumbnailViewModel"]["image"] ?: image.findFirst("image"))
                .ifEmpty { defaultThumbnails(id) },
            durationSeconds = badges.duration,
            viewCountText = partText { it.contains("view") || it.contains("watching") },
            publishedText = partText {
                it.contains(" ago") || it.startsWith("Streamed") || it.startsWith("Premiere") ||
                    it.startsWith("Scheduled")
            },
            isLive = badges.live,
            isUpcoming = partText { it.startsWith("Scheduled") || it.startsWith("Premieres") } != null,
            isShort = r["rendererContext"].findFirst("reelWatchEndpoint") != null,
            progressPercent = image.findFirst("thumbnailOverlayProgressBarViewModel")["startPercent"].int,
            resumeSeconds = r["rendererContext"].findFirst("watchEndpoint")["startTimeSeconds"].long,
        )

        type.contains("PLAYLIST") || type.contains("PODCAST") || type.contains("ALBUM") ||
            type.contains("MIX") || id.startsWith("PL") || id.startsWith("RD") -> {
            val thumb = image["collectionThumbnailViewModel"]["primaryThumbnail"]["thumbnailViewModel"]["image"]
                ?: image.findFirst("image")
            val typeLabels = setOf("Playlist", "Mix", "Podcast", "Album", "Course")
            val owner = when {
                channel == null -> firstRow.firstOrNull()["text"].text
                    ?.takeIf { it !in typeLabels }
                    ?.let { ChannelRef(id = null, name = it) }
                channel.name in typeLabels -> channel.id?.let {
                    ChannelRef(id = it, name = channel.handle ?: it, handle = channel.handle)
                }
                else -> channel
            }
            Playlist(
                id = id,
                title = title,
                thumbnails = thumbnails(thumb),
                channel = owner,
                videoCountText = badges.countText ?: partText { it.contains("video") },
            )
        }

        type.contains("CHANNEL") -> Channel(
            id = id,
            name = title.ifEmpty { bylineText ?: "" },
            handle = partText { it.startsWith("@") },
            avatar = thumbnails(image.findFirst("image")),
            subscriberCountText = partText { it.contains("subscriber") },
            videoCountText = partText { it.contains("video") },
            verified = verified,
        )

        else -> null
    }
}

private fun parseChannelRenderer(r: JsonElement): Channel? {
    val id = r["channelId"].str ?: browseIdOf(r["navigationEndpoint"]) ?: return null
    val counts = listOfNotNull(r["subscriberCountText"].text, r["videoCountText"].text)
    return Channel(
        id = id,
        name = r["title"].text ?: r["displayName"].text ?: "",
        handle = counts.firstOrNull { it.startsWith("@") } ?: handleOf(r["navigationEndpoint"]),
        avatar = thumbnails(r["thumbnail"]),
        subscriberCountText = counts.firstOrNull { it.contains("subscriber", ignoreCase = true) },
        videoCountText = counts.firstOrNull { it.contains("video", ignoreCase = true) },
        description = r["descriptionSnippet"].text,
        verified = verifiedBadge(r["ownerBadges"]),
        subscribed = r["subscriptionButton"]["subscribed"].bool,
    )
}

private fun parsePlaylistRenderer(r: JsonElement): Playlist? {
    val id = r["playlistId"].str ?: return null
    return Playlist(
        id = id,
        title = r["title"].text ?: "",
        thumbnails = thumbnails(r["thumbnail"])
            .ifEmpty { thumbnails(r["thumbnails"][0]) }
            .ifEmpty { thumbnails(r["thumbnailRenderer"].findFirst("thumbnail")) },
        channel = channelRef(r["shortBylineText"] ?: r["longBylineText"]),
        videoCountText = r["videoCountText"].text
            ?: r["videoCountShortText"].text
            ?: r["videoCount"].str?.let { "$it videos" },
        description = r["descriptionSnippet"].text,
    )
}

internal fun tabs(response: JsonElement?): List<JsonElement> =
    response["contents"].findFirst("tabs").list

internal fun selectedTab(response: JsonElement?): JsonElement? {
    val tabs = tabs(response)
    return tabs.firstOrNull { it["tabRenderer"]["selected"].bool == true }["tabRenderer"]
        ?: tabs.firstOrNull { it["tabRenderer"]["content"] != null }["tabRenderer"]
}

internal fun pageOf(response: JsonElement?): Page<Item> {
    val received = receivedItems(response)
    val elements = when {
        received.isNotEmpty() -> received
        response["contents"] != null -> {
            val tab = selectedTab(response)
            listOfNotNull(if (tab != null) tab["content"] else response["contents"])
        }
        else -> emptyList()
    }
    return Page(parseItems(elements), elements.firstNotNullOfOrNull { continuationToken(it) })
}

internal fun isLoginPrompt(response: JsonElement?): Boolean {
    val contents = response["contents"] ?: return false
    val promo = contents.findFirst("backgroundPromoRenderer")
        ?: contents.findFirst("messageRenderer")
        ?: return false
    return promo.contains("signInEndpoint")
}

internal fun isUnavailablePrompt(response: JsonElement?): String? {
    val promo = response["contents"].findFirst("backgroundPromoRenderer") ?: return null
    val title = promo["title"].text ?: return null
    return title.takeIf {
        it.contains("isn't available") || it.contains("not available") || it.contains("unavailable")
    }
}

internal fun requestedLoginError(response: JsonElement?): Boolean {
    val error = response["error"] ?: return false
    val code = error["code"].int
    return code == 401 || code == 403 ||
        error["status"].str == "UNAUTHENTICATED" ||
        error["status"].str == "PERMISSION_DENIED"
}

internal fun channelTabKind(title: String?): ChannelTabKind? = when (title?.lowercase()) {
    "home", "featured" -> ChannelTabKind.HOME
    "videos" -> ChannelTabKind.VIDEOS
    "shorts" -> ChannelTabKind.SHORTS
    "live", "streams" -> ChannelTabKind.LIVE
    "playlists" -> ChannelTabKind.PLAYLISTS
    "community", "posts" -> ChannelTabKind.COMMUNITY
    "about" -> ChannelTabKind.ABOUT
    else -> null
}

/** The view-model button carries no state of its own; it points at an entity in frameworkUpdates. */
private fun subscribedState(response: JsonElement?, button: JsonElement?): Boolean? {
    if (button == null) return null
    button["subscribed"].bool?.let { return it }
    val key = button["stateEntityStoreKey"].str
    val states = response["frameworkUpdates"].findAll("subscriptionStateEntity")
    return (states.firstOrNull { key != null && it["key"].str == key } ?: states.firstOrNull())["subscribed"].bool
}

internal fun parseChannelHeader(response: JsonElement?, fallbackId: String?): Channel {
    val meta = response["metadata"]["channelMetadataRenderer"]
    val header = response["header"]
    val pageHeader = header["pageHeaderRenderer"]
    val c4 = header["c4TabbedHeaderRenderer"]
    val vm = pageHeader["content"]["pageHeaderViewModel"]
    val metaTexts = vm["metadata"]["contentMetadataViewModel"]["metadataRows"].list
        .flatMap { it["metadataParts"].list }
        .mapNotNull { it["text"].text }

    val handle = metaTexts.firstOrNull { it.startsWith("@") }
        ?: c4["channelHandleText"].text
        ?: meta["vanityChannelUrl"].str?.substringAfterLast('/')?.takeIf { it.startsWith("@") }
        ?: meta["ownerUrls"][0].str?.substringAfterLast('/')?.takeIf { it.startsWith("@") }
    val verified = vm["title"]["dynamicTextViewModel"]["text"]["attachmentRuns"].list.any { run ->
        val icon = run.findFirst("imageName").str ?: ""
        icon.contains("CHECK_CIRCLE") || icon.contains("AUDIO_BADGE")
    } || verifiedBadge(c4["badges"])

    return Channel(
        id = meta["externalId"].str ?: c4["channelId"].str ?: fallbackId ?: "",
        name = pageHeader["pageTitle"].str ?: meta["title"].str ?: c4["title"].text ?: "",
        handle = handle,
        avatar = thumbnails(vm["image"].findFirst("avatarViewModel")["image"])
            .ifEmpty { thumbnails(c4["avatar"]) }
            .ifEmpty { thumbnails(meta["avatar"]) },
        banner = thumbnails(vm["banner"].findFirst("image"))
            .ifEmpty { thumbnails(c4["banner"]) },
        subscriberCountText = metaTexts.firstOrNull { it.contains("subscriber") }
            ?: c4["subscriberCountText"].text,
        videoCountText = metaTexts.firstOrNull { it.contains("video") }
            ?: c4["videosCountText"].text,
        description = meta["description"].str
            ?: vm["description"].findFirst("description").text
            ?: c4["tagline"].findFirst("content").text,
        verified = verified,
        subscribed = subscribedState(response, vm["actions"].findFirst("subscribeButtonViewModel"))
            ?: c4["subscribeButton"]["subscribeButtonRenderer"]["subscribed"].bool,
    )
}

internal fun parseChannelTabs(response: JsonElement?): List<ChannelTab> =
    tabs(response).mapNotNull { tab ->
        val r = tab["tabRenderer"] ?: return@mapNotNull null
        val title = r["title"].str ?: return@mapNotNull null
        val kind = channelTabKind(title) ?: return@mapNotNull null
        ChannelTab(kind, title, r["endpoint"]["browseEndpoint"]["params"].str)
    }

internal fun channelAboutContinuation(response: JsonElement?): String? =
    response["header"].findFirst("descriptionPreviewViewModel")?.let { continuationToken(it) }
        ?: response["header"].findFirst("showEngagementPanelEndpoint")?.let { continuationToken(it) }

internal fun applyAbout(channel: Channel, aboutResponse: JsonElement?): Channel {
    val about = aboutResponse.findFirst("aboutChannelViewModel")
        ?: aboutResponse.findFirst("channelAboutFullMetadataRenderer")
        ?: return channel
    return channel.copy(
        description = about["description"].text ?: channel.description,
        subscriberCountText = about["subscriberCountText"].text ?: channel.subscriberCountText,
        videoCountText = about["videoCountText"].text ?: channel.videoCountText,
        handle = channel.handle
            ?: about["canonicalChannelUrl"].str?.substringAfterLast('/')?.takeIf { it.startsWith("@") },
    )
}

internal fun parsePlaylistHeader(response: JsonElement?, playlistId: String): Pair<Playlist, Boolean> {
    val header = response["header"]
    val legacy = header["playlistHeaderRenderer"]
    val pageHeader = header["pageHeaderRenderer"]
    val vm = pageHeader["content"]["pageHeaderViewModel"]
    val sidebar = response["sidebar"].findFirst("playlistSidebarPrimaryInfoRenderer")
    val sidebarOwner = response["sidebar"].findFirst("videoOwnerRenderer")
    val parts = vm["metadata"]["contentMetadataViewModel"]["metadataRows"].list
        .flatMap { it["metadataParts"].list }

    val ownerPart = parts.firstOrNull {
        it["avatarStack"] != null || it["text"]["commandRuns"] != null
    }
    val ownerName = ownerPart["text"].text?.removePrefix("by ")
        ?: legacy["ownerText"].text
        ?: sidebarOwner["title"].text
    val ownerId = browseIdOf(ownerPart)
        ?: browseIdOf(legacy["ownerEndpoint"])
        ?: browseIdOf(sidebarOwner["navigationEndpoint"])
    val channel = ownerName?.let {
        ChannelRef(
            id = ownerId,
            name = it,
            avatar = thumbnails(ownerPart.findFirst("avatarViewModel")["image"])
                .ifEmpty { thumbnails(sidebarOwner["thumbnail"]) },
            handle = handleOf(ownerPart) ?: handleOf(legacy["ownerEndpoint"]),
        )
    }

    val editable = legacy["isEditable"].bool
        ?: vm["actions"].findAll("iconName").any { it.str == "EDIT" || it.str == "DELETE" }

    val playlist = Playlist(
        id = legacy["playlistId"].str
            ?: response["microformat"].findFirst("urlCanonical").str
                ?.substringAfter("list=")?.substringBefore('&')
            ?: playlistId,
        title = pageHeader["pageTitle"].str
            ?: legacy["title"].text
            ?: response["metadata"]["playlistMetadataRenderer"]["title"].str
            ?: "",
        thumbnails = thumbnails(vm["heroImage"].findFirst("image"))
            .ifEmpty { thumbnails(legacy["playlistHeaderBanner"].findFirst("thumbnail")) }
            .ifEmpty { thumbnails(sidebar["thumbnailRenderer"].findFirst("thumbnail")) },
        channel = channel,
        videoCountText = parts.mapNotNull { it["text"].text }.firstOrNull { it.contains("video") }
            ?: legacy["numVideosText"].text
            ?: sidebar["stats"][0].text,
        description = vm["description"].findFirst("description").text
            ?: legacy["descriptionText"].text
            ?: response["metadata"]["playlistMetadataRenderer"]["description"].str,
    )
    return playlist to editable
}

internal fun parseVideoDetails(response: JsonElement, videoId: String): VideoDetails {
    val twoColumn = response["contents"]["twoColumnWatchNextResults"]
    val results = twoColumn["results"]["results"]["contents"].list
    val primary = results.firstNotNullOfOrNull { it["videoPrimaryInfoRenderer"] }
        ?: response.findFirst("videoPrimaryInfoRenderer")
    val secondary = results.firstNotNullOfOrNull { it["videoSecondaryInfoRenderer"] }
        ?: response.findFirst("videoSecondaryInfoRenderer")
    val owner = secondary["owner"]["videoOwnerRenderer"]

    val collaborators = owner["navigationEndpoint"].findAll("listItemViewModel").mapNotNull { vm ->
        val id = browseIdOf(vm) ?: return@mapNotNull null
        val name = vm["title"].text ?: return@mapNotNull null
        val subtitle = vm["subtitle"].text
            ?.let { BIDI_MARKS.replace(it, "") }
            ?.split('•')
            ?.map { it.trim() }
        ChannelRef(
            id = id,
            name = name,
            avatar = thumbnails(vm["leadingAccessory"].findFirst("avatarViewModel")["image"]),
            verified = vm["title"]["attachmentRuns"].findAll("imageName")
                .any { it.str?.contains("CHECK_CIRCLE") == true },
            handle = subtitle?.firstOrNull()?.takeIf { it.startsWith("@") },
            subscriberCountText = subtitle?.getOrNull(1),
        )
    }.distinctBy { it.id }

    val ownerId = browseIdOf(owner["navigationEndpoint"]) ?: browseIdOf(owner["title"])
    val channel = collaborators.firstOrNull() ?: ChannelRef(
        id = ownerId,
        name = owner["title"].text ?: "",
        avatar = thumbnails(owner["thumbnail"]),
        verified = verifiedBadge(owner["badges"]),
        handle = handleOf(owner["navigationEndpoint"]),
        subscriberCountText = owner["subscriberCountText"].text,
    )

    val likeToggle = primary.findFirst("likeButtonViewModel")["likeButtonViewModel"]["toggleButtonViewModel"]["toggleButtonViewModel"]
        ?: primary.findFirst("likeButtonViewModel")["toggleButtonViewModel"]["toggleButtonViewModel"]
    val likeButton = likeToggle["defaultButtonViewModel"]["buttonViewModel"]
    val likeCountText = likeButton["title"].str
    val likeCount = likeButton["accessibilityText"].str
        ?.let { Regex("([\\d,]+) other people").find(it)?.groupValues?.get(1) }
        ?.let { parseCount(it) }
        ?: likeCountText?.let { parseCount(it) }
    val disliked = primary.findFirst("dislikeButtonViewModel")
        .findFirst("toggleButtonViewModel")["isToggled"].bool == true
    val likeStatus = when {
        likeToggle["isToggled"].bool == true -> LikeStatus.LIKE
        disliked -> LikeStatus.DISLIKE
        likeToggle != null -> LikeStatus.INDIFFERENT
        else -> null
    }

    val viewCount = primary["viewCount"]["videoViewCountRenderer"]
    val dateText = primary["dateText"].text ?: primary["relativeDateText"].text
    val description = secondary["attributedDescription"]["content"].str
        ?: secondary["description"].text
        ?: ""
    val descriptionLinks = parseTextLinks(secondary["attributedDescription"]["commandRuns"])

    val chapters = response["playerOverlays"].findAll("chapterRenderer").map {
        Chapter(
            title = it["title"].text ?: "",
            startSeconds = (it["timeRangeStartMillis"].long ?: 0L) / 1000,
            thumbnails = thumbnails(it["thumbnail"]),
        )
    }.ifEmpty {
        response["engagementPanels"].findAll("macroMarkersListItemRenderer").mapNotNull {
            val start = durationSeconds(it["timeDescription"].text) ?: return@mapNotNull null
            Chapter(
                title = it["title"].text ?: "",
                startSeconds = start,
                thumbnails = thumbnails(it["thumbnail"]),
            )
        }
    }

    val commentsPanel = response["engagementPanels"].list.firstOrNull {
        it["engagementPanelSectionListRenderer"]["panelIdentifier"].str == "engagement-panel-comments-section"
    }["engagementPanelSectionListRenderer"]
    val commentsContinuation = commentsPanel["content"]?.let { continuationToken(it) }
        ?: results.firstOrNull {
            it["itemSectionRenderer"]["sectionIdentifier"].str == "comment-item-section"
        }?.let { continuationToken(it) }
    val commentCountText = commentsPanel["header"]["engagementPanelTitleHeaderRenderer"]["contextualInfo"].text
        ?: results.firstNotNullOfOrNull { section ->
            section["itemSectionRenderer"]["header"]["commentsHeaderRenderer"]["countText"].text
                ?.takeIf { text -> text.any { it.isDigit() } }
        }

    val relatedElements = twoColumn["secondaryResults"]["secondaryResults"]["results"].list
    val related = Page(
        parseItems(relatedElements),
        relatedElements.firstNotNullOfOrNull { continuationToken(it) },
    )

    val categoryRow = secondary["metadataRowContainer"].findAll("metadataRowRenderer")
        .firstOrNull { it["title"].text == "Category" }

    val playlistPanel = twoColumn["playlist"]["playlist"]?.let { pl ->
        PlaylistPanel(
            playlistId = pl["playlistId"].str ?: "",
            title = pl["title"].str ?: pl["titleText"].text,
            currentIndex = pl["currentIndex"].int ?: pl["localCurrentIndex"].int ?: 0,
            videos = pl["contents"].list.mapNotNull { parseItem(it) as? Video },
            continuation = continuationToken(pl["contents"])
                ?: pl["continuations"].findFirst("continuation").str,
        )
    }

    val inWatchLater = secondary.findAll("playlistEditEndpoint")
        .firstOrNull { it["playlistId"].str == "WL" }
        ?.let { it["actions"][0]["action"].str == "ACTION_REMOVE_VIDEO_BY_VIDEO_ID" }

    return VideoDetails(
        id = videoId,
        title = primary["title"].text ?: "",
        description = description,
        channel = channel,
        thumbnails = emptyList(),
        viewCountText = viewCount["viewCount"].text ?: viewCount["shortViewCount"].text,
        likeCountText = likeCountText,
        likeCount = likeCount,
        publishedText = dateText,
        lengthSeconds = null,
        isLive = viewCount["isLive"].bool == true,
        isUpcoming = primary["upcomingEventData"] != null ||
            dateText?.startsWith("Premieres") == true ||
            dateText?.startsWith("Scheduled") == true,
        keywords = emptyList(),
        category = categoryRow["contents"][0].text,
        chapters = chapters,
        likeStatus = likeStatus,
        subscribed = secondary["subscribeButton"]["subscribeButtonRenderer"]["subscribed"].bool
            ?: subscribedState(response, secondary.findFirst("subscribeButtonViewModel")),
        commentCountText = commentCountText,
        commentsContinuation = commentsContinuation,
        related = related,
        playlistPanel = playlistPanel,
        inWatchLater = inWatchLater,
        descriptionLinks = descriptionLinks,
        collaborators = collaborators,
    )
}

internal fun parseTextLinks(runs: JsonElement?): List<TextLink> = runs.list.mapNotNull { run ->
    val start = run["startIndex"].int ?: return@mapNotNull null
    val length = run["length"].int ?: return@mapNotNull null
    val command = run["onTap"]["innertubeCommand"]
    val videoId = command["watchEndpoint"]["videoId"].str
    val browseId = command["browseEndpoint"]["browseId"].str
    val url = command["urlEndpoint"]["url"].str
    val target: LinkTarget = when {
        videoId != null -> LinkTarget.Video(videoId, command["watchEndpoint"]["startTimeSeconds"].long)
        browseId != null && browseId.startsWith("UC") -> LinkTarget.Channel(browseId)
        browseId != null -> {
            val path = command["browseEndpoint"]["canonicalBaseUrl"].str ?: return@mapNotNull null
            LinkTarget.Url("https://www.youtube.com$path")
        }
        url != null -> LinkTarget.Url(unwrapRedirect(url))
        else -> return@mapNotNull null
    }
    TextLink(start, start + length, target)
}

internal fun unwrapRedirect(url: String): String {
    if (!url.contains("youtube.com/redirect")) return url
    val target = url.substringAfter('?', "").split('&')
        .firstOrNull { it.startsWith("q=") }
        ?.substring(2)
        ?: return url
    return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(url)
}

internal fun parseComments(response: JsonElement): Page<Comment> {
    val entities = HashMap<String, JsonElement>()
    for (mutation in response["frameworkUpdates"]["entityBatchUpdate"]["mutations"].list) {
        val key = mutation["entityKey"].str ?: continue
        val payload = mutation["payload"] ?: continue
        entities[key] = payload
    }

    val comments = ArrayList<Comment>()
    var continuation: String? = null
    for (item in receivedItems(response)) {
        when (val name = item.rendererName) {
            "commentThreadRenderer" -> {
                val thread = item[name]
                val vm = thread["commentViewModel"]["commentViewModel"]
                val replies = thread["replies"]["commentRepliesRenderer"]
                    ?.let { continuationToken(it["contents"]) }
                val comment = vm?.let { commentFromEntities(it, entities) }
                    ?: thread["comment"]["commentRenderer"]?.let { legacyComment(it) }
                    ?: continue
                comments += comment.copy(repliesContinuation = replies ?: comment.repliesContinuation)
            }
            "commentViewModel" -> {
                val vm = item[name]["commentViewModel"] ?: item[name] ?: continue
                commentFromEntities(vm, entities)?.let { comments += it }
            }
            "commentRenderer" -> legacyComment(item[name])?.let { comments += it }
            "continuationItemRenderer" -> continuation = continuationToken(item)
        }
    }
    return Page(comments, continuation)
}

private fun commentFromEntities(vm: JsonElement, entities: Map<String, JsonElement>): Comment? {
    val key = vm["commentKey"].str ?: return null
    val payload = entities[key]["commentEntityPayload"] ?: return null
    val properties = payload["properties"]
    val author = payload["author"]
    val toolbar = payload["toolbar"]
    val toolbarState = entities[vm["toolbarStateKey"].str ?: properties["toolbarStateKey"].str ?: ""]["engagementToolbarStateEntityPayload"]
    val authorName = author["displayName"].str ?: ""
    return Comment(
        id = properties["commentId"].str ?: vm["commentId"].str ?: return null,
        author = ChannelRef(
            id = author["channelId"].str,
            name = authorName,
            avatar = author["avatarThumbnailUrl"].str?.let { listOf(Thumbnail(it)) } ?: emptyList(),
            verified = author["isVerified"].bool == true,
            handle = authorName.takeIf { it.startsWith("@") },
        ),
        text = properties["content"]["content"].str ?: "",
        likeCountText = toolbar["likeCountNotliked"].str?.takeIf { it.isNotBlank() }
            ?: toolbar["likeCountLiked"].str,
        publishedText = properties["publishedTime"].str,
        replyCount = parseCount(toolbar["replyCount"].str)?.toInt() ?: 0,
        repliesContinuation = null,
        pinned = properties["pinnedText"] != null || payload["pinnedText"] != null,
        hearted = toolbarState["heartState"].str == "TOOLBAR_HEART_STATE_HEARTED",
        byOwner = author["isCreator"].bool == true,
    )
}

private fun legacyComment(r: JsonElement?): Comment? {
    val id = r["commentId"].str ?: return null
    val authorName = r["authorText"].text ?: ""
    return Comment(
        id = id,
        author = ChannelRef(
            id = browseIdOf(r["authorEndpoint"]),
            name = authorName,
            avatar = thumbnails(r["authorThumbnail"]),
            verified = r["authorCommentBadge"] != null,
            handle = authorName.takeIf { it.startsWith("@") },
        ),
        text = r["contentText"].text ?: "",
        likeCountText = r["voteCount"].text,
        publishedText = r["publishedTimeText"].text,
        replyCount = r["replyCount"].int ?: 0,
        repliesContinuation = null,
        pinned = r["pinnedCommentBadge"] != null,
        hearted = r["creatorHeart"] != null,
        byOwner = r["authorIsChannelOwner"].bool == true,
    )
}

internal fun parsePlayer(response: JsonElement, videoId: String, clientName: String): PlayerResponse {
    val playability = response["playabilityStatus"]
    val streaming = response["streamingData"]
    val details = response["videoDetails"]
    val tracking = response["playbackTracking"]?.let {
        PlaybackTracking(
            videostatsPlaybackUrl = it["videostatsPlaybackUrl"]["baseUrl"].str,
            videostatsWatchtimeUrl = it["videostatsWatchtimeUrl"]["baseUrl"].str,
            ptrackingUrl = it["ptrackingUrl"]["baseUrl"].str,
            qoeUrl = it["qoeUrl"]["baseUrl"].str,
            atrUrl = it["atrUrl"]["baseUrl"].str,
        )
    }
    val captions = response["captions"]["playerCaptionsTracklistRenderer"]["captionTracks"].list
        .mapNotNull { track ->
            val baseUrl = track["baseUrl"].str
                ?.let { if (it.startsWith("/")) "https://www.youtube.com$it" else it }
                ?: return@mapNotNull null
            CaptionTrack(
                baseUrl = baseUrl,
                languageCode = track["languageCode"].str ?: "",
                name = track["name"].text ?: track["languageCode"].str ?: "",
                kind = track["kind"].str,
                isTranslatable = track["isTranslatable"].bool == true,
                vssId = track["vssId"].str,
            )
        }
    return PlayerResponse(
        videoId = details["videoId"].str ?: videoId,
        playability = Playability(
            status = playability["status"].str ?: "UNKNOWN",
            reason = playability["reason"].str ?: playability["errorScreen"].findFirst("reason").text,
        ),
        formats = streaming["formats"].list.mapNotNull { parseFormat(it) },
        adaptiveFormats = streaming["adaptiveFormats"].list.mapNotNull { parseFormat(it) },
        hlsManifestUrl = streaming["hlsManifestUrl"].str,
        dashManifestUrl = streaming["dashManifestUrl"].str,
        captions = captions,
        storyboardSpec = response["storyboards"]["playerStoryboardSpecRenderer"]["spec"].str
            ?: response["storyboards"]["playerLiveStoryboardSpecRenderer"]["spec"].str,
        lengthSeconds = details["lengthSeconds"].long,
        isLive = details["isLive"].bool == true ||
            (details["isLiveContent"].bool == true && streaming["hlsManifestUrl"] != null),
        title = details["title"].str,
        channelId = details["channelId"].str,
        author = details["author"].str,
        keywords = details["keywords"].list.mapNotNull { it.str },
        shortDescription = details["shortDescription"].str,
        thumbnails = thumbnails(details["thumbnail"]),
        playbackTracking = tracking,
        expiresInSeconds = streaming["expiresInSeconds"].long,
        clientUsed = clientName,
        serverAbrStreamingUrl = streaming["serverAbrStreamingUrl"].str,
        startSeconds = response["playerConfig"]["playbackStartConfig"]["startSeconds"].long,
    )
}

private fun parseFormat(f: JsonElement): StreamFormat? {
    val itag = f["itag"].int ?: return null
    val mimeType = f["mimeType"].str ?: return null

    fun range(e: JsonElement?): ByteRange? {
        if (e == null) return null
        val start = e["start"].long ?: return null
        val end = e["end"].long ?: return null
        return ByteRange(start, end)
    }

    val audioTrack = f["audioTrack"]?.let {
        AudioTrack(
            id = it["id"].str,
            displayName = it["displayName"].str,
            languageCode = it["id"].str?.substringBefore('.'),
            isDefault = it["audioIsDefault"].bool == true,
        )
    }
    return StreamFormat(
        itag = itag,
        url = f["url"].str,
        signatureCipher = f["signatureCipher"].str ?: f["cipher"].str,
        mimeType = mimeType,
        bitrate = f["bitrate"].long,
        averageBitrate = f["averageBitrate"].long,
        width = f["width"].int,
        height = f["height"].int,
        fps = f["fps"].int,
        qualityLabel = f["qualityLabel"].str,
        quality = f["quality"].str,
        audioQuality = f["audioQuality"].str,
        audioSampleRate = f["audioSampleRate"].int,
        audioChannels = f["audioChannels"].int,
        contentLength = f["contentLength"].long,
        approxDurationMs = f["approxDurationMs"].long,
        audioTrack = audioTrack,
        initRange = range(f["initRange"]),
        indexRange = range(f["indexRange"]),
        isDrc = f["isDrc"].bool == true,
        xtags = f["xtags"].str,
    )
}

internal fun parseGuideSubscriptions(response: JsonElement?): List<Subscription> {
    val out = ArrayList<Subscription>()

    fun entry(e: JsonElement?) {
        val r = e["guideEntryRenderer"] ?: return
        val id = browseIdOf(r["navigationEndpoint"])?.takeIf { it.startsWith("UC") } ?: return
        out += Subscription(
            channel = ChannelRef(
                id = id,
                name = r["formattedTitle"].text ?: "",
                avatar = thumbnails(r["thumbnail"]),
                handle = handleOf(r["navigationEndpoint"]),
            ),
            hasNewContent = r["presentationStyle"].str == "GUIDE_ENTRY_PRESENTATION_STYLE_NEW_CONTENT",
        )
    }

    for (section in response["items"].list) {
        val subscriptions = section["guideSubscriptionsSectionRenderer"] ?: continue
        for (item in subscriptions["items"].list) {
            entry(item)
            item["guideCollapsibleEntryRenderer"]["expandableItems"].list.forEach { entry(it) }
        }
    }
    return out.distinctBy { it.channel.id }
}

private val AUTH_USER_PARAM = Regex("[?&]authuser=(\\d+)")

internal fun parseAccountIdentities(response: JsonElement?): List<AccountIdentity> {
    val sections = response.findAll("accountSectionListRenderer").ifEmpty { listOfNotNull(response) }
    val out = ArrayList<AccountIdentity>()
    for (section in sections) {
        val email = section["header"].findFirst("googleAccountHeaderRenderer")["email"].text
        for (item in section.findAll("accountItem")) {
            val name = item["accountName"].text ?: continue
            val tokens = item["serviceEndpoint"]["selectActiveIdentityEndpoint"]["supportedTokens"].list
            val pageId = item["onBehalfOfParameter"].str
                ?: tokens.firstNotNullOfOrNull { it["pageIdToken"]["pageId"].str }
            val signinUrl = tokens.firstNotNullOfOrNull { it["accountSigninToken"]["signinUrl"].str }
            val byline = item["accountByline"].text
            out += AccountIdentity(
                name = name,
                email = email ?: byline?.takeIf { it.contains('@') && !it.startsWith("@") },
                handle = item["channelHandle"].text,
                avatar = thumbnails(item["accountPhoto"]),
                pageId = pageId,
                authUser = signinUrl
                    ?.let { AUTH_USER_PARAM.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: 0,
                selected = item["isSelected"].bool == true,
            )
        }
    }
    return out.distinctBy { it.pageId to it.authUser }
}

internal fun parseAccountInfo(response: JsonElement?): AccountInfo? {
    val header = response.findFirst("activeAccountHeaderRenderer") ?: return null
    val channelId = response.findFirst("multiPageMenuRenderer").findAll("browseEndpoint")
        .firstNotNullOfOrNull { e -> e["browseId"].str?.takeIf { it.startsWith("UC") } }
    return AccountInfo(
        name = header["accountName"].text ?: "",
        email = header["email"].text,
        avatar = thumbnails(header["accountPhoto"]),
        channelId = channelId,
        channelHandle = header["channelHandle"].text,
    )
}
