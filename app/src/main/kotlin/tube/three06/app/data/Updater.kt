package tube.three06.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tube.three06.app.di.AppGraph

data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String?,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val fraction: Float) : UpdateState
    data class Installing(val update: AppUpdate) : UpdateState
    data class Failed(val message: String, val update: AppUpdate?) : UpdateState
}

/** Finds, downloads and installs releases published on GitHub. */
class Updater(
    private val context: Context,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val currentVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether the update dialog should be on screen. */
    val prompt = MutableStateFlow(false)

    private var job: Job? = null

    /** The once-a-day check on launch. Stays quiet unless there is something new. */
    fun autoCheck() {
        val s = settings.current
        if (!s.checkForUpdates || System.currentTimeMillis() - s.lastUpdateCheck < CHECK_INTERVAL_MILLIS) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching { latestRelease() }
            settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
            val update = result.getOrNull()
            _state.value = when {
                result.isFailure ->
                    // Being offline at launch is not worth a message.
                    if (manual) UpdateState.Failed(result.exceptionOrNull()?.message ?: "Check failed", null) else UpdateState.Idle
                update == null || !isNewer(update.version, currentVersion) -> UpdateState.UpToDate
                else -> UpdateState.Available(update)
            }
            if (update != null && _state.value is UpdateState.Available) {
                prompt.value = manual || update.version != settings.current.skippedUpdate
            }
        }
    }

    fun skip(update: AppUpdate) {
        prompt.value = false
        scope.launch { settings.update { it.copy(skippedUpdate = update.version) } }
    }

    fun install(update: AppUpdate) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0f)
            runCatching {
                val apk = download(update)
                _state.value = UpdateState.Installing(update)
                commit(apk)
            }.onFailure {
                _state.value = UpdateState.Failed(it.message ?: "Update failed", update)
            }
        }
    }

    /** Called by the install receiver; a successful install kills the process instead. */
    fun onInstallFailed(message: String) {
        val update = (_state.value as? UpdateState.Installing)?.update
        _state.value = UpdateState.Failed(message, update)
    }

    private suspend fun latestRelease(): AppUpdate? = withContext(Dispatchers.IO) {
        // "latest" leaves out drafts and pre-releases.
        val body = get("https://api.github.com/repos/$REPO/releases/latest").use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code}")
            response.body?.string() ?: return@withContext null
        }
        val release = json.parseToJsonElement(body) as? JsonObject ?: return@withContext null
        val assets = release["assets"]?.jsonArray?.filterIsInstance<JsonObject>().orEmpty()
        fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        val apk = assets.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return@withContext null
        AppUpdate(
            version = release.str("tag_name") ?: return@withContext null,
            // Release notes are Markdown; the dialog shows plain text, so drop the heading marks.
            notes = release.str("body").orEmpty().lines()
                .joinToString("\n") { if (it.startsWith("#")) it.trimStart('#', ' ') else it }.trim(),
            apkUrl = apk.str("browser_download_url") ?: return@withContext null,
            sha256Url = assets.firstOrNull { it.str("name")?.endsWith(".apk.sha256") == true }?.str("browser_download_url"),
            sizeBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    private fun get(url: String) =
        http.newCall(Request.Builder().url(url).header("User-Agent", "306Tube").build()).execute()

    private suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "306Tube-${update.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        get(update.apkUrl).use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Download failed: empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                val input = body.byteStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    if (total > 0) _state.value = UpdateState.Downloading(update, (done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        // Android still refuses an APK signed with a different key; this catches a corrupt download.
        update.sha256Url?.let { url ->
            val expected = get(url).use { it.body?.string() }?.trim()?.substringBefore(' ')?.lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!expected.isNullOrEmpty() && expected != actual) {
                file.delete()
                throw IOException("Download is corrupt (checksum mismatch)")
            }
        }
        file
    }

    private suspend fun commit(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer fills in the result extras, so the PendingIntent has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                flags,
            )
            session.commit(result.intentSender)
        }
    }

    companion object {
        const val REPO = "306bobby-android/306tube"
        private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Whether release [latest] should replace the installed [current]. A test build named by
         * git describe (1.0.0-12-gabc1234) counts as its base release; any other suffix (1.0.0-rc1)
         * is a pre-release and sits below it.
         */
        fun isNewer(latest: String, current: String): Boolean {
            fun core(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = core(latest)
            val b = core(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            val suffix = current.substringAfter('-', "")
            return suffix.isNotEmpty() && !Regex("\\d+-g[0-9a-f]+").matches(suffix) && !latest.contains('-')
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> AppGraph.get(context).updater.onInstallFailed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Install was cancelled"
                    // Typically a debug or self-built copy, signed with a different key than releases.
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This release can't replace the installed copy. Uninstall it and install the release by hand."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                        ?: "The installer refused the update"
                },
            )
        }
    }
}
