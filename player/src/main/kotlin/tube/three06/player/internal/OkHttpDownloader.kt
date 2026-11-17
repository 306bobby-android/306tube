package tube.three06.player.internal

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class OkHttpDownloader(baseClient: OkHttpClient) : Downloader() {
    val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cookies = ConcurrentHashMap<String, String>()

    var restrictedMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                cookies[YOUTUBE_RESTRICTED_MODE_COOKIE_KEY] = YOUTUBE_RESTRICTED_MODE_COOKIE
            } else {
                cookies.remove(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
            }
        }

    fun setCookie(key: String, cookie: String) {
        cookies[key] = cookie
    }

    fun removeCookie(key: String) {
        cookies.remove(key)
    }

    private fun cookiesFor(url: String): String =
        listOfNotNull(
            if (url.contains(YOUTUBE_DOMAIN)) cookies[YOUTUBE_RESTRICTED_MODE_COOKIE_KEY] else null,
            cookies[RECAPTCHA_COOKIES_KEY],
        )
            .flatMap { it.split(Regex("; *")) }
            .distinct()
            .joinToString("; ")

    override fun execute(request: Request): Response {
        val url = request.url()
        val body = request.dataToSend()?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        val cookieHeader = cookiesFor(url)
        if (cookieHeader.isNotEmpty()) builder.addHeader("Cookie", cookieHeader)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (value in values) builder.addHeader(name, value)
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val responseBody = response.body?.use { it.string() }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY = "youtube_restricted_mode_key"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000"
        const val YOUTUBE_DOMAIN = "youtube.com"
        const val RECAPTCHA_COOKIES_KEY = "recaptcha_cookies"
    }
}
