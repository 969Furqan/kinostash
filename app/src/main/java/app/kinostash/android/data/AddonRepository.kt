package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Installed addons (persisted), plus helpers that query all of them. */
class AddonRepository(context: Context) {
    private val file = File(context.filesDir, "addons.json")

    /** Raw (transportUrl, manifest) entries: the persisted source of truth, in display order. */
    private var entries: List<Pair<String, JSONObject>> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("transportUrl") to o.getJSONObject("manifest") } }
    }.getOrDefault(emptyList())

    private val _addons = MutableStateFlow(parse(entries))
    val addons: StateFlow<List<Addon>> = _addons

    private fun parse(list: List<Pair<String, JSONObject>>) =
        list.mapNotNull { (url, m) -> runCatching { AddonClient.parseManifest(url, m) }.getOrNull() }

    @Synchronized
    private fun commit(list: List<Pair<String, JSONObject>>) {
        entries = list
        _addons.value = parse(list)
        runCatching {
            val arr = JSONArray()
            list.forEach { (url, m) -> arr.put(JSONObject().put("transportUrl", url).put("manifest", m)) }
            file.writeText(arr.toString())
        }
    }

    /** Installed addons as (manifest URL, raw manifest), e.g. to add them to a Stremio account. */
    fun rawEntries(): List<Pair<String, JSONObject>> = entries

    /** First launch: install the defaults (Cinemeta for catalogs/metadata, Torrentio, OpenSubtitles). */
    suspend fun ensureDefaults() {
        if (_addons.value.isNotEmpty()) return
        for (url in DEFAULT_ADDONS) runCatching { install(url) }
    }

    suspend fun install(rawUrl: String): Addon {
        val url = AddonClient.normalizeManifestUrl(rawUrl)
        val json = AddonClient.getJson(url)
        val addon = AddonClient.parseManifest(url, json)
        commit(entries.filterNot { (u, m) -> u == url || m.optString("id") == addon.id } + (url to json))
        return addon
    }

    /** Replace all addons with this list of (transportUrl, manifest) e.g. from a Stremio account. */
    fun replaceAll(items: List<Pair<String, JSONObject>>) {
        val valid = items.filter { (url, m) -> runCatching { AddonClient.parseManifest(url, m) }.isSuccess }
        if (valid.isNotEmpty()) commit(valid)
    }

    fun remove(addon: Addon) = commit(entries.filterNot { it.first == addon.transportUrl })

    fun move(addon: Addon, up: Boolean) {
        val list = entries.toMutableList()
        val i = list.indexOfFirst { it.first == addon.transportUrl }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        commit(list)
    }

    // --- aggregate queries ------------------------------------------------------------------

    /** Catalogs that can be shown on the home screen (no required extras). */
    fun homeCatalogs(): List<Pair<Addon, CatalogDef>> =
        _addons.value.flatMap { a -> a.catalogs.filter { !it.requiresExtra }.map { a to it } }

    /** One result section per searchable catalog. */
    data class SearchSection(val key: String, val title: String, val items: List<MetaPreview>)

    suspend fun search(query: String): List<SearchSection> = coroutineScope {
        _addons.value.flatMap { a -> a.catalogs.filter { it.supportsSearch }.map { a to it } }
            .map { (a, c) ->
                async {
                    runCatching {
                        // Several catalogs share a name (e.g. Cinemeta's "Popular" for movies and for
                        // series), so the key includes the addon, type and catalog id.
                        val type = c.type.replaceFirstChar { it.uppercase() }
                        SearchSection(
                            key = "${a.transportUrl}|${c.type}|${c.id}",
                            title = "${c.name} · $type · ${a.name}",
                            items = AddonClient.catalog(a, c, search = query).distinctBy { it.type + it.id },
                        )
                    }.getOrNull()
                }
            }
            .awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
            .distinctBy { it.key }
    }

    suspend fun meta(type: String, id: String): MetaDetail? {
        for (a in _addons.value.filter { it.supports("meta", type, id) }) {
            runCatching { AddonClient.meta(a, type, id) }.getOrNull()?.let { return it }
        }
        return null
    }

    suspend fun streams(type: String, id: String): List<StreamItem> = coroutineScope {
        _addons.value.filter { it.supports("stream", type, id) }
            .map { a -> async { runCatching { AddonClient.streams(a, type, id) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
    }

    suspend fun subtitles(type: String, id: String): List<SubtitleItem> = coroutineScope {
        _addons.value.filter { it.supports("subtitles", type, id) }
            .map { a -> async { runCatching { AddonClient.subtitles(a, type, id) }.getOrDefault(emptyList()) } }
            .awaitAll().flatten()
    }

    companion object {
        val DEFAULT_ADDONS = listOf(
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://torrentio.strem.fun/manifest.json",
            "https://opensubtitles-v3.strem.io/manifest.json",
        )
    }
}
