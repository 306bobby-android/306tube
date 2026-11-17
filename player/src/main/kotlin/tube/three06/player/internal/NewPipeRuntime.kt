package tube.three06.player.internal

import android.content.Context
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import tube.three06.player.potoken.WebViewPoTokenProvider
import java.util.Locale

internal object NewPipeRuntime {
    @Volatile
    private var downloaderInstance: OkHttpDownloader? = null

    val downloader: OkHttpDownloader
        get() = checkNotNull(downloaderInstance) { "NewPipeRuntime not initialised" }

    val isInitialised: Boolean
        get() = downloaderInstance != null

    @Synchronized
    fun init(context: Context, http: OkHttpClient): OkHttpDownloader {
        downloaderInstance?.let { return it }
        val appContext = context.applicationContext
        val downloader = OkHttpDownloader(http)
        val locale = Locale.getDefault()
        NewPipe.init(
            downloader,
            runCatching { Localization.fromLocale(locale) }.getOrDefault(Localization.DEFAULT),
            locale.country.takeIf { it.isNotBlank() }?.let { ContentCountry(it) } ?: ContentCountry.DEFAULT,
        )
        YoutubeStreamExtractor.setPoTokenProvider(WebViewPoTokenProvider(appContext, downloader))
        downloaderInstance = downloader
        return downloader
    }
}
