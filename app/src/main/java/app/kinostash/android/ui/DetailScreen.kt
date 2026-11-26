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
import androidx.compose.runtime.mutableIntStateOf
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
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloadMenu by remember { mutableStateOf(false) }
    var nextCountDialog by remember { mutableStateOf(false) }
    val preparing by TitleActions.preparing.collectAsState()

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
    val selecting = selected.isNotEmpty()
    fun toggle(v: Video) { selected = if (v.id in selected) selected - v.id else selected + v.id }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    val seasonEpisodes = m.videos.filter { it.season == season || seasons.isEmpty() }
    fun airedOf(list: List<Video>) = list.filter { (it.releasedAt ?: 0) <= System.currentTimeMillis() }
    fun watched(v: Video) = history.find { it.videoId == v.id }?.finished == true
    /** Aired, unwatched episodes not downloaded or queued yet, in viewing order (specials last). */
    fun unwatchedOf(list: List<Video>) = airedOf(list)
        .filter { !watched(it) && TitleActions.downloaded(it.id, downloads) == null && TitleActions.downloading(it.id, downloads) == null }
        .sortedWith(compareBy({ if ((it.season ?: 1) == 0) Int.MAX_VALUE else it.season ?: 1 }, { it.episode ?: 0 }))
    /** Unwatched episodes from where you are in the series onwards (after your last watched episode). */
    fun nextUnwatched(): List<Video> {
        val ordered = m.videos.filter { (it.season ?: 1) > 0 }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        val lastWatchedIdx = ordered.indexOfLast { watched(it) }
        return unwatchedOf(ordered.drop(lastWatchedIdx + 1))
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (selecting) 96.dp else 24.dp)) {
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
                    Box {
                        IconButton(onClick = { downloadMenu = true }) { Icon(Icons.Outlined.Download, "Download episodes", tint = Accent) }
                        androidx.compose.material3.DropdownMenu(downloadMenu, { downloadMenu = false }) {
                            val label = if (season == 0) "specials" else "season $season"
                            val unwatchedHere = unwatchedOf(seasonEpisodes)
                            val next = nextUnwatched()
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Next unwatched episodes…" + if (next.isEmpty()) " (none left)" else "") },
                                enabled = next.isNotEmpty(),
                                onClick = { downloadMenu = false; nextCountDialog = true },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Unwatched in $label (${unwatchedHere.size})") },
                                enabled = unwatchedHere.isNotEmpty(),
                                onClick = { downloadMenu = false; TitleActions.queueDownloads(ctx, m, unwatchedHere) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Whole $label (${airedOf(seasonEpisodes).size})") },
                                onClick = { downloadMenu = false; TitleActions.queueDownloads(ctx, m, airedOf(seasonEpisodes)) },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Select episodes…") },
                                onClick = { downloadMenu = false; selected = airedOf(seasonEpisodes).take(1).map { it.id }.toSet() },
                            )
                        }
                    }
                    IconButton(onClick = { seasonMenu = true }) { Icon(Icons.Outlined.MoreVert, "Season options") }
                }
            }
            if (!selecting) item {
                Text(
                    "Long-press an episode for options, or choose Select to download several at once.",
                    color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            items(seasonEpisodes, key = { it.id }) { v ->
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
                        downloading = busy?.progress ?: if (v.id in preparing) 0f else null,
                        onClick = { if (selecting) toggle(v) else open(v.id) },
                        onLongClick = { if (selecting) toggle(v) else episodeMenu = v },
                        trailing = {
                            if (selecting) androidx.compose.material3.Checkbox(
                                checked = v.id in selected, onCheckedChange = { toggle(v) },
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Accent),
                            )
                        },
                    )
                }
            }
        }
    }

    // Multi-select bar: pick episodes (across seasons too) and queue them all.
    if (selecting) {
        androidx.compose.material3.Surface(
            color = Surface2, shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val airedHere = airedOf(seasonEpisodes); val allHere = airedHere.isNotEmpty() && airedHere.all { it.id in selected }
                TextButton(onClick = {
                    selected = if (allHere) selected - seasonEpisodes.map { it.id }.toSet() else selected + airedHere.map { it.id }
                }) { Text(if (allHere) "Clear season" else "Select season") }
                TextButton(onClick = { selected = emptySet() }) { Text("Cancel", color = Muted) }
                androidx.compose.material3.Button(
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
                    onClick = {
                        TitleActions.queueDownloads(ctx, m, m.videos.filter { it.id in selected })
                        selected = emptySet()
                    },
                ) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(4.dp)); Text("Download") }
            }
        }
    }
    }

    if (categories) CategoryDialog(libItem) { categories = false }
    if (nextCountDialog) {
        val next = nextUnwatched()
        var count by remember { mutableIntStateOf(minOf(3, next.size).coerceAtLeast(1)) }
        AlertDialog(
            onDismissRequest = { nextCountDialog = false },
            title = { Text("Download next unwatched") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Continues after your last watched episode, across seasons. ${next.size} available.", color = Muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                        TextButton(enabled = count > 1, onClick = { count-- }) { Text("−", fontSize = 24.sp) }
                        Text("$count", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                        TextButton(enabled = count < next.size, onClick = { count++ }) { Text("+", fontSize = 24.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 3, 5, 10).filter { it <= next.size }.forEach { n ->
                            FilterChip(count == n, { count = n }, { Text("$n") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
                        }
                        FilterChip(count == next.size, { count = next.size }, { Text("All") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
                    }
                    next.take(count).let { picked ->
                        Text(
                            "${TitleActions.label(picked.first()).substringBefore(" · ")} → ${TitleActions.label(picked.last()).substringBefore(" · ")}",
                            fontSize = 13.sp, color = Accent,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { nextCountDialog = false; TitleActions.queueDownloads(ctx, m, next.take(count)) }) {
                    Text("Download $count", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { nextCountDialog = false }) { Text("Cancel") } },
        )
    }
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
                    MenuItem("Download season (${aired.size} episodes)") {
                        seasonMenu = false; TitleActions.queueDownloads(ctx, m, aired)
                    }
                    MenuItem("Download unwatched in this season") {
                        seasonMenu = false; TitleActions.queueDownloads(ctx, m, unwatchedOf(eps))
                    }
                    MenuItem("Download next unwatched…") { seasonMenu = false; nextCountDialog = true }
                    MenuItem("Download all seasons") {
                        seasonMenu = false; TitleActions.queueDownloads(ctx, m, TitleActions.aired(m))
                    }
                    MenuItem("Select episodes…") { seasonMenu = false; selected = aired.take(1).map { it.id }.toSet() }
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
                    MenuItem("Select (download several)") { selected = setOf(v.id); episodeMenu = null }
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
