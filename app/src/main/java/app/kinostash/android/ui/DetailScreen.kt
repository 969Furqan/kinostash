package app.kinostash.android.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.kinostash.android.App
import app.kinostash.android.data.LibraryItem
import app.kinostash.android.data.MetaDetail
import app.kinostash.android.data.Video
import app.kinostash.android.downloads.OfflineDownloads
import kotlinx.coroutines.launch

/** Metadata shown on the detail screen, kept so the streams screen can label what it plays. */
object MetaCache {
    private val map = mutableMapOf<String, MetaDetail>()
    fun put(m: MetaDetail) { map["${m.type}/${m.id}"] = m }
    fun get(type: String, id: String) = map["$type/$id"]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(type: String, id: String, onBack: () -> Unit, onPickVideo: (videoId: String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(MetaCache.get(type, id)) }
    var failed by remember { mutableStateOf(false) }
    val history by App.instance.history.entries.collectAsState()
    val downloads by OfflineDownloads.store.items.collectAsState()
    val library by App.instance.library.items.collectAsState()
    var episodeMenu by remember { mutableStateOf<Video?>(null) }
    var titleMenu by remember { mutableStateOf(false) }
    var seasonMenu by remember { mutableStateOf(false) }
    var categories by remember { mutableStateOf(false) }

    LaunchedEffect(type, id) {
        if (meta == null) {
            val m = App.instance.addons.meta(type, id)
            if (m != null) { MetaCache.put(m); meta = m } else failed = true
        }
    }
    val m = meta
    if (m == null) { if (failed) Message("Couldn't load details") else Loading(); return }

    val isSeries = m.videos.any { it.season != null } || type == "series"
    val seasons = remember(m) { m.videos.mapNotNull { it.season }.distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it } }
    val lastWatched = history.filter { it.metaId == m.id }.maxByOrNull { it.updatedAt }
    var season by rememberSaveable(m.id) {
        mutableStateOf(m.videos.find { it.id == lastWatched?.videoId }?.season ?: seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1)
    }
    val inLibrary = library.any { it.id == m.id }
    val libItem = LibraryItem(m.id, type, m.name, m.poster, System.currentTimeMillis())
    fun open(videoId: String) { if (!TitleActions.playIfDownloaded(ctx, videoId)) onPickVideo(videoId) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                AsyncImage(m.background ?: m.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
                IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
                }
                IconButton(onClick = { titleMenu = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Outlined.MoreVert, "More", tint = Color.White)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { titleMenu = true }))
                Text(
                    listOfNotNull(m.releaseInfo, m.runtime, m.imdbRating?.let { "★ $it IMDb" }, m.genres.take(3).joinToString(", ").ifBlank { null })
                        .joinToString("  ·  "),
                    color = Muted, fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (inLibrary) App.instance.library.remove(m.id) else App.instance.library.add(libItem) }) {
                        Icon(if (inLibrary) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (inLibrary) "In library" else "Add to library")
                    }
                    OutlinedButton(onClick = { categories = true }) {
                        Icon(Icons.Outlined.Label, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Categories")
                    }
                }
                if (isSeries) {
                    val now = System.currentTimeMillis()
                    m.videos.firstOrNull { (it.releasedAt ?: 0) > now && (it.season ?: 1) > 0 }?.let { next ->
                        Text(
                            "Next episode: S${next.season}E${next.episode} · ${dayLabel(next.releasedAt!!)} · ${formatRelease(next.releasedAt!!)}",
                            color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    } ?: m.status?.let { Text("Status: $it", color = Muted, fontSize = 13.sp) }
                }
                m.description?.let { Text(it, fontSize = 14.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (m.director.isNotEmpty()) Text("Director: " + m.director.take(3).joinToString(", "), color = Muted, fontSize = 13.sp)
                if (m.cast.isNotEmpty()) Text("Cast: " + m.cast.take(6).joinToString(", "), color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!isSeries) {
            // The movie itself as an entry: progress, watched, downloaded, play/download.
            item {
                val d = TitleActions.downloaded(m.id, downloads)
                val busy = TitleActions.downloading(m.id, downloads)
                val h = history.find { it.videoId == m.id }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { open(m.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                d != null -> "Play downloaded copy"
                                h != null && !h.finished && h.positionMs > 30_000 -> "Resume · choose stream"
                                else -> "Watch · choose stream"
                            },
                        )
                    }
                    EntryRow(
                        thumbnail = m.background ?: m.poster,
                        title = m.name,
                        subtitle = listOfNotNull(m.runtime, m.releaseInfo).joinToString(" · ").ifBlank { null },
                        progress = h?.let { if (it.finished) 1f else it.progress },
                        watched = h?.finished == true,
                        downloaded = d != null,
                        downloading = busy?.progress,
                        onClick = { open(m.id) },
                        onLongClick = { titleMenu = true },
                        trailing = {
                            if (d == null && busy == null) IconButton(onClick = { scope.launch { toast(TitleActions.autoDownload(ctx, m, null)) } }) {
                                Icon(Icons.Outlined.Download, "Download best quality", tint = Accent)
                            }
                        },
                    )
                }
            }
        }
        if (isSeries) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(seasons) { s ->
                            FilterChip(
                                selected = s == season, onClick = { season = s },
                                label = { Text(if (s == 0) "Specials" else "Season $s") },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim),
                            )
                        }
                    }
                    IconButton(onClick = { seasonMenu = true }) { Icon(Icons.Outlined.MoreVert, "Season options") }
                }
            }
            items(m.videos.filter { it.season == season || seasons.isEmpty() }, key = { it.id }) { v ->
                val h = history.find { it.videoId == v.id }
                val d = TitleActions.downloaded(v.id, downloads)
                val busy = TitleActions.downloading(v.id, downloads)
                val future = (v.releasedAt ?: 0) > System.currentTimeMillis()
                Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                    EntryRow(
                        thumbnail = v.thumbnail,
                        title = "${v.episode ?: ""}. ${v.title}",
                        subtitle = v.releasedAt?.let { formatRelease(it) },
                        subtitleAccent = future,
                        progress = h?.let { if (it.finished) 1f else it.progress },
                        watched = h?.finished == true,
                        downloaded = d != null,
                        downloading = busy?.progress,
                        onClick = { open(v.id) },
                        onLongClick = { episodeMenu = v },
                    )
                }
            }
        }
    }

    if (categories) CategoryDialog(libItem) { categories = false }
    if (titleMenu) TitleMenu(type, m.id, m.name, m.poster) { titleMenu = false }
    if (seasonMenu) {
        val eps = m.videos.filter { it.season == season }
        val aired = eps.filter { (it.releasedAt ?: 0) <= System.currentTimeMillis() }
        AlertDialog(
            onDismissRequest = { seasonMenu = false },
            title = { Text(if (season == 0) "Specials" else "Season $season") },
            text = {
                Column {
                    MenuItem("Mark season as watched") { TitleActions.setWatched(m, aired, true); seasonMenu = false }
                    MenuItem("Mark season as not watched") { TitleActions.setWatched(m, eps, false); seasonMenu = false }
                    MenuItem("Download season (${App.instance.settings.quality.label.lowercase()})") {
                        seasonMenu = false
                        scope.launch {
                            var queued = 0
                            for (v in aired) if (TitleActions.autoDownload(ctx, m, v).startsWith("Downloading")) queued++
                            toast("Queued $queued episode${if (queued == 1) "" else "s"}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { seasonMenu = false }) { Text("Close") } },
        )
    }
    episodeMenu?.let { v ->
        val watched = history.find { it.videoId == v.id }?.finished == true
        val d = TitleActions.downloaded(v.id, downloads)
        AlertDialog(
            onDismissRequest = { episodeMenu = null },
            title = { Text(TitleActions.label(v)) },
            text = {
                Column {
                    if (d != null) MenuItem("Play downloaded copy") { episodeMenu = null; TitleActions.playIfDownloaded(ctx, v.id) }
                    MenuItem("Choose a stream") { episodeMenu = null; onPickVideo(v.id) }
                    MenuItem(if (watched) "Mark as not watched" else "Mark as watched") { TitleActions.setWatched(m, listOf(v), !watched); episodeMenu = null }
                    MenuItem("Mark all before this as watched") {
                        val idx = m.videos.indexOf(v)
                        TitleActions.setWatched(m, m.videos.take(idx + 1).filter { (it.season ?: 1) > 0 }, true); episodeMenu = null
                    }
                    if (d == null) MenuItem("Download (${App.instance.settings.quality.label.lowercase()})") {
                        episodeMenu = null; scope.launch { toast(TitleActions.autoDownload(ctx, m, v)) }
                    } else MenuItem("Delete download") { OfflineDownloads.delete(ctx, d.id); episodeMenu = null }
                }
            },
            confirmButton = { TextButton(onClick = { episodeMenu = null }) { Text("Close") } },
        )
    }
}

/** One playable entry (episode or movie): thumbnail, progress, watched / downloaded state. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(
    thumbnail: String?,
    title: String,
    subtitle: String?,
    progress: Float?,
    watched: Boolean,
    downloaded: Boolean,
    downloading: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    subtitleAccent: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Surface2)) {
            AsyncImage(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
                if (downloaded) Badge(Icons.Outlined.DownloadDone, "Downloaded")
                if (watched) Badge(Icons.Outlined.Check, "Watched")
            }
            if (progress != null && progress > 0f) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter), color = Accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (watched) Muted else Color.Unspecified)
            subtitle?.let { Text(it, fontSize = 12.sp, color = if (subtitleAccent) Accent else Muted) }
            when {
                downloaded -> Text("Downloaded · plays offline", fontSize = 11.sp, color = Accent)
                downloading != null -> Text("Downloading ${(downloading * 100).toInt()}%", fontSize = 11.sp, color = Accent)
            }
        }
        trailing()
        if (downloaded) Icon(Icons.Outlined.PlayArrow, null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}
