package tube.three06.app.ui.watch

import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerOverlayState {
    enum class FullscreenSource { BUTTON, ROTATION }

    val expanded = MutableStateFlow(false)
    val fullscreen = MutableStateFlow(false)
    var fullscreenSource = FullscreenSource.BUTTON
    val lockPortrait = MutableStateFlow(false)
    val inPip = MutableStateFlow(false)
    val zoomed = MutableStateFlow(false)
    val progress = mutableFloatStateOf(0f)
    val resumeOffer = MutableStateFlow<Long?>(null)

    fun expand() {
        expanded.value = true
    }

    fun enterFullscreen(source: FullscreenSource) {
        fullscreenSource = source
        fullscreen.value = true
    }

    fun exitFullscreen() {
        if (fullscreen.value) {
            fullscreen.value = false
            lockPortrait.value = true
        }
    }

    fun collapse() {
        expanded.value = false
        if (fullscreen.value) exitFullscreen()
    }
}
