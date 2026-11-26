package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One entry per video watched: powers resume and the "Continue watching" row. Stored locally only. */
data class WatchEntry(
    val videoId: String,
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val label: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durationMs > 0 && durationMs - positionMs < 3 * 60_000
}

class WatchHistory(context: Context) {
    private val file = File(context.filesDir, "watch_history.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<WatchEntry>> = _entries
    private var lastSave = 0L

    private fun load(): List<WatchEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WatchEntry(
                o.getString("videoId"), o.getString("metaId"), o.getString("type"), o.getString("name"),
                o.optStr("poster"), o.optStr("label"), o.optLong("positionMs"), o.optLong("durationMs"), o.optLong("updatedAt"),
            )
        }
    }.getOrDefault(emptyList())

    fun positionFor(videoId: String): Long = _entries.value.find { it.videoId == videoId }?.takeIf { !it.finished }?.positionMs ?: 0L

    /** Most recent unfinished item per title. */
    fun continueWatching(): List<WatchEntry> =
        _entries.value.sortedByDescending { it.updatedAt }.distinctBy { it.metaId }.filter { !it.finished && it.positionMs > 30_000 }

    @Synchronized
    fun update(entry: WatchEntry, force: Boolean = false) {
        _entries.value = (_entries.value.filterNot { it.videoId == entry.videoId } + entry)
            .sortedByDescending { it.updatedAt }.take(300)
        val now = System.currentTimeMillis()
        if (force || now - lastSave > 10_000) { lastSave = now; save() }
    }

    @Synchronized
    fun remove(metaId: String) {
        _entries.value = _entries.value.filterNot { it.metaId == metaId }
        save()
    }

    private fun save() = runCatching {
        val arr = JSONArray()
        _entries.value.forEach { e ->
            arr.put(JSONObject().put("videoId", e.videoId).put("metaId", e.metaId).put("type", e.type).put("name", e.name)
                .put("poster", e.poster).put("label", e.label).put("positionMs", e.positionMs)
                .put("durationMs", e.durationMs).put("updatedAt", e.updatedAt))
        }
        file.writeText(arr.toString())
    }
}
