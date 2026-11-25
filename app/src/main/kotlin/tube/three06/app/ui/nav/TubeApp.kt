package tube.three06.app.ui.nav

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tube.three06.app.MainActivity
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.cast.CastChoiceSheet
import tube.three06.app.ui.cast.CastSheet
import tube.three06.app.ui.cast.CastViewModel
import tube.three06.app.ui.channel.ChannelScreen
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalIsTv
import tube.three06.app.ui.common.LocalWide
import tube.three06.app.ui.common.TvIndication
import tube.three06.app.ui.common.UpdateDialog
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.LocalPlay
import tube.three06.app.ui.common.LocalVideoMenu
import tube.three06.app.ui.common.Navigator
import tube.three06.app.ui.common.PlayAction
import tube.three06.app.ui.common.VideoMenuSheet
import tube.three06.app.ui.common.describe
import tube.three06.app.ui.home.HomeScreen
import tube.three06.app.ui.library.DownloadsScreen
import tube.three06.app.ui.library.HistoryScreen
import tube.three06.app.ui.library.LibraryScreen
import tube.three06.app.ui.library.LocalPlaylistScreen
import tube.three06.app.ui.login.AccountsSheet
import tube.three06.app.ui.login.LoginScreen
import tube.three06.app.ui.playlist.PlaylistScreen
import tube.three06.app.ui.search.SearchScreen
import tube.three06.app.ui.settings.SettingsScreen
import tube.three06.app.ui.subscriptions.SubscriptionsScreen
import tube.three06.app.ui.theme.TubeTheme
import tube.three06.app.ui.watch.MiniPlayerHeight
import tube.three06.app.ui.watch.PlayerLauncher
import tube.three06.app.ui.watch.PlayerOverlay
import tube.three06.app.ui.watch.PlayerOverlayState
import tube.three06.app.ui.watch.PlayerSurface
import tube.three06.app.ui.watch.WatchViewModel
import tube.three06.innertube.model.ChannelRef
import tube.three06.innertube.model.Endpoint
import tube.three06.innertube.model.Video
import tube.three06.recommend.WatchEvent

@Composable
fun TubeApp(
    graph: AppGraph,
    pendingIntent: StateFlow<Intent?>,
    onConsumeIntent: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    TubeTheme(settings.theme, settings.dynamicColor) {
        if (graph.isTv) {
            CompositionLocalProvider(
                LocalGraph provides graph,
                LocalIsTv provides true,
                LocalIndication provides TvIndication,
            ) {
                Shell(graph, pendingIntent, onConsumeIntent, onEnterPip)
            }
        } else {
            // The smallest width keeps a phone in landscape on the phone layout.
            val wide = LocalConfiguration.current.smallestScreenWidthDp >= 600
            CompositionLocalProvider(LocalGraph provides graph, LocalWide provides wide) {
                Shell(graph, pendingIntent, onConsumeIntent, onEnterPip)
            }
        }
    }
}

@Composable
private fun Shell(
    graph: AppGraph,
    pendingIntent: StateFlow<Intent?>,
    onConsumeIntent: () -> Unit,
    onEnterPip: () -> Unit,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val launcher = remember { PlayerLauncher(graph, graph.scope) }
    val watchVm: WatchViewModel = viewModel { WatchViewModel(graph) }
    val castVm: CastViewModel = viewModel { CastViewModel(graph) }

    var menuVideo by remember { mutableStateOf<Video?>(null) }
    var accountsSheet by remember { mutableStateOf(false) }
    var castSheet by remember { mutableStateOf(false) }
    var castChoice by remember { mutableStateOf<CastChoice?>(null) }
    // While a TV is connected, picking a video asks where it should go.
    val play = remember(launcher, castVm) {
        object : PlayAction {
            override fun play(video: Video, queue: List<Video>, source: WatchEvent.Source) {
                if (castVm.connected.value) {
                    castChoice = CastChoice(video, queue, source)
                } else {
                    launcher.play(video, queue, source)
                }
            }
        }
    }
    val castConnected by castVm.connected.collectAsStateWithLifecycle()
    val inPip by graph.overlay.inPip.collectAsStateWithLifecycle()
    val fullscreen by graph.overlay.fullscreen.collectAsStateWithLifecycle()
    val playerState by graph.player.state.collectAsStateWithLifecycle()

    val message: (String) -> Unit = remember {
        { text -> scope.launch { snackbar.showSnackbar(text) } }
    }

    val navigator = remember(navController) {
        object : Navigator {
            override fun openChannel(idOrHandle: String) = navController.navigate(Routes.channel(idOrHandle))
            override fun openPlaylist(playlistId: String) = navController.navigate(Routes.playlist(playlistId))
            override fun openLocalPlaylist(id: Long) = navController.navigate(Routes.localPlaylist(id))
            override fun openSearch(query: String?) = navController.navigate(Routes.search(query))
            override fun openLogin() = navController.navigate(Routes.LOGIN)
            override fun openSettings() = navController.navigate(Routes.SETTINGS)
            override fun openHistory() = navController.navigate(Routes.HISTORY)
            override fun openDownloads() = navController.navigate(Routes.DOWNLOADS)
            override fun back() {
                navController.popBackStack()
            }
        }
    }

    val shell = remember(castConnected) {
        object : ShellActions {
            override fun openAccounts() {
                accountsSheet = true
            }

            override fun openCast() {
                castSheet = true
            }

            override val casting: Boolean get() = castConnected
        }
    }

    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { graph.overlay.collapse() }
    }
    LaunchedEffect(Unit) { graph.playbackObserver.messages.collect { message(it) } }
    LaunchedEffect(Unit) { castVm.error.collect { if (it != null) message(it) } }
    LaunchedEffect(castConnected) { if (castConnected) graph.player.pause() }

    DeepLinkHandler(graph, pendingIntent, onConsumeIntent, navigator, launcher, message)
    FullscreenEffect(graph.overlay)

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalPlay provides play,
        LocalVideoMenu provides { menuVideo = it },
        LocalMessages provides message,
        LocalShell provides shell,
    ) {
        if (inPip) {
            Box(Modifier.fillMaxSize()) { PlayerSurface(Modifier.fillMaxSize()) }
            return@CompositionLocalProvider
        }

        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showBars = route != Routes.LOGIN && !fullscreen
        val settings by graph.settings.state.collectAsStateWithLifecycle()
        val showHome = settings.showHome
        val tabs = if (showHome) Tab.entries else Tab.entries.filter { it != Tab.Home }

        LaunchedEffect(showHome) {
            if (showHome) return@LaunchedEffect
            if (runCatching { navController.getBackStackEntry(Routes.HOME) }.isFailure) return@LaunchedEffect
            val current = navController.currentBackStackEntry?.destination?.route
            navController.navigate(Routes.SUBSCRIPTIONS) {
                popUpTo(Routes.HOME) { inclusive = true }
            }
            if (current != null && current != Routes.HOME) navController.navigate(current)
        }

        var navBarHeight by remember { mutableIntStateOf(0) }
        val hasMedia = playerState.current != null

        val onTab: (Tab) -> Unit = { tab ->
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val isSelected: (Tab) -> Boolean = { tab ->
            route?.substringBefore('?') == tab.route || (tab == Tab.Search && route?.startsWith("search") == true)
        }
        val isTv = graph.isTv

        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // The rail itself is drawn on top so opening it doesn't reflow the feed.
                if (isTv && showBars) Spacer(Modifier.width(TvRailCollapsedWidth))
                Scaffold(
                    modifier = Modifier.weight(1f),
                    bottomBar = {
                        if (showBars && !isTv) {
                            NavigationBar(
                                modifier = Modifier
                                    .onSizeChanged { navBarHeight = it.height }
                                    .offset { IntOffset(0, (navBarHeight * graph.overlay.progress.floatValue).roundToInt()) },
                            ) {
                                tabs.forEach { tab ->
                                    val selected = isSelected(tab)
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { onTab(tab) },
                                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    val start = remember { if (graph.settings.current.showHome) Routes.HOME else Routes.SUBSCRIPTIONS }
                    AppNavHost(
                        navController,
                        start,
                        Modifier
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .padding(bottom = if (!hasMedia || fullscreen) 0.dp else MiniPlayerHeight),
                    )
                }
            }

            if (isTv && showBars) TvRail(tabs, isSelected, onTab)
            PlayerOverlay(watchVm, if (showBars && !isTv) navBarHeight else 0, onEnterPip)
        }

        menuVideo?.let { video ->
            val addToTv: (() -> Unit)? = if (castConnected) {
                {
                    castVm.addToQueue(video)
                    message("Added to TV queue")
                }
            } else {
                null
            }
            VideoMenuSheet(video, addToTv) { menuVideo = null }
        }
        castChoice?.let { choice ->
            CastChoiceSheet(
                choice.video,
                onTv = { castVm.playNow(choice.video) },
                onQueue = {
                    castVm.addToQueue(choice.video)
                    message("Added to TV queue")
                },
                onLocal = { launcher.play(choice.video, choice.queue, choice.source) },
                onDismiss = { castChoice = null },
            )
        }
        if (accountsSheet) AccountsSheet { accountsSheet = false }
        if (castSheet) CastSheet(castVm) { castSheet = false }
        UpdateDialog(graph.updater)
    }
}

private val Int.dp: Dp get() = Dp(toFloat())

private class CastChoice(val video: Video, val queue: List<Video>, val source: WatchEvent.Source)

@Composable
private fun AppNavHost(navController: NavHostController, startDestination: String, modifier: Modifier) {
    NavHost(navController, startDestination, modifier) {
        composable(Routes.HOME) { HomeScreen() }
        composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen() }
        composable(
            Routes.SEARCH,
            arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { SearchScreen(it.arguments?.getString("q")) }
        composable(Routes.LIBRARY) { LibraryScreen() }
        composable(Routes.CHANNEL) { ChannelScreen(it.arguments?.getString("id").orEmpty()) }
        composable(Routes.PLAYLIST) { PlaylistScreen(it.arguments?.getString("id").orEmpty()) }
        composable(
            Routes.LOCAL_PLAYLIST,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { LocalPlaylistScreen(it.arguments?.getLong("id") ?: 0L) }
        composable(Routes.HISTORY) { HistoryScreen() }
        composable(Routes.DOWNLOADS) { DownloadsScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}

@Composable
private fun DeepLinkHandler(
    graph: AppGraph,
    pendingIntent: StateFlow<Intent?>,
    onConsume: () -> Unit,
    navigator: Navigator,
    launcher: PlayerLauncher,
    message: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        pendingIntent.collect { intent ->
            if (intent == null) return@collect
            onConsume()
            if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, false)) {
                navigator.openDownloads()
                return@collect
            }
            val url = intent.data?.toString()
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
                ?: return@collect
            runCatching { graph.youTube.resolve(url) }
                .onSuccess { endpoint ->
                    when (endpoint) {
                        is Endpoint.Watch -> {
                            val details = runCatching {
                                graph.youTube.video(endpoint.videoId, endpoint.playlistId, endpoint.index)
                            }.getOrNull()
                            val video = if (details != null) {
                                Video(
                                    id = details.id,
                                    title = details.title,
                                    channel = details.channel,
                                    thumbnails = details.thumbnails,
                                    durationSeconds = details.lengthSeconds,
                                    viewCountText = details.viewCountText,
                                    publishedText = details.publishedText,
                                    isLive = details.isLive,
                                    isUpcoming = details.isUpcoming,
                                )
                            } else {
                                Video(endpoint.videoId, "", ChannelRef(null, ""))
                            }
                            val queue = details?.playlistPanel?.videos?.takeIf { it.isNotEmpty() } ?: listOf(video)
                            launcher.play(video, queue, WatchEvent.Source.EXTERNAL, endpoint.startSeconds?.let { it * 1000 })
                        }
                        is Endpoint.Browse ->
                            if (endpoint.browseId.startsWith("VL")) {
                                navigator.openPlaylist(endpoint.browseId.removePrefix("VL"))
                            } else {
                                navigator.openChannel(endpoint.browseId)
                            }
                        is Endpoint.Search -> navigator.openSearch(endpoint.query)
                        is Endpoint.Url -> message("Can't open this link in 306Tube")
                    }
                }
                .onFailure { message("Couldn't open link: ${it.describe()}") }
        }
    }
}

@Composable
private fun FullscreenEffect(overlay: PlayerOverlayState) {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    val fullscreen by overlay.fullscreen.collectAsStateWithLifecycle()
    val expanded by overlay.expanded.collectAsStateWithLifecycle()
    val lockPortrait by overlay.lockPortrait.collectAsStateWithLifecycle()
    // A TV is always landscape; rotation must not drive fullscreen or orientation locks there.
    val isTv = LocalIsTv.current
    // Nor on a wide window, where landscape is the normal way to browse.
    val wide = LocalWide.current
    val landscape = !isTv && !wide && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(landscape, expanded, fullscreen, lockPortrait) {
        if (landscape && expanded && !fullscreen && !lockPortrait) {
            overlay.enterFullscreen(PlayerOverlayState.FullscreenSource.ROTATION)
        }
        if (!landscape && fullscreen && overlay.fullscreenSource == PlayerOverlayState.FullscreenSource.ROTATION) {
            overlay.fullscreen.value = false
        }
    }

    LaunchedEffect(fullscreen, lockPortrait, wide) {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, view)
        if (wide) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else if (!isTv) {
            activity.requestedOrientation = when {
                fullscreen && overlay.fullscreenSource == PlayerOverlayState.FullscreenSource.BUTTON ->
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                !fullscreen && lockPortrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    // Hold the portrait lock only until the device is physically upright (or upside down) again.
    DisposableEffect(lockPortrait) {
        if (!lockPortrait) return@DisposableEffect onDispose {}
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (orientation <= 30 || orientation >= 330 || orientation in 150..210) {
                    overlay.lockPortrait.value = false
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else overlay.lockPortrait.value = false
        onDispose { listener.disable() }
    }
}
