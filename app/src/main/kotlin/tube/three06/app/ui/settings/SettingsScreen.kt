package tube.three06.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tube.three06.app.data.ResumeMode
import tube.three06.app.data.RoomScreenStore
import tube.three06.app.data.Settings
import tube.three06.app.data.SponsorBlockCategories
import tube.three06.app.data.StreamResolverChoice
import tube.three06.app.data.ThemeMode
import tube.three06.app.data.UpdateState
import tube.three06.app.data.ytdlp.YtDlp
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalIsTv
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.login.AccountsSheet

private val qualityOptions = listOf(
    0 to "Auto",
    2160 to "2160p",
    1440 to "1440p",
    1080 to "1080p",
    720 to "720p",
    480 to "480p",
    360 to "360p",
    240 to "240p",
    144 to "144p",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val isTv = LocalIsTv.current
    val graph = LocalGraph.current
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val signedIn by graph.session.credentials.collectAsStateWithLifecycle()
    val screens by graph.screenStore.observe().collectAsStateWithLifecycle(emptyList())
    var accountsSheet by remember { mutableStateOf(false) }
    var sbCategories by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }

    fun update(transform: (Settings) -> Settings) = scope.launch { graph.settings.update(transform) }

    if (accountsSheet) AccountsSheet(onDismiss = { accountsSheet = false })

    if (sbCategories) {
        AlertDialog(
            onDismissRequest = { sbCategories = false },
            title = { Text("SponsorBlock categories") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SponsorBlockCategories.ALL.forEach { (key, label) ->
                        val checked = key in settings.sponsorBlockCategories
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    update {
                                        it.copy(
                                            sponsorBlockCategories =
                                                if (checked) it.sponsorBlockCategories - key else it.sponsorBlockCategories + key,
                                        )
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sbCategories = false }) { Text("Done") } },
        )
    }

    if (licenses) {
        AlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text("Licenses") },
            text = {
                Text(
                    "306Tube is free software, licensed under the GNU General Public License v3.0. " +
                        "It includes code ported from NewPipe and depends on NewPipeExtractor (both GPL-3.0), " +
                        "and uses Jetpack Compose, Media3, Room, OkHttp, Coil and kotlinx libraries (Apache-2.0). " +
                        "Downloads run through yt-dlp (Unlicense) via youtubedl-android (GPL-3.0), " +
                        "which bundles Python (PSF license). " +
                        "Stream URL challenges are solved with yt-dlp's EJS solver (Unlicense), " +
                        "built on meriyah (ISC) and astring (MIT). " +
                        "SponsorBlock, DeArrow and Return YouTube Dislike data come from their community APIs " +
                        "under their own terms.\n\n" +
                        "This program comes with ABSOLUTELY NO WARRANTY. You may redistribute it under the terms " +
                        "of the GPL; see https://www.gnu.org/licenses/gpl-3.0.html.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SettingsHeader("Appearance") }
            item {
                ChoiceRow("Theme", settings.theme, ThemeMode.entries, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) {
                    update { s -> s.copy(theme = it) }
                }
                SwitchRow("Dynamic color", "Use wallpaper colors on Android 12+", settings.dynamicColor) { update { s -> s.copy(dynamicColor = it) } }
                SwitchRow("Home tab", "Off hides the Home feed and starts the app on Subscriptions", settings.showHome) { update { s -> s.copy(showHome = it) } }
                SwitchRow("Hide Shorts", "Remove Shorts from Home, Subscriptions, search and related videos", settings.hideShorts) { update { s -> s.copy(hideShorts = it) } }
                SwitchRow("Hide watched videos", "Remove videos watched past 80% from Home and related videos", settings.hideWatched) { update { s -> s.copy(hideWatched = it) } }
                SwitchRow("Hide Mixes", "Remove auto-generated Mix playlists from Home and related videos", settings.hideMixes) { update { s -> s.copy(hideMixes = it) } }
            }

            item { SettingsHeader("Playback") }
            item {
                ChoiceRow("Default quality on Wi-Fi", settings.qualityWifi, qualityOptions.map { it.first }, { q -> qualityOptions.first { it.first == q }.second }) {
                    update { s -> s.copy(qualityWifi = it) }
                }
                ChoiceRow("Default quality on mobile data", settings.qualityMobile, qualityOptions.map { it.first }, { q -> qualityOptions.first { it.first == q }.second }) {
                    update { s -> s.copy(qualityMobile = it) }
                }
                SwitchRow("Autoplay", "Play a related video when the queue ends", settings.autoplay) {
                    update { s -> s.copy(autoplay = it) }
                    graph.player.setAutoplay(it)
                }
                ChoiceRow(
                    "Autoplay countdown",
                    settings.autoplayCountdownSeconds,
                    listOf(0, 3, 5, 8, 10, 15, 30),
                    { if (it == 0) "Start the next video immediately" else "$it seconds" },
                ) {
                    update { s -> s.copy(autoplayCountdownSeconds = it) }
                    graph.player.setAutoplayCountdown(it * 1000L)
                }
                SwitchRow("Picture-in-picture", "Shrink the video when you leave the app", settings.pictureInPicture) { update { s -> s.copy(pictureInPicture = it) } }
                SwitchRow("Background play", "Keep playing audio when the app is hidden", settings.backgroundPlay) { update { s -> s.copy(backgroundPlay = it) } }
                ChoiceRow(
                    "Resume videos",
                    settings.resume,
                    ResumeMode.entries,
                    {
                        when (it) {
                            ResumeMode.ALWAYS -> "Always resume"
                            ResumeMode.ASK -> "Ask"
                            ResumeMode.NEVER -> "Start from the beginning"
                        }
                    },
                ) { update { s -> s.copy(resume = it) } }
                ChoiceRow(
                    "Stream source",
                    settings.resolver,
                    StreamResolverChoice.entries,
                    {
                        when (it) {
                            StreamResolverChoice.YTDLP -> "yt-dlp"
                            StreamResolverChoice.EXTRACTOR -> "NewPipe extractor (default)"
                        }
                    },
                ) { update { s -> s.copy(resolver = it) } }
            }

            if (!isTv) {
                item { SettingsHeader("Player gestures") }
                item {
                    SwitchRow("Swipe for brightness", "Drag up or down on the left half; drag to the bottom for automatic", settings.swipeBrightness) { update { s -> s.copy(swipeBrightness = it) } }
                    SwitchRow("Swipe for volume", "Drag up or down on the right half", settings.swipeVolume) { update { s -> s.copy(swipeVolume = it) } }
                    SwitchRow("Swipe to seek", "Drag left or right; a full-width drag moves 90 seconds", settings.swipeSeek) { update { s -> s.copy(swipeSeek = it) } }
                    SwitchRow("Also in the portrait player", "Off limits gestures to fullscreen, so dragging the video down still minimizes it", settings.swipeInPortrait) { update { s -> s.copy(swipeInPortrait = it) } }
                    SwitchRow("Haptic feedback", "Vibrate when a swipe starts", settings.swipeHaptics) { update { s -> s.copy(swipeHaptics = it) } }
                }
            }

            item { SettingsHeader("Downloads") }
            item {
                SwitchRow("Download over Wi-Fi only", "Queued downloads wait for an unmetered connection", settings.downloadsWifiOnly) { update { s -> s.copy(downloadsWifiOnly = it) } }
                YtDlpRow()
            }

            item { SettingsHeader("Privacy") }
            item {
                SwitchRow(
                    "On-device recommendations",
                    "When signed out, build Home from your local history and subscriptions. Off shows Trending.",
                    settings.recommenderEnabled,
                ) { update { s -> s.copy(recommenderEnabled = it) } }
                SwitchRow("SponsorBlock", "Skip sponsored and other crowd-marked segments", settings.sponsorBlock) { update { s -> s.copy(sponsorBlock = it) } }
                ListItem(
                    headlineContent = { Text("SponsorBlock categories") },
                    supportingContent = { Text("${settings.sponsorBlockCategories.size} of ${SponsorBlockCategories.ALL.size} enabled") },
                    modifier = Modifier.clickable { sbCategories = true },
                )
                SwitchRow(
                    "DeArrow",
                    "Replace clickbait titles and thumbnails with community ones; a swap icon marks replaced titles",
                    settings.deArrow,
                ) { update { s -> s.copy(deArrow = it) } }
                SwitchRow(
                    "Random frame thumbnails",
                    "When DeArrow has no thumbnail, show a frame from the video instead of the uploader's",
                    settings.deArrowRandomThumbnail,
                    enabled = settings.deArrow,
                ) { update { s -> s.copy(deArrowRandomThumbnail = it) } }
                SwitchRow("Return YouTube Dislike", "Show dislike counts from returnyoutubedislike.com", settings.returnYouTubeDislike) { update { s -> s.copy(returnYouTubeDislike = it) } }
                SwitchRow(
                    "Sync watch history to YouTube",
                    "When signed in, report playback so YouTube's history and resume points update",
                    settings.syncHistory,
                    enabled = signedIn.signedIn,
                ) { update { s -> s.copy(syncHistory = it) } }
            }

            item { SettingsHeader("Accounts") }
            item {
                val active by graph.accounts.active.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text(active?.name ?: "Not signed in") },
                    supportingContent = {
                        Text(
                            active?.email
                                ?: "Tap to sign in or manage accounts. Sign-in cookies are encrypted with a key in the Android Keystore.",
                        )
                    },
                    modifier = Modifier.clickable { accountsSheet = true },
                )
            }

            item { SettingsHeader("Cast") }
            val receiver = graph.castReceiver
            if (receiver != null) {
                item {
                    val linked by receiver.connected.collectAsStateWithLifecycle()
                    var code by remember { mutableStateOf<String?>(null) }
                    ListItem(
                        headlineContent = { Text("Cast to this TV") },
                        supportingContent = {
                            Text(
                                if (linked) {
                                    "Shows up as \"${receiver.name}\" on phones on this network" +
                                        if (settings.castInBackground) "." else " while 306Tube is open."
                                } else {
                                    "Not connected to YouTube's cast service. Check the network."
                                },
                            )
                        },
                    )
                    ListItem(
                        headlineContent = { Text(code?.chunked(3)?.joinToString(" ") ?: "Link with TV code") },
                        supportingContent = {
                            Text(
                                if (code == null) {
                                    "For phones on another network, or if this TV isn't listed"
                                } else {
                                    "Enter this under Link with TV code in YouTube or 306Tube on your phone"
                                },
                            )
                        },
                        modifier = Modifier.clickable {
                            scope.launch {
                                runCatching { receiver.pairingCode() }
                                    .onSuccess { code = it }
                                    .onFailure { messages("Couldn't get a code: ${it.describe()}") }
                            }
                        },
                    )
                    // Re-read on resume: the permission is granted on a system screen, not here.
                    var canOpen by remember { mutableStateOf(receiver.canOpenOverOtherApps()) }
                    LifecycleResumeEffect(Unit) {
                        canOpen = receiver.canOpenOverOtherApps()
                        onPauseOrDispose {}
                    }
                    SwitchRow(
                        "Stay available in the background",
                        "Keep this TV in the cast list when 306Tube is closed, starting when the TV boots",
                        settings.castInBackground,
                    ) { enabled ->
                        scope.launch { receiver.setBackground(enabled) }
                        if (enabled && !canOpen && !openOverlaySettings(context)) {
                            messages("This TV has no screen for that permission. See the note below.")
                        }
                    }
                    if (settings.castInBackground && !canOpen) {
                        ListItem(
                            headlineContent = { Text("Allow display over other apps") },
                            supportingContent = {
                                Text(
                                    "Needed so a cast can open the player while another app is on screen. " +
                                        "If no settings screen opens, grant it from a computer: " +
                                        "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
                                )
                            },
                            modifier = Modifier.clickable {
                                if (!openOverlaySettings(context)) messages("This TV has no screen for that permission")
                            },
                        )
                    }
                }
            } else if (screens.isEmpty()) {
                item {
                    Text(
                        "No paired screens. Use the cast button to discover a TV or enter a TV code.",
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (receiver == null) items(screens.size) { i ->
                val s = screens[i]
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.lastAddress ?: s.screenId) },
                    trailingContent = {
                        IconButton(onClick = {
                            scope.launch { graph.cast.forget(with(RoomScreenStore) { s.toScreen() }) }
                        }) { Icon(Icons.Default.Delete, "Forget") }
                    },
                )
            }

            item { SettingsHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("306Tube") },
                    supportingContent = { Text("Version ${versionName(graph.context)} · GPL-3.0") },
                )
                val updateState by graph.updater.state.collectAsStateWithLifecycle()
                ListItem(
                    headlineContent = { Text("Check for updates") },
                    supportingContent = {
                        Text(
                            when (val u = updateState) {
                                UpdateState.Idle -> "Looks for a newer release on GitHub"
                                UpdateState.Checking -> "Checking…"
                                UpdateState.UpToDate -> "You're on the latest release"
                                is UpdateState.Available -> "${u.update.version} is available"
                                is UpdateState.Downloading -> "Downloading ${(u.fraction * 100).toInt()}%"
                                is UpdateState.Installing -> "Installing…"
                                is UpdateState.Failed -> u.message
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        // With an update already found, the tap reopens its dialog.
                        if (updateState is UpdateState.Available || (updateState as? UpdateState.Failed)?.update != null) {
                            graph.updater.prompt.value = true
                        } else {
                            graph.updater.check(manual = true)
                        }
                    },
                )
                SwitchRow(
                    "Check automatically",
                    "Asks GitHub for the latest release once a day when the app opens",
                    settings.checkForUpdates,
                ) { update { s -> s.copy(checkForUpdates = it) } }
                ListItem(headlineContent = { Text("Licenses") }, modifier = Modifier.clickable { licenses = true })
            }
        }
    }
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

@Composable
private fun YtDlpRow() {
    val context = LocalContext.current
    val graph = LocalGraph.current
    val message = LocalMessages.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        version = runCatching { YtDlp.version(context) }.getOrNull()
    }

    ListItem(
        headlineContent = { Text("yt-dlp") },
        supportingContent = {
            Text(
                if (busy) "Updating…" else version?.let { "Version $it · tap to check for updates" } ?: "Preparing…",
            )
        },
        modifier = Modifier.clickable(enabled = !busy) {
            busy = true
            scope.launch {
                runCatching { YtDlp.update(context) }
                    .onSuccess { updated ->
                        message(if (updated != null) "yt-dlp updated to $updated" else "yt-dlp is up to date")
                        if (updated != null) version = updated
                        graph.settings.update { it.copy(ytDlpSetupDone = true) }
                    }
                    .onFailure { message("Update failed: ${it.message}") }
                busy = false
            }
        },
    )
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: T,
    options: List<T>,
    label: (T) -> String,
    onChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(value)) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onChange(option); open = false }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(option == value, onClick = null)
                            Text(label(option), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Opens the system screen for "display over other apps". False if this device has none. */
private fun openOverlaySettings(context: Context): Boolean {
    val pkg = Uri.parse("package:${context.packageName}")
    // Many Android TV builds leave out the overlay screen; the app's details page is the next best.
    val intents = listOf(
        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
        Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
    )
    return intents.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
