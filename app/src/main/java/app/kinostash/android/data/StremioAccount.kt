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

    companion object {
        private const val API = "https://api.strem.io/api"
    }
}
