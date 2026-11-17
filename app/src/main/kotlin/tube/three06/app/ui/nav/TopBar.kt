package tube.three06.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tube.three06.app.ui.common.Avatar
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalNavigator

interface ShellActions {
    fun openAccounts()
    fun openCast()
    val casting: Boolean
}

val LocalShell = staticCompositionLocalOf<ShellActions> { error("ShellActions not provided") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TubeTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    showBack: Boolean = false,
    showSearch: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val shell = LocalShell.current
    val account by graph.accounts.active.collectAsStateWithLifecycle()
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (showBack) IconButton(onClick = { navigator.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = {
            actions()
            IconButton(onClick = { shell.openCast() }) {
                Icon(if (shell.casting) Icons.Default.CastConnected else Icons.Default.Cast, "Cast")
            }
            if (showSearch) IconButton(onClick = { navigator.openSearch() }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { navigator.openSettings() }) { Icon(Icons.Default.Settings, "Settings") }
            IconButton(onClick = { shell.openAccounts() }) {
                val avatar = account?.avatarUrl
                if (avatar != null) {
                    Avatar(avatar, 28.dp)
                } else {
                    Icon(Icons.Default.AccountCircle, "Account")
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
