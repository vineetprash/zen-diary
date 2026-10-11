package app.zendiary.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zendiary.data.EncryptedImage
import coil.compose.AsyncImage

@Composable
fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val shape = RoundedCornerShape(24.dp)
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors) { Column(Modifier.padding(16.dp), content = content) }
    else Card(modifier = modifier, shape = shape, colors = colors) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = modifier.padding(bottom = 10.dp))

@Composable
fun ZenScaffold(
    topBar: @Composable () -> Unit = {},
    fab: @Composable () -> Unit = {},
    hasBottomBar: Boolean = false,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        floatingActionButton = fab,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (hasBottomBar) WindowInsets.statusBars else WindowInsets.systemBars.union(WindowInsets.ime),
        content = content,
    )
}

@Composable
fun zenTopBarColors() = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)

@Composable
fun DiaryImage(name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    AsyncImage(model = EncryptedImage(name), contentDescription = "Attached photo", modifier = modifier, contentScale = scale)
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun MonthHeader(label: String, onPrev: () -> Unit, onNext: () -> Unit, nextEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onPrev) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge)
        IconButton(onNext, enabled = nextEnabled) { Icon(Icons.Rounded.ChevronRight, "Next month") }
    }
}

@Composable
fun Dot(color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 8.dp) =
    Box(Modifier.size(size).clip(CircleShape).background(color))
