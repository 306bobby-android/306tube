package tube.three06.cast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class DialDevice(
    val friendlyName: String,
    val location: String,
    val applicationUrl: String,
    val address: String,
    val udn: String?,
    val youtubeState: String?,
)

data class Screen(
    val screenId: String,
    val name: String,
    val loungeToken: String,
    val tokenExpiresAtMillis: Long,
    val deviceId: String?,
    val lastAddress: String?,
)

data class RemoteState(
    val videoId: String? = null,
    val playbackState: PlaybackState = PlaybackState.UNKNOWN,
    val currentTimeSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val volume: Int? = null,
    val muted: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queueVideoIds: List<String> = emptyList(),
    val loungeStatusDevices: List<String> = emptyList(),
) {
    enum class PlaybackState { UNKNOWN, STOPPED, BUFFERING, PLAYING, PAUSED, ENDED, ADVERTISEMENT }
}

enum class DpadKey { UP, DOWN, LEFT, RIGHT, ENTER, BACK }

interface LoungeSession {
    val screen: Screen
    val state: StateFlow<RemoteState>
    val connected: StateFlow<Boolean>
    val events: SharedFlow<LoungeEvent>

    suspend fun setPlaylist(
        videoIds: List<String>,
        startIndex: Int = 0,
        startSeconds: Long = 0,
        listId: String? = null,
    )

    suspend fun addVideo(videoId: String)
    suspend fun insertVideo(videoId: String)
    suspend fun removeVideo(videoId: String)
    suspend fun clearQueue()
    suspend fun play()
    suspend fun pause()
    suspend fun next()
    suspend fun previous()
    suspend fun seekTo(seconds: Long)
    suspend fun setVolume(percent: Int)
    suspend fun setAutoplay(enabled: Boolean)
    suspend fun setSpeed(speed: Float)
    suspend fun dpad(key: DpadKey)
    suspend fun disconnect()
}

interface CastManager {
    fun discover(): Flow<DialDevice>
    suspend fun pair(device: DialDevice): Screen
    suspend fun pairWithCode(code: String): Screen
    suspend fun connect(screen: Screen): LoungeSession
    suspend fun knownScreens(): List<Screen>
    suspend fun forget(screen: Screen)
}

class PairingException(message: String) : Exception(message)

class LoungeException(message: String, cause: Throwable? = null) : Exception(message, cause)
