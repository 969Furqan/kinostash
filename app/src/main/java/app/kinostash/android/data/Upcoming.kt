package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An episode of a series in your library, with its release time. */
data class EpisodeRelease(
    val metaId: String,
    val showName: String,
    val poster: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val title: String,
    val releasedAt: Long,
) {
    val code: String get() = if (season != null && episode != null) "S${season}E$episode" else ""
}

/**
 * Release calendar for the series in your library. Episode schedules come from the series
 * metadata (Cinemeta and other meta addons publish future episodes with release times).
 * Metadata is cached and refreshed at most every [MAX_AGE_MS].
 */
class Upcoming(context: Context, private val addons: AddonRepository, private val library: Library) {
    private val file = File(context.filesDir, "series_schedule.json")

    private data class Cached(val fetchedAt: Long, val status: String?, val releases: List<EpisodeRelease>)
    private val cache: MutableMap<String, Cached> = load().toMutableMap()

    private val _releases = MutableStateFlow(all())
    /** All known episode releases of library series, sorted by time. */
    val releases: StateFlow<List<EpisodeRelease>> = _releases

    fun upcoming(now: Long = System.currentTimeMillis()) = _releases.value.filter { it.releasedAt > now }
    fun recent(days: Int = 7, now: Long = System.currentTimeMillis()) =
        _releases.value.filter { it.releasedAt in (now - days * DAY)..now }.sortedByDescending { it.releasedAt }
    fun nextFor(metaId: String, now: Long = System.currentTimeMillis()) =
        _releases.value.firstOrNull { it.metaId == metaId && it.releasedAt > now }

    /** Refresh schedules of library series (skips fresh cache entries and ended shows unless [force]). */
    suspend fun refresh(force: Boolean = false) = coroutineScope {
        val now = System.currentTimeMillis()
        val series = library.items.value.filter { it.type == "series" }
        val gate = Semaphore(4)
        series.map { item ->
            async {
                val c = cache[item.id]
                val stale = c == null || now - c.fetchedAt > (if (c.status.equals("Ended", true)) 7 * DAY else MAX_AGE_MS)
                if (!force && !stale) return@async
                gate.withPermit {
                    val meta = runCatching { addons.meta("series", item.id) }.getOrNull() ?: return@withPermit
                    val rel = meta.videos.mapNotNull { v ->
                        val at = v.releasedAt ?: return@mapNotNull null
                        EpisodeRelease(item.id, meta.name.ifBlank { item.name }, meta.poster ?: item.poster, v.id, v.season, v.episode, v.title, at)
                    }.filter { (it.season ?: 1) > 0 } // skip specials
                    synchronized(cache) { cache[item.id] = Cached(now, meta.status, rel) }
                }
            }
        }.awaitAll()
        // Forget series no longer in the library.
        synchronized(cache) { cache.keys.retainAll(series.map { it.id }.toSet()) }
        save()
        _releases.value = all()
    }

    /** Recompute from cache (e.g. after the library changed). */
    fun reindex() {
        val ids = library.items.value.map { it.id }.toSet()
        synchronized(cache) { cache.keys.retainAll(ids) }
        _releases.value = all()
    }

    private fun all(): List<EpisodeRelease> = synchronized(cache) { cache.values.flatMap { it.releases } }.sortedBy { it.releasedAt }

    private fun load(): Map<String, Cached> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { id ->
            val e = o.getJSONObject(id)
            val arr = e.getJSONArray("releases")
            Cached(e.optLong("fetchedAt"), e.optStr("status"), (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                EpisodeRelease(
                    id, r.getString("showName"), r.optStr("poster"), r.getString("videoId"),
                    if (r.has("season")) r.getInt("season") else null, if (r.has("episode")) r.getInt("episode") else null,
                    r.optString("title"), r.getLong("releasedAt"),
                )
            })
        }
    }.getOrDefault(emptyMap())

    private fun save() = runCatching {
        val o = JSONObject()
        synchronized(cache) {
            cache.forEach { (id, c) ->
                val arr = JSONArray()
                c.releases.forEach { r ->
                    arr.put(JSONObject().put("showName", r.showName).put("poster", r.poster).put("videoId", r.videoId)
                        .put("season", r.season).put("episode", r.episode).put("title", r.title).put("releasedAt", r.releasedAt))
                }
                o.put(id, JSONObject().put("fetchedAt", c.fetchedAt).put("status", c.status).put("releases", arr))
            }
        }
        file.writeText(o.toString())
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
    }
}
