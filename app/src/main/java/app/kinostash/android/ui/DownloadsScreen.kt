package app.kinostash.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.kinostash.android.downloads.DownloadItem
import app.kinostash.android.downloads.DownloadStatus
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.downloads.formatBytes
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import app.kinostash.android.player.PlayerActivity

/** Downloads of one series or movie. */
private data class DownloadGroup(val key: String, val name: String, val poster: String?, val items: List<DownloadItem>) {
    val isSeries get() = items.any { it.season != null || it.metaType == "series" }
}

/**
 * The "Downloads" tab. Top level: one card per series/movie. Tapping a card shows that title's
 * downloads (episodes grouped by season), with progress, pause/resume/delete and offline playback.
 */
@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val onPlay: (DownloadItem) -> Unit = { PlayerActivity.startDownload(ctx, it) }
    val items by OfflineDownloads.store.items.collectAsState()
    val wifiOnly by OfflineDownloads.wifiOnly.collectAsState()
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<List<DownloadItem>?>(null) }

    val groups = remember(items) {
        items.groupBy { it.groupKey }.map { (key, list) ->
            val newest = list.maxBy { it.addedAt }
            DownloadGroup(key, newest.groupName, list.firstNotNullOfOrNull { it.poster }, list)
        }.sortedByDescending { g -> g.items.maxOf { it.addedAt } }
    }
    val open = groups.find { it.key == openKey }
    LaunchedEffect(open == null, openKey) { if (open == null && openKey != null) openKey = null } // group emptied
    BackHandler(enabled = open != null) { openKey = null }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (open == null) {
            val completed = items.filter { it.status == DownloadStatus.COMPLETED }
            Text("Downloads", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "${completed.size} available offline · ${formatBytes(completed.sumOf { it.bytesTotal })}",
                color = Muted, fontSize = 13.sp,
            )
            WifiOnlyCard(wifiOnly) { OfflineDownloads.setWifiOnly(ctx, it) }
            if (groups.isEmpty()) {
                Text(
                    "Nothing downloaded yet.\n\nOpen a movie or episode, and in the stream list tap the download " +
                        "button next to a stream. Downloads keep going in the background and can be watched with no internet.",
                    color = Muted, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            groups.forEach { g -> GroupCard(g) { openKey = g.key } }
        } else {
            GroupHeader(open, onBack = { openKey = null }, onDeleteAll = { confirmDelete = open.items })
            val sorted = open.items.sortedWith(
                compareBy<DownloadItem>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }, { it.addedAt }),
            )
            var lastSeason: Int? = -1
            sorted.forEach { item ->
                if (open.isSeries && item.season != lastSeason) {
                    lastSeason = item.season
                    Text(
                        item.season?.let { if (it == 0) "Specials" else "Season $it" } ?: "Other",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                DownloadRow(
                    item = item,
                    label = if (open.isSeries) episodeLabel(item) else item.title,
                    onPlay = { onPlay(item) },
                    onPause = { OfflineDownloads.pause(ctx, item.id) },
                    onResume = { OfflineDownloads.resume(ctx, item.id) },
                    onDelete = { confirmDelete = listOf(item) },
                )
            }
        }
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(if (list.size == 1) "Delete download?" else "Delete ${list.size} downloads?") },
            text = {
                Text(
                    if (list.size == 1) "\"${list.first().title}\" will be removed from your phone."
                    else "All downloads of \"${list.first().groupName}\" will be removed from your phone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { list.forEach { OfflineDownloads.delete(ctx, it.id) }; confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WifiOnlyCard(checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Download on Wi-Fi only", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Pauses on mobile data and continues automatically on Wi-Fi.", color = Muted, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent))
        }
    }
}

@Composable
private fun Poster(url: String?, width: Int = 54, height: Int = 80) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.width(width.dp).height(height.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF202033)),
    )
}

@Composable
private fun GroupCard(g: DownloadGroup, onClick: () -> Unit) {
    val ready = g.items.count { it.status == DownloadStatus.COMPLETED }
    val active = g.items.filter { it.status.isActive }
    val failed = g.items.count { it.status == DownloadStatus.FAILED }
    val summary = buildList {
        add(if (g.isSeries) "${g.items.size} episode${if (g.items.size == 1) "" else "s"}" else "Movie")
        if (ready > 0) add(if (g.isSeries) "$ready ready" else "ready to watch")
        if (active.isNotEmpty()) {
            val done = active.sumOf { it.bytesDone }
            val total = active.sumOf { it.bytesTotal }
            add(if (total > 0) "downloading ${(done * 100 / total).toInt()}%" else "downloading")
        }
        if (g.items.any { it.status == DownloadStatus.PAUSED }) add("paused")
        if (failed > 0) add("$failed failed")
    }.joinToString(" · ")
    val size = g.items.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.bytesTotal }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(g.poster)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(summary, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (size > 0) Text(formatBytes(size) + " on phone", color = Muted, fontSize = 12.sp)
                if (active.isNotEmpty()) {
                    val total = active.sumOf { it.bytesTotal }
                    Spacer(Modifier.height(6.dp))
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (active.sumOf { it.bytesDone }.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = Accent,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    }
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open", tint = Muted)
        }
    }
}

@Composable
private fun GroupHeader(g: DownloadGroup, onBack: () -> Unit, onDeleteAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
        Poster(g.poster, width = 40, height = 60)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${g.items.size} download${if (g.items.size == 1) "" else "s"} · " +
                    formatBytes(g.items.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.bytesTotal }) + " on phone",
                color = Muted, fontSize = 12.sp,
            )
        }
        IconButton(onClick = onDeleteAll) { Icon(Icons.Outlined.Delete, "Delete all", tint = Muted) }
    }
}

private fun episodeLabel(i: DownloadItem): String = when {
    i.episode != null -> "E${i.episode}" + (i.episodeTitle?.let { " · $it" } ?: "")
    else -> i.title.substringAfter(" · ", i.title)
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    label: String,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val done = item.status == DownloadStatus.COMPLETED
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Surface1)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = done, onClick = onPlay).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotBlank()) {
                    Text(item.subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                if (!done) {
                    if (item.bytesTotal <= 0 && item.status == DownloadStatus.DOWNLOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
                    } else {
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    statusLine(item),
                    color = if (item.status == DownloadStatus.FAILED) Color(0xFFFF8A80) else Muted,
                    fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (done) {
                    IconButton(onClick = onPlay) { Icon(Icons.Outlined.PlayArrow, "Play", tint = Color.White) }
                } else if (item.status.isActive) {
                    IconButton(onClick = onPause) { Icon(Icons.Outlined.Pause, "Pause", tint = Color.White) }
                } else {
                    IconButton(onClick = onResume) { Icon(Icons.Outlined.Refresh, "Resume", tint = Color.White) }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete", tint = Muted, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

private fun peersLabel(i: DownloadItem): String = if (i.peers >= 0) "${i.peers} peers" else ""

private fun statusLine(i: DownloadItem): String {
    val size = if (i.bytesTotal > 0) "${formatBytes(i.bytesDone)} / ${formatBytes(i.bytesTotal)}" else formatBytes(i.bytesDone)
    val pct = "${(i.progress * 100).toInt()}%"
    return when (i.status) {
        DownloadStatus.QUEUED -> listOfNotNull("Queued", size.takeIf { i.bytesDone > 0 }, i.error).joinToString(" · ")
        DownloadStatus.DOWNLOADING -> when {
            i.bytesDone > 0 -> listOf(pct, size, "${formatBytes(i.speed)}/s", peersLabel(i)).filter { it.isNotEmpty() }.joinToString(" · ")
            i.error != null -> i.error
            i.peers > 0 -> "Connecting · ${i.peers} peers · ${formatBytes(i.torrentSpeed)}/s"
            i.peers == 0 -> "Searching for peers…"
            else -> "Starting…"
        }
        DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi · $pct"
        DownloadStatus.PAUSED -> listOfNotNull("Paused · $pct", i.error).joinToString(" · ")
        DownloadStatus.COMPLETED -> "Available offline · ${formatBytes(i.bytesTotal)}" +
            if (i.positionMs > 0) " · resume at ${i.positionMs / 60_000} min" else ""
        DownloadStatus.FAILED -> "Failed: ${i.error ?: "unknown error"} · tap ↻ to retry"
    }
}
