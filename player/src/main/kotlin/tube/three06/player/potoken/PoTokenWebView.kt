package tube.three06.player.potoken

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.downloader.Downloader
import java.time.Instant

internal class PoTokenWebView private constructor(
    context: Context,
    private val downloader: Downloader,
) : PoTokenGenerator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val webView = WebView(context)
    private val initialization = CompletableDeferred<Unit>()
    private val pending = mutableListOf<Pair<String, CompletableDeferred<String>>>()

    @Volatile
    private var expirationInstant: Instant = Instant.EPOCH

    private fun setUp() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, false)
        }
        settings.userAgentString = USER_AGENT
        // All network traffic goes through the Downloader; the page itself must not fetch anything.
        settings.blockNetworkLoads = true

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    val exception = BadWebViewException(fmt)
                    Log.e(TAG, "This WebView implementation is broken: $fmt")

                    failInitialization(exception)
                    popAllPending().forEach { (_, deferred) -> deferred.completeExceptionally(exception) }
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun loadHtmlAndObtainBotguard(context: Context) {
        scope.launch {
            try {
                val html = withContext(Dispatchers.IO) {
                    context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                }
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    html.replaceFirst(
                        "</script>",
                        "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
                    ),
                    "text/html",
                    "utf-8",
                    null,
                )
            } catch (t: Throwable) {
                failInitialization(t)
            }
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/Create",
            "[ \"$REQUEST_KEY\" ]",
        ) { body ->
            val parsedChallengeData = parseChallengeData(body)
            webView.evaluateJavascript(
                """try {
                    data = $parsedChallengeData
                    runBotGuard(data).then(function (result) {
                        this.webPoSignalOutput = result.webPoSignalOutput
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null,
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, "Initialization error from JavaScript: $error")
        failInitialization(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        makeBotguardServiceRequest(
            "https://www.youtube.com/api/jnn/v1/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { body ->
            val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(body)

            // Leave a 10 minute margin so a token is never handed out right before it lapses.
            expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

            webView.evaluateJavascript("this.integrityToken = $integrityToken") {
                initialization.complete(Unit)
            }
        }
    }

    override suspend fun generatePoToken(identifier: String): String {
        try {
            val deferred = CompletableDeferred<String>()
            synchronized(pending) { pending.add(identifier to deferred) }
            val u8Identifier = stringToU8(identifier)
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    """try {
                    identifier = "$identifier"
                    u8Identifier = $u8Identifier
                    poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                    poTokenU8String = ""
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ","
                        poTokenU8String += poTokenU8[i]
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                } catch (error) {
                    $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                }""",
                    null,
                )
            }
            return deferred.await()
        } catch (e: CancellationException) {
            popPending(identifier)
            throw e
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.e(TAG, "obtainPoToken error from JavaScript: $error")
        popPending(identifier)?.completeExceptionally(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = try {
            u8ToBase64(poTokenU8)
        } catch (t: Throwable) {
            popPending(identifier)?.completeExceptionally(t)
            return
        }
        popPending(identifier)?.complete(poToken)
    }

    override fun isExpired(): Boolean = Instant.now().isAfter(expirationInstant)

    private fun popPending(identifier: String): CompletableDeferred<String>? =
        synchronized(pending) {
            pending.indexOfFirst { it.first == identifier }
                .takeIf { it >= 0 }
                ?.let { pending.removeAt(it).second }
        }

    private fun popAllPending(): List<Pair<String, CompletableDeferred<String>>> =
        synchronized(pending) {
            val result = pending.toList()
            pending.clear()
            result
        }

    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        handleResponseBody: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    downloader.post(
                        url,
                        mapOf(
                            "User-Agent" to listOf(USER_AGENT),
                            "Accept" to listOf("application/json"),
                            "Content-Type" to listOf("application/json+protobuf"),
                            "x-goog-api-key" to listOf(GOOGLE_API_KEY),
                            "x-user-agent" to listOf("grpc-web-javascript/0.1"),
                        ),
                        data.toByteArray(),
                    )
                }
                val httpCode = response.responseCode()
                if (httpCode != 200) {
                    failInitialization(PoTokenException("Invalid response code: $httpCode"))
                    return@launch
                }
                handleResponseBody(response.responseBody())
            } catch (t: Throwable) {
                failInitialization(t)
            }
        }
    }

    private fun failInitialization(error: Throwable) {
        if (!initialization.isCompleted) initialization.completeExceptionally(error)
    }

    override fun close() {
        scope.cancel()
        popAllPending().forEach { (_, deferred) ->
            deferred.completeExceptionally(PoTokenException("Generator closed"))
        }
        webView.clearHistory()
        webView.clearCache(true)
        webView.loadUrl("about:blank")
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        suspend fun create(context: Context, downloader: Downloader): PoTokenGenerator =
            withContext(Dispatchers.Main) {
                val webView = PoTokenWebView(context.applicationContext, downloader)
                try {
                    webView.setUp()
                    webView.loadHtmlAndObtainBotguard(context.applicationContext)
                    webView.initialization.await()
                    webView
                } catch (t: Throwable) {
                    webView.close()
                    throw t
                }
            }
    }
}
