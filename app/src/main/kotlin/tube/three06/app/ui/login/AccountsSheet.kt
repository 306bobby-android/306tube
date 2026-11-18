package tube.three06.app.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tube.three06.app.ui.common.Avatar
import tube.three06.app.ui.common.LocalGraph
import tube.three06.app.ui.common.LocalMessages
import tube.three06.app.ui.common.LocalNavigator
import tube.three06.app.ui.common.TubeSheet

@Composable
fun AccountsSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val nav = LocalNavigator.current
    val message = LocalMessages.current
    val accounts by graph.accounts.accounts.collectAsStateWithLifecycle(emptyList())
    val active by graph.accounts.active.collectAsStateWithLifecycle()
    val scope = graph.scope

    // On the app scope so the refresh outlives the sheet.
    LaunchedEffect(Unit) {
        scope.launch { graph.accounts.refreshIdentities(graph.youTube) }
    }

    TubeSheet(onDismiss) {
        // Scrollable so a long list of brand channels stays reachable; moving focus scrolls it.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Accounts", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            if (accounts.isEmpty()) {
                Text(
                    "Sign in to get your own recommendations, subscriptions and history from YouTube. " +
                        "Without an account the app builds a feed on this device.",
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            for (a in accounts) {
                val brand = a.dataSyncId != null
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = {
                        val line = when {
                            brand && a.email != null -> "Brand channel · ${a.email}"
                            brand -> "Brand channel"
                            else -> a.email
                        }
                        line?.let { Text(it) }
                    },
                    leadingContent = { Avatar(a.avatarUrl, if (brand) 32.dp else 40.dp) },
                    trailingContent = {
                        Row {
                            if (active?.id == a.id) Icon(Icons.Default.Check, "Active", Modifier.padding(12.dp))
                            if (!brand) {
                                IconButton(onClick = {
                                    scope.launch {
                                        graph.accounts.signOut(a.id)
                                        message("Signed out ${a.name}")
                                    }
                                }) { Icon(Icons.AutoMirrored.Filled.Logout, "Sign out") }
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        scope.launch { graph.accounts.switchTo(a.id) }
                        onDismiss()
                    },
                )
            }
            ListItem(
                headlineContent = { Text(if (accounts.isEmpty()) "Sign in" else "Add account") },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable { onDismiss(); nav.openLogin() },
            )
            ListItem(
                headlineContent = { Text("Settings") },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                modifier = Modifier.clickable { onDismiss(); nav.openSettings() },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
