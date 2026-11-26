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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.kinostash.android.App
import app.kinostash.android.data.EpisodeRelease
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LibTab(val label: String) { LIBRARY("Library"), UPCOMING("Upcoming"), NEW("New episodes") }

/** Your saved titles, the release calendar of your series, and recently released episodes. */
@Composable
fun LibraryScreen(onOpen: (type: String, id: String) -> Unit) {
    val items by App.instance.library.items.collectAsState()
    val releases by App.instance.upcoming.releases.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibTab.LIBRARY) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(items.map { it.id }.toSet()) {
        App.instance.upcoming.reindex()
        refreshing = true
        runCatching { App.instance.upcoming.refresh() }
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        Text("Library", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibTab.entries.forEach { t ->
                FilterChip(t == tab, { tab = t }, { Text(t.label) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            LibTab.LIBRARY -> {
                if (items.isEmpty()) { Message("Your library is empty.\nOpen a movie or series and tap \"Add to library\"."); return@Column }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "All", "movie" to "Movies", "series" to "Series").forEach { (v, l) ->
                        FilterChip(filter == v, { filter = v }, { Text(l) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
                    }
                }
                val now = System.currentTimeMillis()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items.filter { filter == null || it.type == filter }, key = { it.id }) { item ->
                        val next = releases.firstOrNull { it.metaId == item.id && it.releasedAt > now }
                        Poster(item.poster, item.name, next?.let { "Next: ${it.code} ${dayLabel(it.releasedAt)}" }, width = 108.dp) {
                            onOpen(item.type, item.id)
                        }
                    }
                }
            }
            LibTab.UPCOMING -> {
                val now = System.currentTimeMillis()
                val list = releases.filter { it.releasedAt > now }
                if (list.isEmpty()) {
                    Message(
                        if (refreshing) "Checking release dates…"
                        else if (items.none { it.type == "series" }) "Add ongoing series to your library to see their upcoming episodes here."
                        else "No upcoming episodes announced for the series in your library.",
                    )
                    return@Column
                }
                ReleaseList(list, onOpen)
            }
            LibTab.NEW -> {
                val list = App.instance.upcoming.recent(days = 14)
                if (list.isEmpty()) { Message(if (refreshing) "Checking…" else "No new episodes in the last two weeks."); return@Column }
                ReleaseList(list, onOpen)
            }
        }
    }
}

@Composable
private fun ReleaseList(list: List<EpisodeRelease>, onOpen: (String, String) -> Unit) {
    val groups = list.groupBy { dayKey(it.releasedAt) }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { (_, eps) ->
            item(key = "h" + eps.first().releasedAt) {
                Text(
                    dayLabel(eps.first().releasedAt), color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp),
                )
            }
            items(eps, key = { it.videoId }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen("series", e.metaId) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(48.dp).height(72.dp).clip(RoundedCornerShape(6.dp)).background(Surface2)) {
                        AsyncImage(e.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.showName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(e.code, e.title).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(timeLabel(e.releasedAt), fontSize = 12.sp, color = Muted)
                    }
                }
            }
        }
    }
}

private fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

/** "Today", "Tomorrow", "Yesterday", or e.g. "Mon 30 Nov". */
fun dayLabel(ms: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        sameDay(day, today) -> "Today"
        sameDay(day, tomorrow) -> "Tomorrow"
        sameDay(day, yesterday) -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

private fun timeLabel(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
