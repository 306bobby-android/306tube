package tube.three06.player.jsc

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class SolverWebView private constructor(context: Context) {
    private val webView = WebView(context)
    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val prepared = HashSet<String>()

    @Volatile
    var alive = true
        private set

    @JavascriptInterface
    fun onReady() {
        ready.complete(Unit)
    }

    @JavascriptInterface
    fun onResult(requestId: String, json: String) {
        pending.remove(requestId)?.complete(json)
    }

    suspend fun prepare(playerId: String) {
        if (playerId in prepared) return
        val result = JSONObject(call("prepare", JSONObject.quote(playerId), JSONObject.quote(playerUrl(playerId))))
        check(result.optString("type") == "result") {
            "solver could not prepare player $playerId: ${result.optString("error")}"
        }
        prepared.add(playerId)
    }

    suspend fun solve(playerId: String, n: Collection<String>, sig: Collection<String>): JsChallengeSolver.Solution {
        val requests = JSONArray()
        if (n.isNotEmpty()) {
            requests.put(JSONObject().put("type", "n").put("challenges", JSONArray(n.toList())))
        }
        if (sig.isNotEmpty()) {
            requests.put(JSONObject().put("type", "sig").put("challenges", JSONArray(sig.toList())))
        }
        val result = JSONObject(call("solve", JSONObject.quote(playerId), JSONObject.quote(requests.toString())))
        check(result.optString("type") == "result") { "solver failed: ${result.optString("error")}" }

        val responses = result.getJSONArray("responses")
        var index = 0
        fun next(): Map<String, String> {
            val response = responses.getJSONObject(index++)
            check(response.optString("type") == "result") { "solver failed: ${response.optString("error")}" }
            val data = response.getJSONObject("data")
            return data.keys().asSequence().associateWith { data.getString(it) }
        }
        return JsChallengeSolver.Solution(
            n = if (n.isNotEmpty()) next() else emptyMap(),
            sig = if (sig.isNotEmpty()) next() else emptyMap(),
        )
    }

    private suspend fun call(function: String, vararg args: String): String {
        check(alive) { "solver WebView is gone" }
        ready.await()
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pending[id] = deferred
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript("$function(${JSONObject.quote(id)}, ${args.joinToString(", ")})", null)
        }
        return deferred.await()
    }

    fun close() {
        alive = false
        pending.values.forEach { it.completeExceptionally(IllegalStateException("solver closed")) }
        pending.clear()
        webView.destroy()
    }

    private fun setUp(context: Context) {
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "solver WebView renderer gone")
                alive = false
                pending.values.forEach { it.completeExceptionally(IllegalStateException("solver renderer gone")) }
                pending.clear()
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("solver renderer gone"))
                // Returning true keeps the app alive; the next solve builds a fresh WebView.
                return true
            }
        }
        val assets = context.assets
        fun asset(name: String) = assets.open("jsc/$name").bufferedReader().use { it.readText() }
        val html = asset("solver.html")
            .replace("<!--LIB-->", "<script>${asset("yt.solver.lib.min.js")}</script>")
            .replace("<!--CORE-->", "<script>${asset("yt.solver.core.min.js")}</script>")
        // The page fetches base.js itself, so it needs the YouTube origin.
        webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
    }

    companion object {
        private const val TAG = "SolverWebView"
        private const val JS_INTERFACE = "Solver"

        fun playerUrl(playerId: String): String =
            "https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js"

        suspend fun create(context: Context): SolverWebView = withContext(Dispatchers.Main) {
            val webView = SolverWebView(context.applicationContext)
            try {
                webView.setUp(context.applicationContext)
                webView
            } catch (t: Throwable) {
                webView.close()
                throw t
            }
        }
    }
}
