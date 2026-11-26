package app.kinostash.android.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.kinostash.android.App
import app.kinostash.android.data.StreamItem
import app.kinostash.android.data.WatchEntry
import app.kinostash.android.downloads.DownloadItem
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.downloads.formatBytes
import app.kinostash.android.server.StremioServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What to play, plus what to remember it as in watch history. */
data class PlayRequest(
    val stream: StreamItem?,
    val title: String,
    val metaType: String,
    val metaId: String,
    val videoId: String,
    val metaName: String,
    val poster: String?,
    val label: String?,
    val filePath: String? = null,
    val downloadId: String? = null,
)

/** Fullscreen player: torrents through Stremio's server, direct links, and downloaded files. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var status: TextView
    private lateinit var req: PlayRequest
    private var online = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        req = pending ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0x99000000.toInt())
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
            text = req.title
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { val m = (20 * resources.displayMetrics.density).toInt(); setMargins(m, m, m, m) })
        setContentView(root)

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(120_000) // torrent pieces can take a while
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build().also { p ->
                view.player = p
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        show("Playback error: ${error.errorCodeName}")
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) { saveProgress(force = true); finish() }
                    }
                })
            }
        lifecycleScope.launch { prepare() }
    }

    private suspend fun prepare() {
        val s = req.stream
        val local = req.filePath?.let(::File)?.takeIf { it.exists() } ?: s?.let { OfflineDownloads.completedFileFor(it) }
        val subsJob = lifecycleScope.async {
            withTimeoutOrNull(5_000) { runCatching { App.instance.addons.subtitles(req.metaType, req.videoId) }.getOrNull() }.orEmpty()
        }
        val uri: Uri = when {
            local != null -> Uri.fromFile(local)
            s?.infoHash != null -> {
                online = true
                OfflineDownloads.setStreaming(this, true) // downloads step aside while you watch
                show("Starting Stremio streaming server…")
                val ok = runCatching { App.instance.server.ensureRunning() }
                if (ok.isFailure) { show("Streaming server failed: ${ok.exceptionOrNull()?.message}"); return }
                show("Finding peers…")
                Uri.parse(App.instance.server.torrentUrl(s.infoHash, s.fileIdx, s.trackers))
            }
            s?.url != null -> { online = true; OfflineDownloads.setStreaming(this, true); Uri.parse(s.url) }
            else -> { show("Nothing to play"); return }
        }
        val subtitles = subsJob.await().distinctBy { it.url }.take(40).map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(if (sub.url.substringBefore('?').endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(sub.lang)
                .setLabel(sub.lang)
                .setSelectionFlags(0)
                .build()
        }
        val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subtitles).build()
        val p = player ?: return
        p.setMediaItem(item)
        p.prepare()
        val resume = req.downloadId?.let { id -> OfflineDownloads.store.get(id)?.positionMs }?.takeIf { it > 0 }
            ?: App.instance.history.positionFor(req.videoId)
        if (resume > 0) p.seekTo(resume)
        p.playWhenReady = true
        if (s?.infoHash != null && local == null) watchTorrent(s.infoHash)
        else hideStatusSoon()
        trackProgress()
    }

    /** While buffering a torrent, show peers and speed from the server's stats. */
    private fun watchTorrent(hash: String) = lifecycleScope.launch {
        while (isActive) {
            val p = player ?: break
            val buffering = p.playbackState == Player.STATE_BUFFERING || p.playbackState == Player.STATE_IDLE
            if (buffering && p.playerError == null) {
                val stats = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = URL("${StremioServer.BASE_URL}/$hash/stats.json").openConnection() as HttpURLConnection
                        c.connectTimeout = 1_000; c.readTimeout = 2_000
                        try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
                    }.getOrNull()
                }
                if (stats != null) show("Buffering · ${stats.optInt("peers")} peers · ${formatBytes(stats.optDouble("downloadSpeed").toLong())}/s")
            } else if (p.playerError == null) {
                status.visibility = View.GONE
            }
            delay(1_000)
        }
    }

    private fun trackProgress() = lifecycleScope.launch {
        while (isActive) { delay(5_000); saveProgress() }
    }

    private fun saveProgress(force: Boolean = false) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val pos = p.currentPosition
        App.instance.history.update(
            WatchEntry(req.videoId, req.metaId, req.metaType, req.metaName, req.poster, req.label, pos, dur, System.currentTimeMillis()),
            force = force,
        )
        req.downloadId?.let { OfflineDownloads.savePosition(it, if (dur - pos < 60_000) 0 else pos) }
    }

    private fun show(text: String) { status.text = text; status.visibility = View.VISIBLE }

    private fun hideStatusSoon() = lifecycleScope.launch { delay(3_000); status.visibility = View.GONE }

    override fun onStop() {
        super.onStop()
        saveProgress(force = true)
        OfflineDownloads.store.flush(force = true)
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        if (online) OfflineDownloads.setStreaming(this, false)
        super.onDestroy()
    }

    companion object {
        // The request is handed over in memory (it holds a whole stream description).
        @Volatile private var pending: PlayRequest? = null

        fun start(ctx: Context, req: PlayRequest) {
            pending = req
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
        }

        fun startDownload(ctx: Context, d: DownloadItem) = start(
            ctx,
            PlayRequest(
                stream = null, title = d.title, metaType = d.metaType ?: "movie", metaId = d.metaId ?: d.id,
                videoId = d.videoId ?: d.id, metaName = d.showName ?: d.title, poster = d.poster,
                label = d.episodeTitle, filePath = d.filePath, downloadId = d.id,
            ),
        )
    }
}
