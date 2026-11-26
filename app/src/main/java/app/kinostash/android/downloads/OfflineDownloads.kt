package app.kinostash.android.downloads

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import app.kinostash.android.data.StreamItem
import app.kinostash.android.server.StremioServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Info about what is being downloaded, for naming and grouping. */
data class DownloadMeta(
    val title: String,
    val poster: String?,
    val metaType: String,
    val metaId: String,
    val videoId: String,
    val showName: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
)

/**
 * Offline downloads. Torrents are downloaded through Stremio's streaming server: it already turns
 * any torrent file into an HTTP stream, so a download reads that stream from start to finish into
 * a file on the phone. Direct HTTP streams (e.g. debrid links) are fetched the same way.
 */
object OfflineDownloads {
    private var storeRef: DownloadStore? = null
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        storeRef = DownloadStore(context.applicationContext)
        prefs = context.applicationContext.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        _wifiOnly.value = prefs.getBoolean("wifi_only", true)
    }

    val store: DownloadStore get() = storeRef ?: error("OfflineDownloads.init() not called")

    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly

    fun setWifiOnly(context: Context, value: Boolean) {
        prefs.edit().putBoolean("wifi_only", value).apply()
        _wifiOnly.value = value
        kick(context)
    }

    private val _streaming = MutableStateFlow(false)
    /** True while an online stream plays: downloads step aside so playback gets the bandwidth. */
    val streaming: StateFlow<Boolean> = _streaming

    fun setStreaming(ctx: Context, value: Boolean) {
        if (_streaming.value == value) return
        _streaming.value = value
        if (!value) kick(ctx)
    }

    const val MAX_ACTIVE = 2

    fun rootDir(ctx: Context): File = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "Movies")
    fun itemDir(ctx: Context, id: String) = File(rootDir(ctx), id)

    private fun sourceUrlOf(s: StreamItem): String? = when {
        s.infoHash != null -> "${StremioServer.BASE_URL}/${s.infoHash}/${s.fileIdx ?: -1}"
        s.url?.startsWith("http") == true -> s.url
        else -> null
    }

    fun enqueue(ctx: Context, stream: StreamItem, meta: DownloadMeta): DownloadItem? {
        val url = sourceUrlOf(stream) ?: return null
        store.items.value.firstOrNull { it.sourceUrl == url && it.status != DownloadStatus.FAILED }?.let { return it }
        val item = DownloadItem(
            id = UUID.randomUUID().toString().take(12),
            title = meta.title,
            subtitle = listOfNotNull(stream.quality?.uppercase(), stream.sizeLabel, stream.addonName).joinToString(" · "),
            poster = meta.poster,
            metaType = meta.metaType,
            metaId = meta.metaId,
            videoId = meta.videoId,
            sourceUrl = url,
            infoHash = stream.infoHash,
            fileIdx = stream.fileIdx,
            filename = stream.filename,
            trackers = stream.trackers,
            showName = meta.showName,
            season = meta.season,
            episode = meta.episode,
            episodeTitle = meta.episodeTitle,
        )
        store.add(item)
        kick(ctx)
        return item
    }

    /** A finished download of exactly this stream, if any. */
    fun completedFileFor(stream: StreamItem): File? {
        val url = sourceUrlOf(stream) ?: return null
        val item = storeRef?.items?.value?.firstOrNull { it.sourceUrl == url && it.status == DownloadStatus.COMPLETED } ?: return null
        return item.filePath?.let(::File)?.takeIf { it.exists() }
    }

    fun pause(ctx: Context, id: String) {
        store.update(id) { it.copy(status = DownloadStatus.PAUSED, speed = 0) }
        kick(ctx)
    }

    fun resume(ctx: Context, id: String) {
        store.update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        kick(ctx)
    }

    fun savePosition(id: String, positionMs: Long) = store.update(id, persist = false) { it.copy(positionMs = positionMs) }

    fun delete(ctx: Context, id: String) {
        val app = ctx.applicationContext
        store.remove(id)
        thread(name = "delete-download") { itemDir(app, id).deleteRecursively() }
    }

    fun kick(ctx: Context) {
        if (store.items.value.any { it.status.isActive }) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java)) }
        }
    }
}
