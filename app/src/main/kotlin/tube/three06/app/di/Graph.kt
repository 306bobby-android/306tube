package tube.three06.app.di

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import tube.three06.app.App
import tube.three06.app.MainActivity
import tube.three06.app.data.AccountRepository
import tube.three06.app.data.DeArrowRepository
import tube.three06.app.data.DownloadRepository
import tube.three06.app.data.HistoryRepository
import tube.three06.app.data.LocalLibraryRepository
import tube.three06.app.data.PlaybackObserver
import tube.three06.app.data.RoomRecommenderStore
import tube.three06.app.data.RoomScreenStore
import tube.three06.app.data.SettingsRepository
import tube.three06.app.data.StreamResolverChoice
import tube.three06.app.data.api.DeArrowApi
import tube.three06.app.data.api.ReturnYouTubeDislikeApi
import tube.three06.app.data.api.SponsorBlockApi
import tube.three06.app.data.db.AppDatabase
import tube.three06.app.data.ytdlp.CompositeStreamResolver
import tube.three06.app.data.ytdlp.YtDlp
import tube.three06.app.data.ytdlp.YtDlpStreamResolver
import tube.three06.app.ui.watch.PlayerOverlayState
import tube.three06.cast.CastManager
import tube.three06.cast.LoungeCastManager
import tube.three06.innertube.ClientKind
import tube.three06.innertube.InnerTubeClient
import tube.three06.innertube.Session
import tube.three06.innertube.YouTube
import tube.three06.player.Playback
import tube.three06.player.PlayerController
import tube.three06.player.PlayerHost
import tube.three06.player.SignedInAccess
import tube.three06.recommend.LocalRecommender
import tube.three06.recommend.Recommender
import java.util.concurrent.TimeUnit

class AppGraph private constructor(val context: Context) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val db: AppDatabase = AppDatabase.build(context)
    val settings = SettingsRepository(context, scope)
    val session = Session()
    val accounts = AccountRepository(db.accounts(), session, settings, scope)
    val history = HistoryRepository(db.watchHistory())
    val library = LocalLibraryRepository(db)
    val recommenderStore = RoomRecommenderStore(db, history)
    val screenStore = RoomScreenStore(db, settings)

    val sponsorBlock = SponsorBlockApi(http)
    val deArrow = DeArrowApi(http)
    val deArrowCache = DeArrowRepository(deArrow)
    val returnYouTubeDislike = ReturnYouTubeDislikeApi(http)

    val youTube: YouTube = InnerTubeClient(session, http)
    val recommender: Recommender = LocalRecommender(youTube, recommenderStore)

    val player: PlayerController
    val playerHost: PlayerHost
    val cast: CastManager = LoungeCastManager(http, screenStore)

    init {
        // Playback is configured before the first get(): the controller reads these when it is built.
        Playback.httpClient = http
        Playback.resolverOverride = { extractor ->
            CompositeStreamResolver(YtDlpStreamResolver(context, session), extractor) {
                settings.current.resolver == StreamResolverChoice.YTDLP
            }
        }
        Playback.signedInAccess = {
            val credentials = session.current
            if (credentials.signedIn) {
                SignedInAccess(credentials.poTokenIdentifier ?: "") { videoId, poToken ->
                    (youTube as InnerTubeClient).player(videoId, ClientKind.MWEB, null, null, poToken)
                }
            } else {
                null
            }
        }
        Playback.sessionActivity = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        player = Playback.get(context)
        playerHost = player as PlayerHost
    }

    val downloads = DownloadRepository(context, db.downloads(), settings, scope).also { repo ->
        Playback.offlineProvider = { videoId -> repo.offlineMedia(videoId) }
    }

    val overlay = PlayerOverlayState()

    val playbackObserver = PlaybackObserver(
        player,
        history,
        recommender,
        youTube,
        session,
        settings,
        sponsorBlock,
        scope,
    ).also {
        Playback.autoplayProvider = { current ->
            it.nextUp.value?.takeIf { next -> next.id != current.id }
        }
    }

    companion object {
        fun create(app: Application): AppGraph {
            val graph = AppGraph(app)
            // The stored session has to be in place before any screen issues its first request.
            runBlocking { graph.accounts.restore() }
            graph.playbackObserver.start()
            graph.scope.launch { runCatching { YtDlp.ensureReady(app) } }
            return graph
        }

        fun get(context: Context): AppGraph = (context.applicationContext as App).graph
    }
}
