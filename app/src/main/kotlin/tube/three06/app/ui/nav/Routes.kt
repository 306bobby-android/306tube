package tube.three06.app.ui.nav

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val HOME = "home"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SEARCH = "search?q={q}"
    const val LIBRARY = "library"
    const val CHANNEL = "channel/{id}"
    const val PLAYLIST = "playlist/{id}"
    const val LOCAL_PLAYLIST = "localPlaylist/{id}"
    const val HISTORY = "history"
    const val DOWNLOADS = "downloads"
    const val LOGIN = "login"
    const val SETTINGS = "settings"

    fun search(query: String? = null): String =
        if (query.isNullOrBlank()) "search" else "search?q=" + Uri.encode(query)

    fun channel(id: String): String = "channel/" + Uri.encode(id)

    fun playlist(id: String): String = "playlist/" + Uri.encode(id)

    fun localPlaylist(id: Long): String = "localPlaylist/$id"
}

enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home),
    Subscriptions(Routes.SUBSCRIPTIONS, "Subscriptions", Icons.Outlined.Subscriptions, Icons.Filled.Subscriptions),
    Search("search", "Search", Icons.Outlined.Search, Icons.Filled.Search),
    Library(Routes.LIBRARY, "Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
}
