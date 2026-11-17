package tube.three06.app.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tube.three06.app.data.db.DownloadDao
import tube.three06.app.data.db.DownloadEntity
import tube.three06.app.data.ytdlp.YtDlp
import tube.three06.innertube.model.Video
import tube.three06.player.offline.OfflineMedia
import java.io.File

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val dir: File =
        (context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")).apply { mkdirs() }

    private val waitingForWifi = MutableStateFlow<Set<String>>(emptySet())
    val idle = MutableStateFlow(true)
    private var worker: Job? = null

    val items: Flow<List<DownloadItem>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toItem() } }

    fun hasDownloadsDir(): Boolean = dir.exists()

    suspend fun start(video: Video, maxHeight: Int) {
        dao.upsert(
            DownloadEntity(
                videoId = video.id,
                videoJson = VideoJson.encode(video),
                maxHeight = maxHeight,
                state = STATE_QUEUED,
                percent = 0,
                videoPath = null,
                audioPath = null,
                durationSeconds = video.durationSeconds,
                error = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        kick()
    }

    suspend fun retry(videoId: String) {
        dao.setState(videoId, STATE_QUEUED, 0)
        kick()
    }

    suspend fun remove(videoId: String) {
        YoutubeDL.getInstance().destroyProcessById(videoId)
        dao.byId(videoId)?.let { row ->
            listOfNotNull(row.videoPath, row.audioPath).forEach { File(it).delete() }
        }
        dir.listFiles { file -> file.name.startsWith("$videoId.") }?.forEach { it.delete() }
        dao.delete(videoId)
    }

    suspend fun offlineMedia(videoId: String): OfflineMedia? {
        val row = dao.byId(videoId)?.takeIf { it.state == STATE_DONE } ?: return null
        val video = File(row.videoPath.orEmpty()).takeIf { row.videoPath != null && it.exists() }
        val audio = File(row.audioPath.orEmpty()).takeIf { row.audioPath != null && it.exists() }
        if (video == null && audio == null) return null
        return OfflineMedia(
            videoId = videoId,
            title = VideoJson.decode(row.videoJson)?.title,
            durationSeconds = row.durationSeconds,
            videoUri = video?.toURI()?.toString(),
            audioUri = audio?.toURI()?.toString(),
        )
    }

    fun kick() {
        if (worker?.isActive == true) return
        idle.value = false
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        worker = scope.launch(Dispatchers.IO) {
            try {
                YtDlp.ensureReady(context)
                while (true) {
                    val row = dao.nextPending() ?: break
                    if (settings.current.downloadsWifiOnly && !onUnmeteredNetwork()) {
                        waitingForWifi.value += row.videoId
                        delay(15_000)
                        continue
                    }
                    waitingForWifi.value -= row.videoId
                    run(row)
                }
            } catch (e: Exception) {
                Log.w(TAG, "download worker stopped", e)
            } finally {
                idle.value = true
            }
        }
    }

    private suspend fun run(row: DownloadEntity) {
        dao.setState(row.videoId, STATE_RUNNING, 0)
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=${row.videoId}").apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-cache-dir")
            addOption("-N", 4)
            addOption("-P", dir.absolutePath)
            // format_id stays in the name so audio-only files can be told apart from video ones
            addOption("-o", "%(id)s.%(format_id)s.%(ext)s")
            addOption("-f", formatSelector(row.maxHeight))
        }
        var lastWrite = 0L
        val result = runCatching {
            YoutubeDL.getInstance().execute(request, row.videoId) { progress, _, _ ->
                val now = System.currentTimeMillis()
                if (progress >= 0f && now - lastWrite > 500) {
                    lastWrite = now
                    runBlocking { dao.setPercent(row.videoId, progress.toInt().coerceIn(0, 100)) }
                }
            }
        }
        result.onFailure { e ->
            Log.w(TAG, "yt-dlp failed for ${row.videoId}", e)
            // Removing a download kills the process; the row is already gone then.
            if (dao.byId(row.videoId) != null) {
                dao.setState(
                    row.videoId,
                    STATE_FAILED,
                    0,
                    e.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(200) ?: "yt-dlp failed",
                )
            }
            return
        }

        val files = dir.listFiles { file ->
            file.name.startsWith("${row.videoId}.") && !file.name.endsWith(".part")
        } ?: emptyArray()
        val video = files.firstOrNull { it.extension in VIDEO_EXTENSIONS && !isAudioOnly(it) }
        val audio = files.firstOrNull { it.extension in AUDIO_EXTENSIONS || isAudioOnly(it) }
        if (video == null && audio == null) {
            dao.setState(row.videoId, STATE_FAILED, 0, "yt-dlp produced no files")
            return
        }
        dao.byId(row.videoId)
            ?.copy(
                state = STATE_DONE,
                percent = 100,
                videoPath = video?.absolutePath,
                audioPath = audio?.absolutePath,
                error = null,
            )
            ?.let { dao.upsert(it) }
    }

    private fun isAudioOnly(file: File): Boolean =
        file.nameWithoutExtension.substringAfterLast('.').substringBefore('-') in AUDIO_FORMAT_IDS

    private fun formatSelector(maxHeight: Int): String =
        if (maxHeight <= 0) {
            "ba[ext=m4a]/ba"
        } else {
            "bv*[height<=$maxHeight][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$maxHeight]+ba/b[height<=$maxHeight]/b"
        }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun DownloadEntity.toItem(): DownloadItem? {
        val video = VideoJson.decode(videoJson) ?: return null
        return DownloadItem(
            video = video,
            qualityLabel = if (maxHeight <= 0) "Audio" else "up to ${maxHeight}p",
            state = when (state) {
                STATE_RUNNING -> DownloadItem.State.RUNNING
                STATE_DONE -> DownloadItem.State.DONE
                STATE_FAILED -> DownloadItem.State.FAILED
                else -> if (videoId in waitingForWifi.value) {
                    DownloadItem.State.WAITING_FOR_WIFI
                } else {
                    DownloadItem.State.QUEUED
                }
            },
            percent = percent,
            bytes = listOfNotNull(videoPath, audioPath).sumOf { File(it).length() },
            error = error,
        )
    }

    companion object {
        private const val TAG = "DownloadRepository"
        const val STATE_QUEUED = "queued"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"

        private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv")
        private val AUDIO_EXTENSIONS = setOf("m4a", "opus", "ogg", "mp3", "weba")

        private val AUDIO_FORMAT_IDS = setOf("139", "140", "141", "249", "250", "251", "256", "258")
    }
}
