package app.kinostash.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.kinostash.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppRelease(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sha256: String?,
    val sizeBytes: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** Downloaded and verified in the background; installing is one tap. */
    data class ReadyToInstall(val release: AppRelease, val file: File, val note: String? = null) : UpdateState
    /** Handed to the system installer; the downloaded file stays cached so it can be installed again. */
    data class Installing(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-updates from this project's GitHub Releases.
 *
 * Safety: the APK's SHA-256 is checked against the release's published checksum, and the APK must
 * be signed with the same certificate as the installed app (Android enforces this too). Installation
 * uses the system package installer, which asks you to confirm.
 */
object AppUpdater {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"

    private val _lastCheck = MutableStateFlow(0L)
    /** When the app last looked for an update (0 = never). */
    val lastCheck: StateFlow<Long> = _lastCheck

    fun init(ctx: Context) {
        _lastCheck.value = prefs(ctx).getLong("last_check", 0)
        restoreCached(ctx)
    }

    // --- Cached download: survives a dismissed install dialog and app restarts ----------------

    private fun saveCached(ctx: Context, rel: AppRelease) {
        prefs(ctx).edit().putString("cached_release", JSONObject().apply {
            put("version", rel.version); put("notes", rel.notes); put("apkUrl", rel.apkUrl); put("apkName", rel.apkName)
            put("sha256", rel.sha256 ?: ""); put("size", rel.sizeBytes); put("pageUrl", rel.pageUrl)
        }.toString()).apply()
    }

    private fun cachedRelease(ctx: Context): AppRelease? = runCatching {
        val j = JSONObject(prefs(ctx).getString("cached_release", null) ?: return null)
        AppRelease(j.getString("version"), j.optString("notes"), j.getString("apkUrl"), j.getString("apkName"),
            j.optString("sha256").ifBlank { null }, j.optLong("size"), j.optString("pageUrl"))
    }.getOrNull()

    private fun cachedFile(ctx: Context, rel: AppRelease) = File(File(ctx.cacheDir, "updates"), rel.apkName)

    /** A verified download of [version] that is still on disk, if any. */
    private fun cachedReady(ctx: Context, version: String? = null): UpdateState.ReadyToInstall? {
        val rel = cachedRelease(ctx) ?: return null
        if (version != null && rel.version != version) return null
        val f = cachedFile(ctx, rel)
        return if (f.exists() && f.length() > 0) UpdateState.ReadyToInstall(rel, f) else null
    }

    private fun restoreCached(ctx: Context) {
        val rel = cachedRelease(ctx) ?: return
        if (!isNewer(rel.version, BuildConfig.VERSION_NAME)) {
            // Already installed (or older): clean up.
            File(ctx.cacheDir, "updates").deleteRecursively()
            prefs(ctx).edit().remove("cached_release").apply()
            return
        }
        cachedReady(ctx)?.let { _state.value = it }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** Check GitHub for a newer release. [quiet] keeps the state Idle when there is nothing new. */
    suspend fun check(ctx: Context, quiet: Boolean = false): AppRelease? {
        if (!quiet) _state.value = UpdateState.Checking
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("last_check", now).apply()
        _lastCheck.value = now
        val previous = _state.value
        return runCatching { fetchLatest() }.fold(
            onSuccess = { rel ->
                if (rel != null && isNewer(rel.version, BuildConfig.VERSION_NAME)) {
                    // Reuse an already-downloaded copy instead of downloading again.
                    _state.value = cachedReady(ctx, rel.version) ?: UpdateState.Available(rel); rel
                } else {
                    if (!quiet) _state.value = UpdateState.UpToDate
                    null
                }
            },
            onFailure = {
                val ready = cachedReady(ctx)
                when {
                    ready != null -> { _state.value = ready.copy(note = "Couldn't reach GitHub, but the downloaded update can still be installed."); ready.release }
                    quiet -> { _state.value = previous; null }
                    else -> { _state.value = UpdateState.Failed("Couldn't check for updates: ${it.message}"); null }
                }
            },
        )
    }

    /** Automatic check (app launch / background), following the user's update settings. */
    suspend fun checkIfDue(ctx: Context, settings: app.kinostash.android.data.AppSettings): AppRelease? {
        if (!settings.autoCheckUpdates) return null
        val last = prefs(ctx).getLong("last_check", 0)
        if (System.currentTimeMillis() - last < settings.updateFrequency.intervalMs) return null
        val rel = check(ctx, quiet = true) ?: return null
        if (settings.autoDownloadUpdates && onUnmeteredNetwork(ctx)) prepare(ctx, rel)
        return rel
    }

    private fun onUnmeteredNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Download and verify without installing (background auto-download). */
    suspend fun prepare(ctx: Context, rel: AppRelease): Boolean = try {
        _state.value = UpdateState.Downloading(rel, 0f)
        val file = withContext(Dispatchers.IO) { download(ctx, rel) }
        withContext(Dispatchers.IO) { verify(ctx, file, rel) }
        saveCached(ctx, rel)
        _state.value = UpdateState.ReadyToInstall(rel, file)
        true
    } catch (t: Throwable) {
        _state.value = UpdateState.Available(rel) // fall back to a manual download
        false
    }

    /** Install an update that [prepare] already downloaded and verified. */
    suspend fun installPrepared(ctx: Context, ready: UpdateState.ReadyToInstall) {
        try {
            if (!ready.file.exists()) return downloadAndInstall(ctx, ready.release)
            withContext(Dispatchers.IO) { verify(ctx, ready.file, ready.release) }
            _state.value = UpdateState.Installing(ready.release, ready.file)
            withContext(Dispatchers.IO) { install(ctx, ready.file) }
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Hand the cached file to the installer again (e.g. the confirm dialog was dismissed or never showed). */
    suspend fun reinstall(ctx: Context, installing: UpdateState.Installing) =
        installPrepared(ctx, UpdateState.ReadyToInstall(installing.release, installing.file))

    /** The installer was cancelled or failed: keep the downloaded file and offer to install again. */
    private fun backToReady(ctx: Context, note: String?) {
        val cur = _state.value
        val ready = (cur as? UpdateState.Installing)?.let { UpdateState.ReadyToInstall(it.release, it.file) }?.takeIf { it.file.exists() }
            ?: cachedReady(ctx)
        _state.value = ready?.copy(note = note) ?: if (note != null) UpdateState.Failed(note) else UpdateState.Idle
    }

    private suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "Kinostash").build()
        val json = http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null // no releases yet
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            JSONObject(r.body?.string() ?: "{}")
        }
        val version = json.optString("tag_name").removePrefix("v").ifBlank { return@withContext null }
        val assets = json.optJSONArray("assets") ?: return@withContext null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "universal"
        var apk: JSONObject? = null
        var fallback: JSONObject? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            when {
                name.endsWith(".apk") && name.contains(abi) -> apk = a
                name.endsWith(".apk") && name.contains("universal") -> fallback = a
                name == "SHA256SUMS.txt" -> sums = a.optString("browser_download_url")
            }
        }
        val chosen = apk ?: fallback ?: return@withContext null
        val name = chosen.optString("name")
        // GitHub reports a digest per asset ("sha256:…"); SHA256SUMS.txt is the fallback.
        val sha = chosen.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            ?: sums?.let { url -> runCatching { downloadText(url) }.getOrNull() }
                ?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }?.firstOrNull { it.size >= 2 && it.last().trimStart('*') == name }?.first()
        AppRelease(
            version = version,
            notes = json.optString("body").trim(),
            apkUrl = chosen.optString("browser_download_url"),
            apkName = name,
            sha256 = sha?.lowercase(),
            sizeBytes = chosen.optLong("size"),
            pageUrl = json.optString("html_url"),
        )
    }

    private fun downloadText(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() ?: "" }

    /** Download, verify and hand the update to the system installer. */
    suspend fun downloadAndInstall(ctx: Context, rel: AppRelease) {
        try {
            _state.value = UpdateState.Downloading(rel, 0f)
            val file = withContext(Dispatchers.IO) { download(ctx, rel) }
            withContext(Dispatchers.IO) { verify(ctx, file, rel) }
            saveCached(ctx, rel)
            _state.value = UpdateState.Installing(rel, file)
            withContext(Dispatchers.IO) { install(ctx, file) }
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun download(ctx: Context, rel: AppRelease): File {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, rel.apkName)
        http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            val body = r.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.sizeBytes
            var done = 0L
            var lastUpdate = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        output.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastUpdate > 200) {
                            lastUpdate = now
                            _state.value = UpdateState.Downloading(rel, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun verify(ctx: Context, file: File, rel: AppRelease) {
        val expected = rel.sha256 ?: throw IOException("The release has no checksum; refusing to install")
        val actual = sha256(file)
        if (actual != expected) throw IOException("Checksum mismatch; the download is corrupted")

        // Must be our app, signed with the same key as the installed version.
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("Not a valid APK")
        if (archive.packageName != ctx.packageName) throw IOException("The update is for a different app")
        val installed = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo) =
            info.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
        val mine = certs(installed)
        val theirs = certs(archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) throw IOException("The update is not signed with the Kinostash key")
    }

    private fun install(ctx: Context, file: File) {
        val installer = ctx.packageManager.packageInstaller
        // Drop sessions left over from an earlier attempt whose dialog was dismissed.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("kinostash.apk", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val pi = PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallResult(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system needs you to confirm (and, the first time, to allow installs from Kinostash).
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // the app is being replaced
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) backToReady(ctx, "Install was cancelled. Tap Install to try again.")
                else backToReady(ctx, "Install failed: $msg")
            }
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

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** "0.10.1" > "0.9.3"; ignores pre-release suffixes after '-'. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallResult(context, intent)
}
