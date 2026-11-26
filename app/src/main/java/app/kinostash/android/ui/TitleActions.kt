package app.kinostash.android.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinostash.android.App
import app.kinostash.android.data.LibraryItem
import app.kinostash.android.data.MetaDetail
import app.kinostash.android.data.StreamPicker
import app.kinostash.android.data.Video
import app.kinostash.android.data.WatchEntry
import app.kinostash.android.downloads.DownloadItem
import app.kinostash.android.downloads.DownloadMeta
import app.kinostash.android.downloads.DownloadStatus
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.player.PlayerActivity
import kotlinx.coroutines.launch

/** Shared logic for titles and episodes: watched marks, downloads, playing local copies. */
object TitleActions {
    /** A finished download of this video (movie id or episode id), if any. */
    fun downloaded(videoId: String, items: List<DownloadItem> = OfflineDownloads.store.items.value): DownloadItem? =
        items.firstOrNull { it.videoId == videoId && it.status == DownloadStatus.COMPLETED && it.filePath?.let { p -> java.io.File(p).exists() } == true }

    fun downloading(videoId: String, items: List<DownloadItem> = OfflineDownloads.store.items.value): DownloadItem? =
        items.firstOrNull { it.videoId == videoId && it.status.isActive }

    fun hasDownloads(metaId: String, items: List<DownloadItem> = OfflineDownloads.store.items.value) =
        items.any { it.metaId == metaId && it.status == DownloadStatus.COMPLETED }

    private fun entry(meta: MetaDetail, videoId: String, label: String?) =
        WatchEntry(videoId, meta.id, meta.type, meta.name, meta.poster, label, 1, 1, System.currentTimeMillis())

    fun label(v: Video) = listOfNotNull(v.season?.let { s -> v.episode?.let { "S${s}E$it" } }, v.title).joinToString(" · ")

    fun setWatched(meta: MetaDetail, videos: List<Video>, watched: Boolean) =
        App.instance.history.setWatched(videos.map { entry(meta, it.id, label(it)) }, watched)

    fun setMovieWatched(meta: MetaDetail, watched: Boolean) =
        App.instance.history.setWatched(listOf(entry(meta, meta.id, null)), watched)

    /** Episodes that have aired (marking a whole series watched shouldn't include future ones). */
    fun aired(meta: MetaDetail) = meta.videos.filter { (it.season ?: 1) > 0 && (it.releasedAt ?: 0) <= System.currentTimeMillis() }

    /** Play: the downloaded copy directly if there is one, otherwise null (caller shows streams). */
    fun playIfDownloaded(ctx: Context, videoId: String): Boolean {
        val d = downloaded(videoId) ?: return false
        PlayerActivity.startDownload(ctx, d)
        return true
    }

    /** Download the best stream for a movie or episode (preferred quality, then most seeders). */
    suspend fun autoDownload(ctx: Context, meta: MetaDetail, video: Video?): String {
        val videoId = video?.id ?: meta.id
        if (downloaded(videoId) != null) return "Already downloaded"
        if (downloading(videoId) != null) return "Already downloading"
        val streams = App.instance.addons.streams(meta.type, videoId)
        val best = StreamPicker.pick(streams, App.instance.settings.quality, forDownload = true) ?: return "No streams found"
        val title = listOfNotNull(meta.name, video?.let { label(it) }).joinToString(" · ")
        OfflineDownloads.enqueue(
            ctx, best,
            DownloadMeta(
                title = title, poster = meta.poster, metaType = meta.type, metaId = meta.id, videoId = videoId,
                showName = meta.name, season = video?.season, episode = video?.episode, episodeTitle = video?.title,
            ),
        ) ?: return "This stream can't be downloaded"
        return "Downloading ${best.quality ?: ""} ${best.sizeLabel ?: ""}".replace(Regex("\\s+"), " ").trim()
    }
}

/** Long-press menu for a whole title (poster in catalogs, search, library). */
@Composable
fun TitleMenu(type: String, id: String, name: String, poster: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by App.instance.library.items.collectAsState()
    val inLibrary = library.any { it.id == id }
    var showCategories by remember { mutableStateOf(false) }
    val item = LibraryItem(id, type, name, poster, System.currentTimeMillis())

    if (showCategories) { CategoryDialog(item, onDismiss); return }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 2) },
        text = {
            Column {
                MenuItem(if (inLibrary) "Remove from library" else "Add to library") {
                    if (inLibrary) App.instance.library.remove(id) else App.instance.library.add(item); onDismiss()
                }
                MenuItem("Add to category…") { showCategories = true }
                MenuItem("Mark as watched") {
                    scope.launch {
                        val meta = MetaCache.get(type, id) ?: App.instance.addons.meta(type, id)?.also { MetaCache.put(it) }
                        if (meta != null) {
                            if (type == "movie" || meta.videos.isEmpty()) TitleActions.setMovieWatched(meta, true)
                            else TitleActions.setWatched(meta, TitleActions.aired(meta), true)
                        }
                        onDismiss()
                    }
                }
                MenuItem("Mark as not watched") {
                    scope.launch {
                        val meta = MetaCache.get(type, id) ?: App.instance.addons.meta(type, id)?.also { MetaCache.put(it) }
                        if (meta != null) {
                            if (type == "movie" || meta.videos.isEmpty()) TitleActions.setMovieWatched(meta, false)
                            else TitleActions.setWatched(meta, meta.videos, false)
                        }
                        onDismiss()
                    }
                }
                if (type == "movie") MenuItem("Download (best quality)") {
                    scope.launch {
                        val meta = MetaCache.get(type, id) ?: App.instance.addons.meta(type, id)
                        val msg = if (meta != null) TitleActions.autoDownload(ctx, meta, null) else "Couldn't load details"
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun MenuItem(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

/** Pick which of your library categories a title belongs to; create new ones here too. */
@Composable
fun CategoryDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val cats by App.instance.library.categories.collectAsState()
    var chosen by remember { mutableStateOf(App.instance.library.categoriesOf(item.id)) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column {
                if (cats.isEmpty()) Text("No categories yet. Create one below.", color = Muted, fontSize = 13.sp)
                cats.keys.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { chosen = if (c in chosen) chosen - c else chosen + c },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c in chosen, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Accent))
                        Spacer(Modifier.width(8.dp))
                        Text(c)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("New category") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = newName.isNotBlank(), onClick = {
                        App.instance.library.createCategory(newName); chosen = chosen + newName.trim(); newName = ""
                    }) { Text("Add", fontWeight = FontWeight.SemiBold) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { App.instance.library.setCategories(item, chosen); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
