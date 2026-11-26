package app.kinostash.android.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class DownloadStatus {
    QUEUED, DOWNLOADING, WAITING_FOR_WIFI, PAUSED, COMPLETED, FAILED;

    /** Statuses the downloader keeps working on. */
    val isActive get() = this == QUEUED || this == DOWNLOADING || this == WAITING_FOR_WIFI
}

/**
 * One offline download. [sourceUrl] is either a local streaming-server URL
 * (`http://127.0.0.1:11470/<infoHash>/<fileIdx>`, for torrents) or a direct HTTP(S) link.
 */
data class DownloadItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val poster: String?,
    val metaType: String?,
    val metaId: String?,
    val videoId: String?,
    val sourceUrl: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val filename: String?,
    val trackers: List<String> = emptyList(),
    /** Series/movie name and episode info, for grouping in the Downloads tab. */
    val showName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val speed: Long = 0,
    /** Live torrent info from the streaming server (not persisted). */
    val peers: Int = -1,
    val torrentSpeed: Long = 0,
    val filePath: String? = null,
    val error: String? = null,
    val positionMs: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    val isTorrent: Boolean get() = infoHash != null

    /** Key used to group downloads of the same series/movie in the Downloads tab. */
    val groupKey: String get() = metaId ?: showName ?: title
    val groupName: String get() = showName ?: title.substringBefore(" · ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("subtitle", subtitle); put("poster", poster)
        put("metaType", metaType); put("metaId", metaId); put("videoId", videoId)
        put("sourceUrl", sourceUrl); put("infoHash", infoHash); put("fileIdx", fileIdx); put("filename", filename)
        put("trackers", JSONArray(trackers)); put("showName", showName); put("season", season)
        put("episode", episode); put("episodeTitle", episodeTitle)
        put("status", status.name); put("bytesDone", bytesDone); put("bytesTotal", bytesTotal)
        put("filePath", filePath); put("error", error); put("positionMs", positionMs); put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            var status = runCatching { DownloadStatus.valueOf(o.getString("status")) }.getOrDefault(DownloadStatus.QUEUED)
            // Nothing is mid-transfer after a restart; re-queue so the downloader picks it up again.
            if (status == DownloadStatus.DOWNLOADING) status = DownloadStatus.QUEUED
            // Older entries have no season/episode fields: recover them from the video id ("tt123:1:2").
            val ep = str("videoId")?.let { Regex(""":(\d+):(\d+)$""").find(it) }
            return DownloadItem(
                id = o.getString("id"),
                title = o.optString("title"),
                subtitle = o.optString("subtitle"),
                poster = str("poster"),
                metaType = str("metaType"),
                metaId = str("metaId"),
                videoId = str("videoId"),
                sourceUrl = o.getString("sourceUrl"),
                infoHash = str("infoHash"),
                fileIdx = if (o.has("fileIdx") && !o.isNull("fileIdx")) o.getInt("fileIdx") else null,
                filename = str("filename"),
                trackers = o.optJSONArray("trackers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                showName = str("showName"),
                season = if (o.has("season") && !o.isNull("season")) o.getInt("season") else ep?.groupValues?.get(1)?.toIntOrNull(),
                episode = if (o.has("episode") && !o.isNull("episode")) o.getInt("episode") else ep?.groupValues?.get(2)?.toIntOrNull(),
                episodeTitle = str("episodeTitle"),
                status = status,
                bytesDone = o.optLong("bytesDone"),
                bytesTotal = o.optLong("bytesTotal"),
                filePath = str("filePath"),
                error = str("error"),
                positionMs = o.optLong("positionMs"),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            )
        }
    }
}

/** Thread-safe list of downloads, persisted as JSON in the app's private storage. */
class DownloadStore(context: Context) {
    private val file = File(context.filesDir, "offline_downloads.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items
    private var dirty = false

    private fun load(): List<DownloadItem> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun get(id: String): DownloadItem? = _items.value.find { it.id == id }

    @Synchronized
    fun add(item: DownloadItem) {
        _items.value = _items.value + item
        flush(force = true)
    }

    /** Update an item. Pass persist=false for frequent progress updates and call [flush] later. */
    @Synchronized
    fun update(id: String, persist: Boolean = true, block: (DownloadItem) -> DownloadItem) {
        _items.value = _items.value.map { if (it.id == id) block(it) else it }
        dirty = true
        if (persist) flush(force = true)
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        flush(force = true)
    }

    @Synchronized
    fun flush(force: Boolean = false) {
        if (!force && !dirty) return
        dirty = false
        runCatching {
            val arr = JSONArray()
            _items.value.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }
}
