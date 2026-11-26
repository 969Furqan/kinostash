package app.kinostash.android.server

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Ready(val version: String) : ServerState
    data class Failed(val message: String) : ServerState
}

/**
 * Runs Stremio's streaming server (`server.js`, the one the official Stremio app uses) on
 * nodejs-mobile, as a separate process listening on 127.0.0.1:11470.
 *
 * `server.js` is not part of this app: it is downloaded from Stremio's own download server and
 * kept up to date by [ServerUpdater]. The launcher (`libstremionode.so`, see node-launcher/) runs
 * `libnode.so` as a normal process from the app's native library directory.
 */
class StremioServer(private val context: Context) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state

    private val _pendingUpdate = MutableStateFlow<String?>(null)
    /** A newer server.js downloaded and waiting for the next start. */
    val pendingUpdate: StateFlow<String?> = _pendingUpdate

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null

    private val dir get() = File(context.filesDir, "stremio-server").apply { mkdirs() }
    private val pidFile get() = File(dir, "node.pid")
    val logFile: File get() = File(dir, "server.log")
    private val updater by lazy { ServerUpdater(context, File(dir, "bin")) }

    /** Start the server if needed and wait until it answers. Throws if it can't start. */
    suspend fun ensureRunning() {
        mutex.withLock {
            if (_state.value is ServerState.Ready && process?.isAlive == true && reachable()) return
            _state.value = ServerState.Starting
            try {
                withContext(Dispatchers.IO) { killStale() }
                val order = withContext(Dispatchers.IO) { updater.launchOrder() }
                var lastError = "no server version available"
                for ((version, script) in order) {
                    val p = withContext(Dispatchers.IO) { launch(script) }
                    process = p
                    watch(p)
                    if (waitReady(p, 30_000)) {
                        updater.markGood(version)
                        _state.value = ServerState.Ready(version)
                        scope.launch {
                            runCatching { updater.checkForUpdate() }
                            _pendingUpdate.value = updater.candidateVersion
                        }
                        return
                    }
                    lastError = if (p.isAlive) "server $version did not answer in time" else "server $version exited"
                    withContext(Dispatchers.IO) { stopNow(p) }
                    updater.markBad(version)
                }
                throw IllegalStateException(lastError)
            } catch (t: Throwable) {
                _state.value = ServerState.Failed(t.message ?: t.javaClass.simpleName)
                throw t
            }
        }
    }

    /** Stop the server (blocking up to a few seconds: call off the main thread). */
    fun stop() {
        process?.let { stopNow(it) }
        process = null
        _state.value = ServerState.Stopped
    }

    /** URL the player / downloader should read for a torrent file. */
    fun torrentUrl(infoHash: String, fileIdx: Int?, trackers: List<String>, download: Boolean = false): String {
        val params = buildList {
            if (download) add("download=1")
            // The addon's trackers let the server find peers much faster than DHT alone.
            trackers.forEach { add("tr=" + URLEncoder.encode(it, "UTF-8")) }
        }
        return "$BASE_URL/$infoHash/${fileIdx ?: -1}" + if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
    }

    // --- process ---------------------------------------------------------------------------

    private fun launch(script: File): Process {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val exe = File(nativeDir, "libstremionode.so")
        check(exe.exists()) { "Node launcher missing" }
        check(File(nativeDir, "libnode.so").exists()) { "Node runtime missing for this CPU" }
        val tmp = File(context.cacheDir, "stremio-server-tmp").apply { mkdirs() }
        val pb = ProcessBuilder(exe.absolutePath, script.absolutePath).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", nativeDir)
            put("APP_PATH", dir.absolutePath) // server settings + torrent cache
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("TMP", tmp.absolutePath)
            put("NO_CORS", "1")
            put("NO_HTTPS_SERVER", "1")
            put("CASTING_DISABLED", "1")
        }
        logFile.delete()
        val p = pb.start()
        pidOf(p)?.let { pidFile.writeText(it.toString()) }
        scope.launch {
            runCatching {
                logFile.bufferedWriter().use { log ->
                    p.inputStream.bufferedReader().forEachLine { line ->
                        android.util.Log.i("StremioServer", line)
                        if (logFile.length() < 2_000_000) { log.write(line); log.newLine(); log.flush() }
                    }
                }
            }
        }
        return p
    }

    private fun watch(p: Process) {
        scope.launch {
            runCatching { p.waitFor() }
            if (process === p) {
                process = null
                pidFile.delete()
                _state.value = ServerState.Stopped // the next ensureRunning() starts it again
            }
        }
    }

    private fun stopNow(p: Process) {
        runCatching { p.destroy() }
        if (!runCatching { p.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)) runCatching { p.destroyForcibly() }
        pidFile.delete()
    }

    private fun killStale() {
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: return
        val cmd = runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
        if (cmd != null && cmd.contains("libstremionode")) {
            android.os.Process.killProcess(pid)
            Thread.sleep(500)
        }
        pidFile.delete()
    }

    private fun pidOf(p: Process): Int? = runCatching {
        p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p)
    }.getOrNull()

    private suspend fun waitReady(p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            if (reachable()) return true
            delay(250)
        }
        return false
    }

    private suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$BASE_URL/settings").openConnection() as HttpURLConnection
            c.connectTimeout = 500; c.readTimeout = 1_500
            try { c.responseCode in 200..499 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    companion object {
        const val BASE_URL = "http://127.0.0.1:11470"
    }
}
