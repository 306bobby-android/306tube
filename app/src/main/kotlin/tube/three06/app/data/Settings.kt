package tube.three06.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ResumeMode { ALWAYS, ASK, NEVER }

enum class StreamResolverChoice { YTDLP, EXTRACTOR }

object SponsorBlockCategories {
    val ALL: List<Pair<String, String>> = listOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Unpaid / self promotion",
        "interaction" to "Interaction reminder",
        "intro" to "Intermission / intro",
        "outro" to "Endcards / credits",
        "preview" to "Preview / recap",
        "music_offtopic" to "Non-music section",
        "filler" to "Filler tangent",
    )

    val DEFAULT: Set<String> = setOf(
        "sponsor",
        "selfpromo",
        "interaction",
        "intro",
        "outro",
        "preview",
        "music_offtopic",
    )
}

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    val qualityWifi: Int = 0,
    val qualityMobile: Int = 480,
    val autoplay: Boolean = true,
    val autoplayCountdownSeconds: Int = 8,
    val pictureInPicture: Boolean = true,
    val backgroundPlay: Boolean = true,
    val resume: ResumeMode = ResumeMode.ALWAYS,
    val swipeBrightness: Boolean = true,
    val swipeVolume: Boolean = true,
    val swipeSeek: Boolean = true,
    val swipeInPortrait: Boolean = false,
    val swipeHaptics: Boolean = true,
    val downloadsWifiOnly: Boolean = true,
    val ytDlpSetupDone: Boolean = false,
    val resolver: StreamResolverChoice = StreamResolverChoice.EXTRACTOR,
    val playerBrightness: Float = -1f,
    val recommenderEnabled: Boolean = true,
    val sponsorBlock: Boolean = true,
    val sponsorBlockCategories: Set<String> = SponsorBlockCategories.DEFAULT,
    val deArrow: Boolean = false,
    val deArrowRandomThumbnail: Boolean = false,
    val showHome: Boolean = true,
    val hideShorts: Boolean = true,
    val returnYouTubeDislike: Boolean = true,
    val syncHistory: Boolean = true,
    val visitorData: String? = null,
    val castSenderId: String? = null,
    val castScreenId: String? = null,
    val castReceiverId: String? = null,
    val castInBackground: Boolean = false,
    val checkForUpdates: Boolean = true,
    val lastUpdateCheck: Long = 0L,
    val skippedUpdate: String? = null,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val qualityWifi = intPreferencesKey("quality_wifi")
        val qualityMobile = intPreferencesKey("quality_mobile")
        val autoplay = booleanPreferencesKey("autoplay")
        val autoplayCountdown = intPreferencesKey("autoplay_countdown")
        val pip = booleanPreferencesKey("pip")
        val backgroundPlay = booleanPreferencesKey("background_play")
        val resume = stringPreferencesKey("resume")
        val swipeBrightness = booleanPreferencesKey("swipe_brightness")
        val swipeVolume = booleanPreferencesKey("swipe_volume")
        val swipeSeek = booleanPreferencesKey("swipe_seek")
        val swipeInPortrait = booleanPreferencesKey("swipe_in_portrait")
        val swipeHaptics = booleanPreferencesKey("swipe_haptics")
        val downloadsWifiOnly = booleanPreferencesKey("downloads_wifi_only")
        val ytDlpSetupDone = booleanPreferencesKey("ytdlp_setup_done")
        val resolver = stringPreferencesKey("stream_resolver")
        val playerBrightness = floatPreferencesKey("player_brightness")
        val recommender = booleanPreferencesKey("recommender")
        val sponsorBlock = booleanPreferencesKey("sponsorblock")
        val sbCategories = stringSetPreferencesKey("sponsorblock_categories")
        val deArrow = booleanPreferencesKey("dearrow")
        val deArrowRandomThumbnail = booleanPreferencesKey("dearrow_random_thumbnail")
        val showHome = booleanPreferencesKey("show_home")
        val hideShorts = booleanPreferencesKey("hide_shorts")
        val ryd = booleanPreferencesKey("ryd")
        val syncHistory = booleanPreferencesKey("sync_history")
        val visitorData = stringPreferencesKey("visitor_data")
        val castSenderId = stringPreferencesKey("cast_sender_id")
        val castScreenId = stringPreferencesKey("cast_screen_id")
        val castReceiverId = stringPreferencesKey("cast_receiver_id")
        val castInBackground = booleanPreferencesKey("cast_in_background")
        val checkForUpdates = booleanPreferencesKey("check_for_updates")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val skippedUpdate = stringPreferencesKey("skipped_update")
    }

    val flow: Flow<Settings> = store.data.map { decode(it) }

    private fun decode(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
            qualityWifi = p[Keys.qualityWifi] ?: d.qualityWifi,
            qualityMobile = p[Keys.qualityMobile] ?: d.qualityMobile,
            autoplay = p[Keys.autoplay] ?: d.autoplay,
            autoplayCountdownSeconds = p[Keys.autoplayCountdown] ?: d.autoplayCountdownSeconds,
            pictureInPicture = p[Keys.pip] ?: d.pictureInPicture,
            backgroundPlay = p[Keys.backgroundPlay] ?: d.backgroundPlay,
            resume = p[Keys.resume]?.let { runCatching { ResumeMode.valueOf(it) }.getOrNull() } ?: d.resume,
            swipeBrightness = p[Keys.swipeBrightness] ?: d.swipeBrightness,
            swipeVolume = p[Keys.swipeVolume] ?: d.swipeVolume,
            swipeSeek = p[Keys.swipeSeek] ?: d.swipeSeek,
            swipeInPortrait = p[Keys.swipeInPortrait] ?: d.swipeInPortrait,
            swipeHaptics = p[Keys.swipeHaptics] ?: d.swipeHaptics,
            downloadsWifiOnly = p[Keys.downloadsWifiOnly] ?: d.downloadsWifiOnly,
            ytDlpSetupDone = p[Keys.ytDlpSetupDone] ?: d.ytDlpSetupDone,
            resolver = p[Keys.resolver]?.let { runCatching { StreamResolverChoice.valueOf(it) }.getOrNull() }
                ?: d.resolver,
            playerBrightness = p[Keys.playerBrightness] ?: d.playerBrightness,
            recommenderEnabled = p[Keys.recommender] ?: d.recommenderEnabled,
            sponsorBlock = p[Keys.sponsorBlock] ?: d.sponsorBlock,
            sponsorBlockCategories = p[Keys.sbCategories] ?: d.sponsorBlockCategories,
            deArrow = p[Keys.deArrow] ?: d.deArrow,
            deArrowRandomThumbnail = p[Keys.deArrowRandomThumbnail] ?: d.deArrowRandomThumbnail,
            showHome = p[Keys.showHome] ?: d.showHome,
            hideShorts = p[Keys.hideShorts] ?: d.hideShorts,
            returnYouTubeDislike = p[Keys.ryd] ?: d.returnYouTubeDislike,
            syncHistory = p[Keys.syncHistory] ?: d.syncHistory,
            visitorData = p[Keys.visitorData],
            castSenderId = p[Keys.castSenderId],
            castScreenId = p[Keys.castScreenId],
            castReceiverId = p[Keys.castReceiverId],
            castInBackground = p[Keys.castInBackground] ?: d.castInBackground,
            checkForUpdates = p[Keys.checkForUpdates] ?: d.checkForUpdates,
            lastUpdateCheck = p[Keys.lastUpdateCheck] ?: d.lastUpdateCheck,
            skippedUpdate = p[Keys.skippedUpdate],
        )
    }

    // `current` is read synchronously during startup, so the initial value must be the stored one, not defaults
    val state: StateFlow<Settings> = flow.stateIn(scope, SharingStarted.Eagerly, runBlocking { flow.first() })

    val current: Settings get() = state.value

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val s = transform(decode(p))
            p[Keys.theme] = s.theme.name
            p[Keys.dynamicColor] = s.dynamicColor
            p[Keys.qualityWifi] = s.qualityWifi
            p[Keys.qualityMobile] = s.qualityMobile
            p[Keys.autoplay] = s.autoplay
            p[Keys.autoplayCountdown] = s.autoplayCountdownSeconds
            p[Keys.pip] = s.pictureInPicture
            p[Keys.backgroundPlay] = s.backgroundPlay
            p[Keys.castInBackground] = s.castInBackground
            p[Keys.checkForUpdates] = s.checkForUpdates
            p[Keys.lastUpdateCheck] = s.lastUpdateCheck
            if (s.skippedUpdate != null) p[Keys.skippedUpdate] = s.skippedUpdate else p.remove(Keys.skippedUpdate)
            p[Keys.resume] = s.resume.name
            p[Keys.swipeBrightness] = s.swipeBrightness
            p[Keys.swipeVolume] = s.swipeVolume
            p[Keys.swipeSeek] = s.swipeSeek
            p[Keys.swipeInPortrait] = s.swipeInPortrait
            p[Keys.swipeHaptics] = s.swipeHaptics
            p[Keys.downloadsWifiOnly] = s.downloadsWifiOnly
            p[Keys.ytDlpSetupDone] = s.ytDlpSetupDone
            p[Keys.resolver] = s.resolver.name
            p[Keys.playerBrightness] = s.playerBrightness
            p[Keys.recommender] = s.recommenderEnabled
            p[Keys.sponsorBlock] = s.sponsorBlock
            p[Keys.sbCategories] = s.sponsorBlockCategories
            p[Keys.deArrow] = s.deArrow
            p[Keys.deArrowRandomThumbnail] = s.deArrowRandomThumbnail
            p[Keys.showHome] = s.showHome
            p[Keys.hideShorts] = s.hideShorts
            p[Keys.ryd] = s.returnYouTubeDislike
            p[Keys.syncHistory] = s.syncHistory
            if (s.visitorData != null) p[Keys.visitorData] = s.visitorData else p.remove(Keys.visitorData)
            if (s.castSenderId != null) p[Keys.castSenderId] = s.castSenderId else p.remove(Keys.castSenderId)
            if (s.castScreenId != null) p[Keys.castScreenId] = s.castScreenId else p.remove(Keys.castScreenId)
            if (s.castReceiverId != null) p[Keys.castReceiverId] = s.castReceiverId else p.remove(Keys.castReceiverId)
        }
    }
}
