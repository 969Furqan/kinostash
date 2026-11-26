package app.kinostash.android.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinostash.android.App
import app.kinostash.android.data.StreamItem
import app.kinostash.android.downloads.DownloadMeta
import app.kinostash.android.downloads.DownloadStatus
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.player.PlayerActivity
import app.kinostash.android.player.PlayRequest

private val QUALITY_ORDER = listOf("2160p", "1080p", "720p", "480p")

@Composable
fun StreamsScreen(type: String, metaId: String, videoId: String, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val ctx = LocalContext.current
    val meta = MetaCache.get(type, metaId)
    val video = meta?.videos?.find { it.id == videoId }
    val label = video?.let { v -> listOfNotNull(v.season?.let { s -> v.episode?.let { "S${s}E$it" } }, v.title).joinToString(" · ") }
    val title = listOfNotNull(meta?.name, label).joinToString(" · ").ifBlank { videoId }

    var streams by remember { mutableStateOf<List<StreamItem>?>(null) }
    var quality by remember { mutableStateOf<String?>(null) }
    val downloads by OfflineDownloads.store.items.collectAsState()
    LaunchedEffect(type, videoId) {
        streams = App.instance.addons.streams(type, videoId).filter { it.isPlayable }
            .sortedWith(compareBy<StreamItem>({ QUALITY_ORDER.indexOf(it.quality).let { i -> if (i < 0) 99 else i } }, { -(it.seeders ?: 0) }))
    }

    fun request(s: StreamItem) = PlayRequest(
        stream = s, title = title, metaType = type, metaId = metaId, videoId = videoId,
        metaName = meta?.name ?: title, poster = meta?.poster, label = label,
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Tap to watch · ⬇ to download for offline", fontSize = 12.sp, color = Muted)
            }
        }
        val list = streams
        if (list == null) { Loading(); return@Column }
        if (list.isEmpty()) { Message("No streams found. Add a stream addon (e.g. Torrentio) in Settings."); return@Column }
        val qualities = QUALITY_ORDER.filter { q -> list.any { it.quality == q } }
        if (qualities.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(quality == null, { quality = null }, { Text("All") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim)) }
                items(qualities) { q ->
                    FilterChip(quality == q, { quality = q }, { Text(q) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.filter { quality == null || it.quality == quality }) { s ->
                val downloaded = OfflineDownloads.completedFileFor(s) != null
                val inQueue = !downloaded && downloads.any { d -> d.infoHash == s.infoHash && d.fileIdx == s.fileIdx && s.infoHash != null && d.status != DownloadStatus.FAILED }
                Card(colors = CardDefaults.cardColors(containerColor = Surface1)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { PlayerActivity.start(ctx, request(s)) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = Accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(s.quality?.uppercase(), s.addonName).joinToString(" · ") + (if (downloaded) " · on phone" else ""),
                                fontSize = 12.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                            )
                            Text(s.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(s.description, fontSize = 12.sp, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(
                            enabled = !downloaded && !inQueue,
                            onClick = {
                                OfflineDownloads.enqueue(
                                    ctx, s,
                                    DownloadMeta(
                                        title = title, poster = meta?.poster, metaType = type, metaId = metaId, videoId = videoId,
                                        showName = meta?.name ?: title, season = video?.season, episode = video?.episode,
                                        episodeTitle = video?.title,
                                    ),
                                )
                                Toast.makeText(ctx, "Added to Downloads", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(if (downloaded || inQueue) Icons.Outlined.DownloadDone else Icons.Outlined.Download, "Download")
                        }
                    }
                }
            }
        }
    }
}
