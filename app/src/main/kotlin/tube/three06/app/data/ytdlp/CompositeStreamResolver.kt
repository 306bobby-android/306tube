package tube.three06.app.data.ytdlp

import android.util.Log
import kotlinx.coroutines.CancellationException
import tube.three06.player.ResolvedStreams
import tube.three06.player.StreamResolver
import tube.three06.player.VideoUnavailableException

class CompositeStreamResolver(
    private val ytDlp: StreamResolver,
    private val extractor: StreamResolver,
    private val preferYtDlp: () -> Boolean,
) : StreamResolver {

    override suspend fun resolve(videoId: String): ResolvedStreams {
        val (first, second) = if (preferYtDlp()) ytDlp to extractor else extractor to ytDlp
        return try {
            first.resolve(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "primary resolver failed for $videoId, trying the other", e)
            try {
                second.resolve(videoId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw e
            }
        }
    }

    override fun invalidate(videoId: String) {
        ytDlp.invalidate(videoId)
        extractor.invalidate(videoId)
    }

    private companion object {
        const val TAG = "CompositeStreamResolver"
    }
}
