package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Optional sign-in to a Stremio account, used to import the addons you already installed in
 * Stremio. Talks to Stremio's public API (api.strem.io). Only the auth key is stored.
 */
class StremioAccount(context: Context) {
    private val prefs = context.getSharedPreferences("stremio_account", Context.MODE_PRIVATE)
    private val _email = MutableStateFlow(prefs.getString("email", null))
    val email: StateFlow<String?> = _email
    private val authKey: String? get() = prefs.getString("authKey", null)

    private suspend fun call(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        AddonClient.http.newCall(req).execute().use { r ->
            val json = JSONObject(r.body?.string() ?: "{}")
            json.optJSONObject("error")?.let { throw IOException(it.optString("message", "Stremio API error")) }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            json
        }
    }

    suspend fun login(email: String, password: String) {
        val res = call("login", JSONObject().put("type", "Login").put("email", email).put("password", password).put("facebook", false))
            .optJSONObject("result") ?: throw IOException("Unexpected response")
        val key = res.optString("authKey").takeIf { it.isNotBlank() } ?: throw IOException("Login failed")
        prefs.edit().putString("authKey", key).putString("email", email).apply()
        _email.value = email
    }

    fun logout() {
        prefs.edit().clear().apply()
        _email.value = null
    }

    /** Your Stremio account's addons as (transportUrl, manifest). */
    suspend fun addons(): List<Pair<String, JSONObject>> {
        val key = authKey ?: throw IOException("Not signed in")
        val res = call("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true))
        val arr = res.optJSONObject("result")?.optJSONArray("addons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.getJSONObject(i)
            val url = a.optString("transportUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val m = a.optJSONObject("manifest") ?: return@mapNotNull null
            url to m
        }
    }

    /** Your Stremio library: titles in it, and ids of titles you removed there. */
    suspend fun librarySnapshot(): Pair<List<LibraryItem>, Set<String>> {
        val key = authKey ?: throw IOException("Not signed in")
        val body = JSONObject()
        body.put("authKey", key)
        body.put("collection", "libraryItem")
        body.put("ids", org.json.JSONArray())
        body.put("all", true)
        val arr = call("datastoreGet", body).optJSONArray("result") ?: return Pair(emptyList(), emptySet())
        val items = mutableListOf<LibraryItem>()
        val removed = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("_id")
            val type = o.optString("type")
            if (id.isBlank() || (type != "movie" && type != "series") || o.optBoolean("temp")) continue
            if (o.optBoolean("removed")) { removed += id; continue }
            items += LibraryItem(id, type, o.optString("name"), o.optStr("poster"), System.currentTimeMillis())
        }
        return Pair(items, removed)
    }

    /** Movies and series in your Stremio library. */
    suspend fun library(): List<LibraryItem> = librarySnapshot().first

    val isSignedIn: Boolean get() = authKey != null

    /**
     * Create a Stremio account (Stremio's own sign-up API). The user must accept Stremio's Terms
     * and Privacy Policy; [marketing] is opt-in.
     */
    suspend fun register(email: String, password: String, marketing: Boolean = false) {
        val consent = JSONObject()
        consent.put("tos", true)
        consent.put("privacy", true)
        consent.put("marketing", marketing)
        consent.put("time", java.time.Instant.now().toString())
        consent.put("from", "kinostash")
        val body = JSONObject()
        body.put("type", "Register")
        body.put("email", email)
        body.put("password", password)
        body.put("gdpr_consent", consent)
        val res = call("register", body).optJSONObject("result") ?: throw IOException("Unexpected response")
        val key = res.optString("authKey").takeIf { it.isNotBlank() } ?: throw IOException("Sign-up failed")
        prefs.edit().putString("authKey", key).putString("email", email).apply()
        _email.value = email
    }

    /** Add these addons (manifest URL -> manifest) to the account's addon collection, keeping what's there. */
    suspend fun addToAccount(addons: List<Pair<String, JSONObject>>): Int {
        val key = authKey ?: throw IOException("Not signed in")
        val get = JSONObject()
        get.put("type", "AddonCollectionGet")
        get.put("authKey", key)
        get.put("update", true)
        val current = call("addonCollectionGet", get).optJSONObject("result")?.optJSONArray("addons") ?: org.json.JSONArray()
        val have = mutableSetOf<String>()
        for (i in 0 until current.length()) have += current.getJSONObject(i).optString("transportUrl")
        var added = 0
        for ((url, manifest) in addons) {
            if (url in have) continue
            val flags = JSONObject()
            flags.put("official", false)
            flags.put("protected", false)
            val descriptor = JSONObject()
            descriptor.put("transportUrl", url)
            descriptor.put("manifest", manifest)
            descriptor.put("flags", flags)
            current.put(descriptor)
            added++
        }
        if (added > 0) {
            val set = JSONObject()
            set.put("type", "AddonCollectionSet")
            set.put("authKey", key)
            set.put("addons", current)
            call("addonCollectionSet", set)
        }
        return added
    }

    private suspend fun fetchLibraryItems(ids: List<String>): List<JSONObject> {
        val key = authKey ?: return emptyList()
        val body = JSONObject()
        body.put("authKey", key)
        body.put("collection", "libraryItem")
        body.put("ids", org.json.JSONArray(ids))
        body.put("all", false)
        val arr = call("datastoreGet", body).optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /**
     * Add or remove a title in your Stremio library. An existing entry is updated in place (its
     * watch state is kept); a new one is created with Stremio's library-item fields.
     */
    suspend fun pushLibrary(item: LibraryItem, removed: Boolean) {
        val key = authKey ?: return
        val now: String = java.time.Instant.now().toString()
        val existing: JSONObject? = runCatching { fetchLibraryItems(listOf(item.id)) }.getOrDefault(emptyList()).firstOrNull()
        if (existing == null && removed) return
        val obj: JSONObject = existing ?: newLibraryItem(item, now)
        obj.put("removed", removed)
        obj.put("temp", false)
        obj.put("_mtime", now)
        val changes = org.json.JSONArray()
        changes.put(obj)
        val body = JSONObject()
        body.put("authKey", key)
        body.put("collection", "libraryItem")
        body.put("changes", changes)
        call("datastorePut", body)
    }

    /** A new entry in Stremio's library-item format. */
    private fun newLibraryItem(item: LibraryItem, now: String): JSONObject {
        val state = JSONObject()
        state.put("lastWatched", JSONObject.NULL)
        state.put("timeWatched", 0)
        state.put("timeOffset", 0)
        state.put("overallTimeWatched", 0)
        state.put("timesWatched", 0)
        state.put("flaggedWatched", 0)
        state.put("duration", 0)
        state.put("video_id", JSONObject.NULL)
        state.put("watched", JSONObject.NULL)
        state.put("noNotif", false)
        val hints = JSONObject()
        hints.put("defaultVideoId", JSONObject.NULL)
        hints.put("featuredVideoId", JSONObject.NULL)
        hints.put("hasScheduledVideos", false)
        val o = JSONObject()
        o.put("_id", item.id)
        o.put("name", item.name)
        o.put("type", item.type)
        o.put("poster", item.poster ?: JSONObject.NULL)
        o.put("posterShape", "poster")
        o.put("_ctime", now)
        o.put("temp", false)
        o.put("state", state)
        o.put("behaviorHints", hints)
        return o
    }

    companion object {
        private const val API = "https://api.strem.io/api"
    }
}
