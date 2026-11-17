package tube.three06.player.potoken

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

internal class WebViewPoTokenProvider(
    context: Context,
    private val downloader: Downloader,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : PoTokenProvider {
    private val appContext = context.applicationContext
    private val webViewSupported by lazy { supportsWebView(appContext) }

    @Volatile
    private var webViewBadImpl = false

    private val lock = Mutex()
    private var visitorData: String? = null
    private var streamingPot: String? = null
    private var generator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!webViewSupported || webViewBadImpl) return null

        // The WebView lives on the main thread; blocking it here would deadlock.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "poToken requested on the main thread; refusing to block it")
            return null
        }

        return try {
            runBlocking {
                withTimeout(timeoutMs) { webClientPoToken(videoId, forceRecreate = false) }
            }
        } catch (e: BadWebViewException) {
            Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            Log.e(TAG, "Could not obtain poToken, continuing without one", e)
            null
        }
    }

    private suspend fun webClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (gen, visitor, streaming, recreated) = lock.withLock {
            val shouldRecreate = generator == null || forceRecreate || generator!!.isExpired()

            if (shouldRecreate) {
                val requestInfo = InnertubeClientRequestInfo.ofWebClient()
                requestInfo.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion()

                visitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                    requestInfo,
                    NewPipe.getPreferredLocalization(),
                    NewPipe.getPreferredContentCountry(),
                    YoutubeParsingHelper.getYouTubeHeaders(),
                    YoutubeParsingHelper.YOUTUBEI_V1_URL,
                    null,
                    false,
                )

                generator?.let { old -> Handler(Looper.getMainLooper()).post { old.close() } }
                generator = PoTokenWebView.create(appContext, downloader)

                // The streaming poToken is bound to the visitor data and is reused for every video.
                streamingPot = generator!!.generatePoToken(visitorData!!)
            }

            Quadruple(generator!!, visitorData!!, streamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            gen.generatePoToken(videoId)
        } catch (t: Throwable) {
            if (recreated) throw t
            Log.e(TAG, "Failed to obtain poToken, retrying with a fresh generator", t)
            return webClientPoToken(videoId, forceRecreate = true)
        }

        return PoTokenResult(visitor, playerPot, streaming)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    internal companion object {
        const val TAG = "WebViewPoTokenProvider"
        const val DEFAULT_TIMEOUT_MS = 20_000L

        fun supportsWebView(context: Context): Boolean {
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW)) return false
            return runCatching { WebViewCompat.getCurrentWebViewPackage(context) != null }.getOrDefault(false)
        }
    }
}
