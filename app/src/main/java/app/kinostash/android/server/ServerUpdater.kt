package app.kinostash.android.server

import android.content.Context
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps Stremio's `server.js` up to date without app updates.
 *
 * - Baseline: a pinned version with a known SHA-256, downloaded on first use.
 * - Updates: the version Stremio ships is read from Stremio Service's public Cargo.toml (Stremio's
 *   own server package), then downloaded from dl.strem.io over HTTPS and sanity-checked.
 * - Safety: a new version is only a *candidate* until it has started successfully once. If it
 *   fails, it is blacklisted and the last working version is used again.
 */
class ServerUpdater(context: Context, private val binDir: File) {
    private val prefs = context.getSharedPreferences("stremio_server_updates", Context.MODE_PRIVATE)

    /** Last version that started successfully (null until the first successful start). */
    val goodVersion: String? get() = prefs.getString(KEY_GOOD, null)
    /** Newer version downloaded and waiting to be tried on the next server start. */
    val candidateVersion: String? get() = prefs.getString(KEY_CANDIDATE, null)
    private val badVersions: Set<String> get() = prefs.getStringSet(KEY_BAD, emptySet()) ?: emptySet()

    fun file(version: String) = File(binDir, "server-$version.js")

    /** Versions to try, newest first. Downloads the pinned baseline if nothing usable is on disk. */
    fun launchOrder(): List<Pair<String, File>> {
        binDir.mkdirs()
        val order = mutableListOf<Pair<String, File>>()
        candidateVersion?.takeIf { it !in badVersions && file(it).exists() }?.let { order += it to file(it) }
        goodVersion?.takeIf { it != candidateVersion && file(it).exists() }?.let { order += it to file(it) }
        // Last resort: the pinned, checksum-verified baseline. Only downloaded when nothing that
        // is known to work is on disk (so being offline never blocks an installed version).
        if (order.none { it.first == BASELINE_VERSION }) {
            val haveGood = order.any { it.first == goodVersion }
            val baseline = file(BASELINE_VERSION)
            when {
                baseline.exists() -> order += BASELINE_VERSION to baseline
                !haveGood -> runCatching { ensureBaseline() }
                    .onSuccess { order += BASELINE_VERSION to it }
                    .onFailure { if (order.isEmpty()) throw it }
            }
        }
        return order
    }

    fun markGood(version: String) {
        prefs.edit().putString(KEY_GOOD, version).apply {
            if (candidateVersion == version) remove(KEY_CANDIDATE)
        }.commit()
        // Keep only the working version (and the baseline as a last resort).
        binDir.listFiles()?.filter {
            it.name.startsWith("server-") && it != file(version) && it != file(BASELINE_VERSION) &&
                it != candidateVersion?.let(::file)
        }?.forEach { it.delete() }
    }

    fun markBad(version: String) {
        if (version == BASELINE_VERSION) return // the pinned baseline is never blacklisted
        Timber.tag(TAG).w("Stremio server %s failed to start; rolling back", version)
        prefs.edit()
            .putStringSet(KEY_BAD, badVersions + version)
            .apply { if (candidateVersion == version) remove(KEY_CANDIDATE) }
            .commit()
        file(version).delete()
    }

    /**
     * Look for a newer server.js and stage it as the candidate for the next server start.
     * Cheap and rate-limited (every [CHECK_INTERVAL_MS]); safe to call after each start.
     */
    fun checkForUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val current = candidateVersion ?: goodVersion ?: BASELINE_VERSION
        val latest = discoverLatest(current) ?: return
        if (compare(latest, current) <= 0 || latest in badVersions) return

        Timber.tag(TAG).i("Stremio server update found: %s -> %s", current, latest)
        val dest = file(latest)
        runCatching {
            download(serverUrl(latest), dest)
            validate(dest)
            prefs.edit().putString(KEY_CANDIDATE, latest).commit()
            Timber.tag(TAG).i("Stremio server %s staged; it will be used from the next server start", latest)
        }.onFailure {
            dest.delete()
            Timber.tag(TAG).w(it, "Couldn't stage Stremio server %s", latest)
        }
    }

    // --- discovery ---------------------------------------------------------------------------

    /**
     * The server.js version Stremio currently ships, as declared by Stremio Service (Stremio's own
     * standalone server package) in its public Cargo.toml. dl.strem.io also hosts newer builds that
     * Stremio hasn't rolled out yet; those are deliberately ignored.
     */
    private fun discoverLatest(@Suppress("UNUSED_PARAMETER") current: String): String? = runCatching {
        val toml = httpGetText(STREMIO_SERVICE_CARGO_TOML)
        Regex("""\[package\.metadata\.server\]\s*\n\s*version\s*=\s*"(v?[0-9]+\.[0-9]+\.[0-9]+)"""")
            .find(toml)?.groupValues?.get(1)?.let(::normalize)
    }.onFailure { Timber.tag(TAG).d(it, "Stremio server version lookup failed") }.getOrNull()

    // --- download / validation --------------------------------------------------------------

    private fun ensureBaseline(): File {
        val f = file(BASELINE_VERSION)
        if (f.exists() && sha256(f) == BASELINE_SHA256) return f
        Timber.tag(TAG).i("Downloading Stremio server %s", BASELINE_VERSION)
        download(serverUrl(BASELINE_VERSION), f)
        val actual = sha256(f)
        if (actual != BASELINE_SHA256) {
            f.delete()
            error("server.js checksum mismatch ($actual)")
        }
        return f
    }

    /** Make sure a downloaded file is really Stremio's server bundle (no checksum is published for new versions). */
    private fun validate(f: File) {
        val size = f.length()
        check(size in 1_000_000L..80_000_000L) { "unexpected server.js size $size" }
        val head = f.inputStream().use { input -> ByteArray(200).let { buf -> String(buf, 0, input.read(buf)) } }
        check(head.startsWith("/*! For license information please see server.js.LICENSE.txt */")) { "not a Stremio server bundle" }
        val text = f.readText()
        check(text.contains("\"stremio-server\"") && text.contains("EngineFS")) { "not a Stremio server bundle" }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".download")
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode} for $url")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (!tmp.renameTo(dest)) { tmp.delete(); error("couldn't save ${dest.name}") }
    }

    private fun httpGetText(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000 }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "StremioServerUpdater"
        const val BASELINE_VERSION = "v4.21.1"
        const val BASELINE_SHA256 = "405eb494d6708406a30e716c3cfb5abae7a5e9c7a8b79446d64c3f821385930f"
        private const val STREMIO_SERVICE_CARGO_TOML =
            "https://raw.githubusercontent.com/Stremio/stremio-service/master/Cargo.toml"
        private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val KEY_GOOD = "good"
        private const val KEY_CANDIDATE = "candidate"
        private const val KEY_BAD = "bad"
        private const val KEY_LAST_CHECK = "last_check"

        fun serverUrl(version: String) = "https://dl.strem.io/server/$version/desktop/server.js"

        private fun normalize(v: String) = if (v.startsWith("v")) v else "v$v"

        private fun parts(v: String): Triple<Int, Int, Int> {
            val p = v.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
            return Triple(p.getOrElse(0) { 0 }, p.getOrElse(1) { 0 }, p.getOrElse(2) { 0 })
        }

        fun compare(a: String, b: String): Int {
            val (a1, a2, a3) = parts(a)
            val (b1, b2, b3) = parts(b)
            return compareValuesBy(Triple(a1, a2, a3), Triple(b1, b2, b3), { it.first }, { it.second }, { it.third })
        }
    }
}
