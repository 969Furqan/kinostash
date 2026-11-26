package app.kinostash.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.kinostash.android.App
import app.kinostash.android.data.MetaDetail

/** Metadata shown on the detail screen, kept so the streams screen can label what it plays. */
object MetaCache {
    private val map = mutableMapOf<String, MetaDetail>()
    fun put(m: MetaDetail) { map["${m.type}/${m.id}"] = m }
    fun get(type: String, id: String) = map["$type/$id"]
}

@Composable
fun DetailScreen(type: String, id: String, onBack: () -> Unit, onPickVideo: (videoId: String) -> Unit) {
    var meta by remember { mutableStateOf(MetaCache.get(type, id)) }
    var failed by remember { mutableStateOf(false) }
    val history by App.instance.history.entries.collectAsState()
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
        mutableStateOf(m.videos.find { it.id == lastWatched?.videoId }?.season ?: seasons.firstOrNull() ?: 1)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                AsyncImage(m.background ?: m.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg))))
                IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOfNotNull(m.releaseInfo, m.runtime, m.imdbRating?.let { "★ $it" }, m.genres.take(3).joinToString(", ").ifBlank { null })
                        .joinToString("  ·  "),
                    color = Muted, fontSize = 13.sp,
                )
                val library by App.instance.library.items.collectAsState()
                val inLibrary = library.any { it.id == m.id }
                OutlinedButton(onClick = {
                    if (inLibrary) App.instance.library.remove(m.id)
                    else App.instance.library.add(app.kinostash.android.data.LibraryItem(m.id, type, m.name, m.poster, System.currentTimeMillis()))
                }) {
                    Icon(if (inLibrary) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (inLibrary) "In library" else "Add to library")
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
                if (!isSeries) {
                    Button(
                        onClick = { onPickVideo(m.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (lastWatched != null && !lastWatched.finished) "Resume · choose stream" else "Watch · choose stream")
                    }
                }
            }
        }
        if (isSeries) {
            if (seasons.size > 1) item {
                LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s ->
                        FilterChip(
                            selected = s == season, onClick = { season = s },
                            label = { Text(if (s == 0) "Specials" else "Season $s") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim),
                        )
                    }
                }
            }
            items(m.videos.filter { it.season == season || seasons.isEmpty() }, key = { it.id }) { v ->
                val watched = history.find { it.videoId == v.id }
                Row(
                    Modifier.fillMaxWidth().clickable { onPickVideo(v.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(Surface2)) {
                        AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (watched != null) LinearProgressIndicator(
                            progress = { if (watched.finished) 1f else watched.progress },
                            modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter), color = Accent,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${v.episode ?: ""}. ${v.title}", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        v.releasedAt?.let { Text(formatRelease(it), fontSize = 12.sp, color = if (it > System.currentTimeMillis()) Accent else Muted) }
                    }
                }
            }
        }
    }
}
