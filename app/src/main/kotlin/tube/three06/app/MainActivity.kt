package tube.three06.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import tube.three06.app.di.AppGraph
import tube.three06.app.ui.nav.TubeApp
import tube.three06.player.Playback

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as App).graph
    private val pendingIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) offer(intent)
        setContent {
            TubeApp(graph, pendingIntent, { pendingIntent.value = null }, ::enterPip)
        }
    }

    override fun onStart() {
        super.onStart()
        Playback.ensureService(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        offer(intent)
    }

    private fun offer(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND ||
            intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true
        ) {
            pendingIntent.value = intent
        }
    }

    private fun enterPip() {
        if (graph.player.state.value.current == null) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = graph.player.state.value
        if (!graph.isTv && graph.settings.current.pictureInPicture && state.isPlaying && !state.audioOnly &&
            (graph.overlay.expanded.value || graph.overlay.fullscreen.value)
        ) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        graph.overlay.inPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode && !graph.settings.current.backgroundPlay) graph.player.pause()
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
