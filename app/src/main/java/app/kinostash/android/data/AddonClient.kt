package app.kinostash.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** HTTP client for the Stremio addon protocol (https://github.com/Stremio/stremio-addon-sdk). */
object AddonClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Kinostash").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
    }

    /** Accepts manifest URLs, base URLs and stremio:// links. */
    fun normalizeManifestUrl(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("stremio://")) s = "https://" + s.removePrefix("stremio://")
        if (!s.startsWith("http")) s = "https://$s"
        s = s.trimEnd('/')
        if (!s.endsWith("/manifest.json")) s += "/manifest.json"
        return s
    }

    suspend fun fetchManifest(manifestUrl: String): Addon = parseManifest(manifestUrl, getJson(manifestUrl))

    fun parseManifest(transportUrl: String, m: JSONObject): Addon {
        val types = m.optJSONArray("types").strings()
        val idPrefixes = m.optJSONArray("idPrefixes").strings()
        val resources = mutableMapOf<String, Pair<List<String>, List<String>>>()
        m.optJSONArray("resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                when (val r = arr.get(i)) {
                    is String -> resources[r] = types to idPrefixes
                    is JSONObject -> resources[r.optString("name")] = Pair(
                        r.optJSONArray("types")?.strings() ?: types,
                        r.optJSONArray("idPrefixes")?.strings() ?: idPrefixes,
                    )
                }
            }
        }
        val catalogs = mutableListOf<CatalogDef>()
        m.optJSONArray("catalogs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val extras = c.optJSONArray("extra")
                var search = false
                var required = false
                if (extras != null) for (j in 0 until extras.length()) {
                    val e = extras.getJSONObject(j)
                    if (e.optString("name") == "search") search = true
                    if (e.optBoolean("isRequired")) required = true
                }
                c.optJSONArray("extraSupported")?.strings()?.let { if ("search" in it) search = true }
                c.optJSONArray("extraRequired")?.strings()?.let { if (it.isNotEmpty()) required = true }
                catalogs += CatalogDef(c.optString("type"), c.optString("id"), c.optString("name", c.optString("id")), search, required)
            }
        }
        return Addon(
            transportUrl = transportUrl,
            id = m.optString("id"),
            name = m.optString("name", m.optString("id")),
            version = m.optString("version"),
            description = m.optString("description"),
            logo = m.optStr("logo"),
            types = types,
            catalogs = catalogs,
            resources = resources,
        )
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun catalog(addon: Addon, c: CatalogDef, search: String? = null, skip: Int = 0): List<MetaPreview> {
        val extra = buildList {
            if (search != null) add("search=${enc(search)}")
            if (skip > 0) add("skip=$skip")
        }.joinToString("&")
        val url = "${addon.baseUrl}/catalog/${c.type}/${enc(c.id)}" + (if (extra.isNotEmpty()) "/$extra" else "") + ".json"
        val arr = getJson(url).optJSONArray("metas") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val id = m.optStr("id") ?: return@mapNotNull null
            MetaPreview(id, m.optString("type", c.type), m.optString("name"), m.optStr("poster"), m.optStr("releaseInfo"), m.optStr("imdbRating"))
        }
    }

    suspend fun meta(addon: Addon, type: String, id: String): MetaDetail? {
        val m = getJson("${addon.baseUrl}/meta/$type/${enc(id)}.json").optJSONObject("meta") ?: return null
        val videos = mutableListOf<Video>()
        m.optJSONArray("videos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val season = if (v.has("season")) v.optInt("season") else null
                val episode = if (v.has("episode")) v.optInt("episode") else if (v.has("number")) v.optInt("number") else null
                videos += Video(
                    id = v.optString("id"),
                    title = v.optStr("name") ?: v.optStr("title") ?: episode?.let { "Episode $it" } ?: "Video",
                    season = season,
                    episode = episode,
                    released = v.optStr("released"),
                    thumbnail = v.optStr("thumbnail"),
                )
            }
        }
        videos.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        // Lists are keyed by video id; some addons repeat entries.
        val unique = videos.distinctBy { it.id }
        videos.clear(); videos.addAll(unique)
        return MetaDetail(
            id = m.optString("id", id),
            type = m.optString("type", type),
            name = m.optString("name"),
            poster = m.optStr("poster"),
            background = m.optStr("background"),
            logo = m.optStr("logo"),
            description = m.optStr("description"),
            releaseInfo = m.optStr("releaseInfo") ?: m.optStr("year"),
            runtime = m.optStr("runtime"),
            genres = m.optJSONArray("genres").strings(),
            imdbRating = m.optStr("imdbRating"),
            videos = videos,
            status = m.optStr("status"),
            cast = m.optJSONArray("cast").strings(),
            director = m.optJSONArray("director").strings(),
        )
    }

    suspend fun streams(addon: Addon, type: String, id: String): List<StreamItem> {
        val arr = getJson("${addon.baseUrl}/stream/$type/${enc(id)}.json").optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val hints = s.optJSONObject("behaviorHints")
            StreamItem(
                addonName = addon.name,
                name = s.optString("name").replace('\n', ' ').trim(),
                description = s.optStr("description") ?: s.optString("title"),
                infoHash = s.optStr("infoHash")?.lowercase(),
                fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null,
                url = s.optStr("url"),
                trackers = s.optJSONArray("sources").strings()
                    .filter { it.startsWith("tracker:") || it.startsWith("udp://") || it.startsWith("http") },
                filename = hints?.optStr("filename"),
            )
        }
    }

    suspend fun subtitles(addon: Addon, type: String, id: String): List<SubtitleItem> {
        val arr = getJson("${addon.baseUrl}/subtitles/$type/${enc(id)}.json").optJSONArray("subtitles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val url = s.optStr("url") ?: return@mapNotNull null
            SubtitleItem(s.optString("id", url), url, s.optString("lang", "und"))
        }
    }
}

internal fun JSONObject.optStr(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
