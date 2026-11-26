package app.kinostash.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.kinostash.android.App
import app.kinostash.android.ui.MainActivity
import app.kinostash.android.server.StremioServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs the download queue. It only lives while there are active
 * downloads, and it keeps the streaming server (which does the actual torrenting) running.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Timber.e(t, "DownloadService coroutine failed") },
    )
    private val store get() = OfflineDownloads.store
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val statsJobs = ConcurrentHashMap<String, Job>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()
    private var loop: Job? = null
    @Volatile private var lastStartId = 0
    private var lastFlush = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // the server may wait a while for torrent pieces
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        if (!startInForeground(buildNotification("Starting downloads…", null))) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground(buildNotification("Downloading…", null))) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastStartId = startId
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        // Not sticky: unfinished downloads continue when the app is opened again, instead of
        // Android restarting the service in the background.
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification): Boolean = runCatching {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }.onFailure { Timber.e(it, "startForeground failed") }.isSuccess

    // Android 15+ limits data-sync services to ~6 hours a day: pause cleanly, the user can resume.
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.items.value.filter { it.status.isActive }.forEach { item ->
            store.update(item.id) { it.copy(status = DownloadStatus.PAUSED, speed = 0, error = "Paused by Android's background limit. Tap resume.") }
        }
        stopSelf()
    }

    // --- Server speed caps --------------------------------------------------------------------
    // Stremio's server caps torrent speed by default (soft ~1.6 MB/s, hard ~2.5 MB/s): plenty for
    // watching, but it would limit downloads. While downloads run we raise those caps and put the
    // previous values back afterwards. Caps apply to torrents the server starts after the change.

    @Volatile private var savedCaps: org.json.JSONObject? = null

    private fun boostServerSpeed(base: String) {
        if (savedCaps != null) return
        runCatching {
            val current = http.newCall(Request.Builder().url("$base/settings").build()).execute().use { r ->
                org.json.JSONObject(r.body?.string() ?: "{}").optJSONObject("values") ?: org.json.JSONObject()
            }
            val saved = org.json.JSONObject()
            for (k in SPEED_KEYS) saved.put(k, current.opt(k) ?: org.json.JSONObject.NULL)
            postSettings(base, org.json.JSONObject().apply {
                put("btDownloadSpeedSoftLimit", BOOST_SPEED)
                put("btDownloadSpeedHardLimit", BOOST_SPEED)
            })
            savedCaps = saved
            serverBaseForCaps = base
        }.onFailure { Timber.w(it, "couldn't raise server speed caps") }
    }

    @Volatile private var serverBaseForCaps: String? = null

    private fun restoreServerSpeed() {
        val saved = savedCaps ?: return
        val base = serverBaseForCaps ?: return
        savedCaps = null
        runCatching {
            val body = org.json.JSONObject()
            for (k in SPEED_KEYS) {
                val v = saved.opt(k)
                if (v != null && v != org.json.JSONObject.NULL) body.put(k, v)
            }
            if (body.length() > 0) postSettings(base, body)
        }.onFailure { Timber.w(it, "couldn't restore server speed caps") }
    }

    private fun postSettings(base: String, body: org.json.JSONObject) {
        val req = Request.Builder().url("$base/settings")
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        http.newCall(req).execute().close()
    }

    override fun onDestroy() {
        calls.values.forEach { runCatching { it.cancel() } }
        scope.cancel()
        if (savedCaps != null) kotlin.concurrent.thread(name = "restore-speed-caps") { restoreServerSpeed() }
        store.flush(force = true)
        super.onDestroy()
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            runCatching { schedule() }.onFailure { Timber.e(it, "download schedule failed") }
            val now = System.currentTimeMillis()
            if (now - lastFlush > 5_000) { store.flush(); lastFlush = now }

            val active = store.items.value.filter { it.status.isActive }
            if (active.isEmpty() && jobs.values.none { it.isActive }) {
                store.flush(force = true)
                restoreServerSpeed()
                // Only stop if no new start request arrived meanwhile.
                if (stopSelfResult(lastStartId)) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    return
                }
            } else {
                updateNotification(active)
            }
            delay(1_000)
        }
    }

    private fun networkAllowed(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !OfflineDownloads.wifiOnly.value || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun stopJob(id: String) {
        statsJobs.remove(id)?.cancel()
        calls.remove(id)?.let { runCatching { it.cancel() } }
        jobs.remove(id)?.cancel()
    }

    private fun schedule() {
        val items = store.items.value
        // Stop transfers for anything paused, finished or deleted.
        jobs.keys.forEach { id ->
            val item = items.find { it.id == id }
            if (item == null || !item.status.isActive) stopJob(id)
        }

        val netOk = networkAllowed()
        val streaming = OfflineDownloads.streaming.value
        val active = items.filter { it.status.isActive }.sortedBy { it.addedAt }
        active.forEachIndexed { index, item ->
            when {
                streaming -> {
                    // Give the video being watched all the bandwidth; resume when the player closes.
                    stopJob(item.id)
                    if (item.status != DownloadStatus.QUEUED || item.error != STREAMING_NOTE) {
                        store.update(item.id, persist = false) { it.copy(status = DownloadStatus.QUEUED, speed = 0, error = STREAMING_NOTE) }
                    }
                }
                !netOk -> {
                    stopJob(item.id)
                    if (item.status != DownloadStatus.WAITING_FOR_WIFI) {
                        store.update(item.id, persist = false) { it.copy(status = DownloadStatus.WAITING_FOR_WIFI, speed = 0) }
                    }
                }
                index >= OfflineDownloads.MAX_ACTIVE -> {
                    stopJob(item.id)
                    if (item.status != DownloadStatus.QUEUED) {
                        store.update(item.id, persist = false) { it.copy(status = DownloadStatus.QUEUED, speed = 0) }
                    }
                }
                jobs[item.id]?.isActive == true -> Unit
                (retryAt[item.id] ?: 0L) > System.currentTimeMillis() -> Unit
                else -> {
                    val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { download(item.id) }
                    jobs[item.id] = job
                    job.start()
                }
            }
        }
    }

    private suspend fun resolveUrl(item: DownloadItem): String {
        if (!item.sourceUrl.startsWith(StremioServer.BASE_URL)) return item.sourceUrl
        // Torrent: make sure Stremio's streaming server is up, then read the whole file from it.
        val server = (application as App).server
        server.ensureRunning()
        boostServerSpeed(StremioServer.BASE_URL)
        val hash = item.infoHash ?: throw IOException("missing info hash")
        return server.torrentUrl(hash, item.fileIdx, item.trackers, download = true)
    }

    private suspend fun download(id: String) {
        val start = store.get(id) ?: return
        val dir = OfflineDownloads.itemDir(this, id).apply { mkdirs() }
        val part = File(dir, "download.part")
        try {
            store.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
            val url = resolveUrl(start)
            var done = if (part.exists()) part.length() else 0L
            start.infoHash?.let { hash ->
                statsJobs.remove(id)?.cancel()
                statsJobs[id] = scope.launch(Dispatchers.IO) { pollTorrentStats(id, url, hash) }
            }
            val req = Request.Builder().url(url).header("Range", "bytes=$done-").build()
            val call = http.newCall(req)
            calls[id] = call
            call.execute().use { resp ->
                if (resp.code == 416 && done > 0) { // we already have everything
                    finish(id, part, done, resp.header("Content-Type"))
                    return
                }
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                val append = resp.code == 206 && done > 0
                if (!append) done = 0 // server ignored the range: start over
                val total = when {
                    resp.code == 206 -> resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: (done + body.contentLength())
                    else -> body.contentLength()
                }
                store.update(id) { it.copy(bytesTotal = if (total > 0) total else it.bytesTotal, bytesDone = done) }

                val buf = ByteArray(256 * 1024)
                var lastTick = System.currentTimeMillis()
                var bytesSinceTick = 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            bytesSinceTick += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 1_000) {
                                val speed = bytesSinceTick * 1000 / (now - lastTick)
                                lastTick = now; bytesSinceTick = 0
                                failures.remove(id) // making progress
                                store.update(id, persist = false) {
                                    it.copy(bytesDone = done, speed = speed, status = DownloadStatus.DOWNLOADING, error = null)
                                }
                                if (store.get(id)?.status?.isActive != true) return // paused / deleted
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("Connection ended early (${done}/${total})")
                finish(id, part, done, resp.header("Content-Type"))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val current = store.get(id) ?: return
            if (!current.status.isActive) return // cancelled because it was paused/deleted
            // Stopped on purpose (streaming started / Wi-Fi lost): not a failure, no retry backoff.
            if (OfflineDownloads.streaming.value || !networkAllowed()) {
                store.update(id, persist = false) { it.copy(bytesDone = part.length(), speed = 0) }
                return
            }
            val count = (failures[id] ?: 0) + 1
            failures[id] = count
            Timber.w(t, "download %s failed (attempt %d)", id, count)
            if (count >= 8) {
                store.update(id) { it.copy(status = DownloadStatus.FAILED, speed = 0, error = t.message ?: t.javaClass.simpleName) }
            } else {
                retryAt[id] = System.currentTimeMillis() + 10_000L * count
                store.update(id) {
                    it.copy(status = DownloadStatus.QUEUED, speed = 0, bytesDone = part.length(), error = "Retrying… (${t.message ?: "connection problem"})")
                }
            }
        } finally {
            calls.remove(id)
            statsJobs.remove(id)?.cancel()
            jobs.remove(id)
            store.update(id, persist = false) { it.copy(peers = -1, torrentSpeed = 0) }
        }
    }

    /** Poll the streaming server's torrent stats so the UI can show peers/speed while data trickles in. */
    private suspend fun pollTorrentStats(id: String, streamUrl: String, hash: String) {
        val base = streamUrl.substringBefore("?").substringBefore("/$hash")
        val started = System.currentTimeMillis()
        while (true) {
            runCatching {
                http.newCall(Request.Builder().url("$base/$hash/stats.json").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use
                    val o = org.json.JSONObject(r.body?.string() ?: return@use)
                    val peers = o.optInt("peers", -1)
                    val speed = o.optDouble("downloadSpeed", 0.0).toLong()
                    val stalled = store.get(id)?.bytesDone == 0L && System.currentTimeMillis() - started > 180_000
                    store.update(id, persist = false) {
                        it.copy(
                            peers = peers, torrentSpeed = speed,
                            error = if (stalled && peers <= 0) "No peers found for this torrent yet. Try a stream with more seeders." else it.error,
                        )
                    }
                }
            }
            delay(3_000)
        }
    }

    private fun finish(id: String, part: File, size: Long, contentType: String?) {
        val item = store.get(id) ?: return
        val ext = extensionFor(item, contentType)
        val base = (item.filename?.substringBeforeLast('.') ?: item.title)
            .replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(120).ifBlank { "video" }
        val target = File(part.parentFile, "$base.$ext")
        if (!part.renameTo(target)) throw IOException("Couldn't save file")
        store.update(id) {
            it.copy(status = DownloadStatus.COMPLETED, filePath = target.absolutePath, bytesDone = size, bytesTotal = size, speed = 0, error = null)
        }
        failures.remove(id)
        notifyDone(item.title)
        releaseTorrent(item)
    }

    /**
     * Tell the streaming server we're done with this torrent, so its own cached copy is no longer
     * protected and its cache cleaner can free the space (we keep our own copy of the file).
     */
    private fun releaseTorrent(item: DownloadItem) {
        val hash = item.infoHash ?: return
        if (store.items.value.any { it.infoHash == hash && it.id != item.id && it.status.isActive }) return
        runCatching {
            http.newCall(Request.Builder().url("${StremioServer.BASE_URL}/$hash/remove").build()).execute().close()
        }.onFailure { Timber.w(it, "couldn't release torrent %s", hash) }
    }

    private fun extensionFor(item: DownloadItem, contentType: String?): String {
        item.filename?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 }?.let { return it.lowercase(Locale.US) }
        return when (contentType?.substringBefore(';')?.trim()?.lowercase(Locale.US)) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/mp2t" -> "ts"
            else -> item.sourceUrl.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                .takeIf { it.length in 2..4 } ?: "mkv"
        }
    }

    private fun updateNotification(active: List<DownloadItem>) {
        val downloading = active.filter { it.status == DownloadStatus.DOWNLOADING }
        val done = active.sumOf { it.bytesDone }
        val total = active.sumOf { it.bytesTotal }
        val text = when {
            active.all { it.status == DownloadStatus.WAITING_FOR_WIFI } -> "Waiting for Wi-Fi (${active.size})"
            downloading.isEmpty() -> "Preparing ${active.size} download(s)…"
            else -> "${downloading.size} downloading · ${formatBytes(downloading.sumOf { it.speed })}/s"
        }
        val pct = if (total > 0) (done * 100 / total).toInt() else null
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, pct)) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_DOWNLOADS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String, percent: Int?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .apply { if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()

    private fun notifyDone(title: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(title.hashCode(), n) }
    }

    companion object {
        private const val CHANNEL_ID = "offline_downloads"
        private const val NOTIFICATION_ID = 11471
        private val SPEED_KEYS = listOf("btDownloadSpeedSoftLimit", "btDownloadSpeedHardLimit")
        private const val BOOST_SPEED = 100L * 1024 * 1024 // 100 MB/s, i.e. effectively uncapped
        const val STREAMING_NOTE = "Paused while you're streaming"
        const val EXTRA_OPEN_DOWNLOADS = "app.kinostash.android.OPEN_DOWNLOADS"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
