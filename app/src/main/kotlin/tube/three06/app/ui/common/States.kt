package tube.three06.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
fun ErrorBox(
    message: String,
    modifier: Modifier = Modifier.fillMaxSize(),
    onRetry: (() -> Unit)? = null,
) {
    MessageBox(
        icon = Icons.Outlined.CloudOff,
        title = "Something went wrong",
        message = message,
        modifier = modifier,
        action = onRetry?.let { "Retry" to it },
    )
}

fun Context.isOffline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
    // a network can be up with no working internet behind it; VALIDATED is the system's own probe
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
fun OfflineBox(onRetry: () -> Unit, modifier: Modifier = Modifier.fillMaxSize()) {
    val navigator = LocalNavigator.current
    MessageBox(
        icon = Icons.Outlined.CloudOff,
        title = "You're offline",
        message = "Check your connection, or watch something you've downloaded.",
        modifier = modifier,
        action = "Refresh" to onRetry,
        secondary = "Downloads" to navigator::openDownloads,
    )
}

@Composable
fun EmptyBox(
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: Pair<String, () -> Unit>? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
) {
    MessageBox(icon, title, message, modifier, action)
}

@Composable
private fun MessageBox(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier,
    action: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (message != null) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = action.second) { Text(action.first) }
            }
            if (secondary != null) {
                OutlinedButton(onClick = secondary.second) { Text(secondary.first) }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        trailing?.invoke()
    }
}
