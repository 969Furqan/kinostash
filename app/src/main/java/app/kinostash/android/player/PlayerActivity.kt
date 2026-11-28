package app.kinostash.android.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.kinostash.android.App
import app.kinostash.android.data.PlayerChoice
import app.kinostash.android.data.StreamItem
import app.kinostash.android.data.SubtitleItem
import app.kinostash.android.data.WatchEntry
import app.kinostash.android.downloads.DownloadItem
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.downloads.formatBytes
import app.kinostash.android.server.StremioServer
import app.kinostash.android.ui.KinostashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** One selectable audio or subtitle track. */
data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

/** Fullscreen player: torrents through Stremio's server, direct links and downloaded files. */
class PlayerActivity : ComponentActivity() {
    var player: ExoPlayer? = null
        private set
    lateinit var req: PlayRequest
        private set
    private var online = false
    private var torrentWatch: Job? = null
    /** Addon subtitles attached to the player (downloaded). */
    private var addonSubs: List<SubtitleItem> = emptyList()
    /** Every subtitle the addons offer for this video; loaded on demand from the CC menu. */
    val availableSubs = mutableStateOf<List<SubtitleItem>>(emptyList())
    val loadingSubs = mutableStateOf(false)
    private var pendingSubLabel: String? = null
    private var currentUri: Uri? = null
    private var retriedWithoutSubtitles = false
    private val settings get() = App.instance.settings

    // UI state (read by PlayerUi)
    val status = mutableStateOf<String?>("Loading…")
    val error = mutableStateOf<String?>(null)
    val isPlaying = mutableStateOf(false)
    val isBuffering = mutableStateOf(true)
    val positionMs = mutableLongStateOf(0)
    val durationMs = mutableLongStateOf(0)
    val bufferedMs = mutableLongStateOf(0)
    val resumedFrom = mutableLongStateOf(0)
    val subtitleDelayMs = mutableIntStateOf(0)
    val speed = mutableFloatStateOf(1f)
    val title = mutableStateOf("")
    val tracksVersion = mutableIntStateOf(0)
    /** Current subtitle cues, drawn by our own overlay so the video's zoom/stretch doesn't resize them. */
    val cues = mutableStateOf<List<androidx.media3.common.text.Cue>>(emptyList())
    /** Bumped whenever subtitle style settings change, so the overlay restyles live. */
    val subStyleVersion = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        req = pending ?: return finish()
        title.value = req.title
        subtitleDelayMs.intValue = settings.subtitleDelayMs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(120_000) // torrent pieces can take a while
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this, KinostashRenderers(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build().also { p ->
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
                    val lang = settings.subtitleLanguage
                    if (lang.isBlank()) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else setPreferredTextLanguage(lang).setSelectUndeterminedTextLanguage(false)
                }.build()
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(e: PlaybackException) {
                        // A subtitle the player can't handle shouldn't stop the video: retry once without subtitles.
                        if (e.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK && !retriedWithoutSubtitles) {
                            retriedWithoutSubtitles = true
                            retryWithoutSubtitles()
                            return
                        }
                        val cause = generateSequence(e.cause) { it.cause }.lastOrNull()
                        error.value = "Playback error: ${e.errorCodeName}" + (cause?.message?.let { "\n${it.take(200)}" } ?: "")
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                        if (state == Player.STATE_READY) { status.value = null; error.value = null }
                        if (state == Player.STATE_ENDED) { saveProgress(force = true); finish() }
                    }
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying.value = playing }
                    override fun onTracksChanged(tracks: Tracks) { tracksVersion.intValue++; applyPendingSubtitle() }
                    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) { cues.value = cueGroup.cues }
                })
            }
        setContent { KinostashTheme { PlayerUi(this) } }
        lifecycleScope.launch { start(resumeAt = null) }
        lifecycleScope.launch {
            while (isActive) {
                player?.let { p ->
                    positionMs.longValue = p.currentPosition
                    durationMs.longValue = p.duration.takeIf { it != C.TIME_UNSET } ?: 0
                    bufferedMs.longValue = p.bufferedPosition
                }
                delay(250)
            }
        }
        lifecycleScope.launch { while (isActive) { delay(5_000); saveProgress() } }
    }

    /** Resolve what to play and start it. [resumeAt] overrides the saved position (source switches). */
    private suspend fun start(resumeAt: Long?) {
        error.value = null
        val s = req.stream
        val local = req.filePath?.let(::File)?.takeIf { it.exists() } ?: s?.let { OfflineDownloads.completedFileFor(it) }
        val uri: Uri = when {
            local != null -> Uri.fromFile(local)
            s?.infoHash != null -> {
                online = true
                OfflineDownloads.setStreaming(this, true) // downloads step aside while you watch
                status.value = "Starting streaming server…"
                val ok = runCatching { App.instance.server.ensureRunning() }
                if (ok.isFailure) { error.value = "Streaming server failed: ${ok.exceptionOrNull()?.message}"; return }
                status.value = "Finding peers…"
                Uri.parse(App.instance.server.torrentUrl(s.infoHash, s.fileIdx, s.trackers))
            }
            s?.url != null -> { online = true; OfflineDownloads.setStreaming(this, true); Uri.parse(s.url) }
            else -> { error.value = "Nothing to play"; return }
        }
        currentUri = uri
        val resume = resumeAt ?: req.downloadId?.let { id -> OfflineDownloads.store.get(id)?.positionMs }?.takeIf { it > 0 }
            ?: if (settings.autoResume) App.instance.history.positionFor(req.videoId) else 0L

        // Hand off to another player app if that's the choice.
        if (settings.player != PlayerChoice.BUILT_IN) {
            ExternalPlayer.open(this, uri, req.title, resume, settings.player)
            finish()
            return
        }

        // Start right away; addon subtitles are fetched in the background (they used to delay playback,
        // even for downloaded files, while up to 40 subtitle files were fetched one by one).
        load(uri, resume)
        if (resume > 0 && resumeAt == null) resumedFrom.longValue = resume
        if (s?.infoHash != null && local == null) watchTorrent(s.infoHash) else status.value = null
        if (resumeAt == null) lifecycleScope.launch { fetchAddonSubtitles() }
    }

    /** List the addons' subtitles; auto-attach the preferred language if the video has none built in. */
    private suspend fun fetchAddonSubtitles() {
        val list = withTimeoutOrNull(8_000) { runCatching { App.instance.addons.subtitles(req.metaType, req.videoId) }.getOrNull() }
            .orEmpty().distinctBy { it.url }
        availableSubs.value = list
        val lang = settings.subtitleLanguage
        if (lang.isBlank() || list.isEmpty()) return
        delay(1_500) // let the player report the file's own tracks
        val embedded = player?.currentTracks?.groups.orEmpty().any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { i -> sameLanguage(g.getTrackFormat(i).language, lang) }
        }
        if (embedded) return
        val preferred = list.filter { sameLanguage(it.lang, lang) }.take(2)
        if (preferred.isNotEmpty()) attachSubtitles(preferred, select = null)
    }

    private fun sameLanguage(a: String?, b: String): Boolean {
        if (a.isNullOrBlank()) return false
        fun norm(x: String) = runCatching { java.util.Locale.forLanguageTag(x).isO3Language }.getOrNull()?.ifBlank { null } ?: x.lowercase()
        return norm(a) == norm(b) || a.equals(b, ignoreCase = true)
    }

    private fun subLabel(sub: SubtitleItem, all: List<SubtitleItem>): String {
        val sameLang = all.filter { it.lang == sub.lang }
        val n = sameLang.indexOf(sub) + 1
        return languageName(sub.lang) + " · addon" + if (sameLang.size > 1) " $n" else ""
    }

    /** Download [subs] (in parallel), add them to the player and continue at the same moment. */
    private suspend fun attachSubtitles(subs: List<SubtitleItem>, select: SubtitleItem?) {
        val uri = currentUri ?: return
        val new = subs.filter { it !in addonSubs }
        if (new.isEmpty()) { select?.let { pendingSubLabel = subLabel(it, addonSubs); applyPendingSubtitle() }; return }
        loadingSubs.value = true
        val ok = kotlinx.coroutines.coroutineScope {
            new.map { sub -> async { sub to withTimeoutOrNull(10_000) { SubtitleShift.shifted(this@PlayerActivity, sub.url, subtitleDelayMs.intValue) } } }
                .awaitAll().filter { it.second != null }.map { it.first }
        }
        loadingSubs.value = false
        if (ok.isEmpty()) {
            if (select != null) android.widget.Toast.makeText(this, "Couldn't download that subtitle", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        addonSubs = addonSubs + ok
        if (select != null && select in ok) pendingSubLabel = subLabel(select, addonSubs)
        val p = player ?: return
        val params = p.trackSelectionParameters
        val playing = p.playWhenReady
        load(uri, p.currentPosition, playWhenReady = playing)
        if (pendingSubLabel == null) p.trackSelectionParameters = params
    }

    /** Load the next not-yet-attached addon subtitle in [lang] and switch to it. */
    fun loadAddonSubtitle(lang: String) {
        val next = availableSubs.value.firstOrNull { it.lang == lang && it !in addonSubs } ?: return
        lifecycleScope.launch { attachSubtitles(listOf(next), select = next) }
    }

    fun attachedAddonCount(lang: String) = addonSubs.count { it.lang == lang }

    private fun applyPendingSubtitle() {
        val label = pendingSubLabel ?: return
        val t = tracks(C.TRACK_TYPE_TEXT).firstOrNull { it.label == label } ?: return
        pendingSubLabel = null
        selectTrack(C.TRACK_TYPE_TEXT, t)
    }

    /** (Re)load [uri] at [positionMs] with the addon subtitles shifted by the current delay. */
    private suspend fun load(uri: Uri, positionMs: Long, playWhenReady: Boolean = true) {
        val delay = subtitleDelayMs.intValue
        val attached = addonSubs
        val subs = kotlinx.coroutines.coroutineScope {
            attached.map { sub -> async { sub to SubtitleShift.shifted(this@PlayerActivity, sub.url, delay) } }.awaitAll()
        }.mapNotNull { (sub, shifted) ->
            shifted ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(shifted)
                .setMimeType(if (shifted.path!!.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(sub.lang)
                .setLabel(subLabel(sub, attached))
                .build()
        }
        val p = player ?: return
        p.setMediaItem(MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subs).build(), positionMs)
        p.prepare()
        p.playWhenReady = playWhenReady
    }

    /** While buffering a torrent, show peers and speed from the server's stats. */
    private fun watchTorrent(hash: String) {
        torrentWatch?.cancel()
        torrentWatch = lifecycleScope.launch {
            while (isActive) {
                if (isBuffering.value && error.value == null) {
                    val stats = withContext(Dispatchers.IO) {
                        runCatching {
                            val c = URL("${StremioServer.BASE_URL}/$hash/stats.json").openConnection() as HttpURLConnection
                            c.connectTimeout = 1_000; c.readTimeout = 2_000
                            try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
                        }.getOrNull()
                    }
                    if (stats != null) {
                        val peers = stats.optInt("peers")
                        status.value = if (peers == 0) "Finding peers…"
                        else "$peers peers · ${formatBytes(stats.optDouble("downloadSpeed").toLong())}/s"
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun retryWithoutSubtitles() {
        val uri = currentUri ?: return
        val p = player ?: return
        val pos = p.currentPosition
        addonSubs = emptyList()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        status.value = "This file's subtitles aren't supported. Playing without subtitles…"
        lifecycleScope.launch {
            load(uri, pos)
            delay(4_000)
            if (error.value == null && status.value?.startsWith("This file") == true) status.value = null
        }
    }

    // --- Actions used by the UI ------------------------------------------------------------

    fun togglePlay() { player?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    fun startOver() { seekTo(0); resumedFrom.longValue = 0 }

    fun setSpeed(v: Float) { speed.floatValue = v; player?.setPlaybackSpeed(v) }

    fun tracks(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        val list = mutableListOf<TrackOption>()
        p.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val label = listOfNotNull(
                    f.label ?: f.language?.let(::languageName),
                    f.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 0 }?.let { "${it}ch" },
                ).joinToString(" · ").ifBlank { "Track ${list.size + 1}" }
                list += TrackOption(label, g, i, g.isTrackSelected(i))
            }
        }
        return list
    }

    fun selectTrack(type: Int, option: TrackOption?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (option?.group == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        }
        p.trackSelectionParameters = b.build()
    }

    /** Change the subtitle delay; addon subtitles are re-timed and playback continues at the same moment. */
    fun changeSubtitleDelay(deltaMs: Int) {
        subtitleDelayMs.intValue += deltaMs
        val uri = currentUri ?: return
        val pos = player?.currentPosition ?: 0
        val textOverride = player?.trackSelectionParameters
        lifecycleScope.launch {
            load(uri, pos)
            textOverride?.let { player?.trackSelectionParameters = it }
        }
    }

    /** Streams for this video, for switching the source while watching. */
    suspend fun alternativeStreams(): List<StreamItem> =
        App.instance.addons.streams(req.metaType, req.videoId).filter { it.isPlayable }
            .sortedWith(compareBy<StreamItem>({ listOf("2160p", "1080p", "720p", "480p").indexOf(it.quality).let { i -> if (i < 0) 99 else i } }, { -(it.seeders ?: 0) }))

    /** Switch to another stream and continue from the same moment. */
    fun switchSource(stream: StreamItem) {
        val pos = player?.currentPosition ?: 0
        saveProgress(force = true)
        player?.stop()
        req = req.copy(stream = stream, filePath = null, downloadId = null)
        isBuffering.value = true
        status.value = "Switching source…"
        lifecycleScope.launch { start(resumeAt = pos) }
    }

    fun openExternally() {
        val uri = currentUri ?: return
        val choice = settings.player.takeIf { it != PlayerChoice.BUILT_IN } ?: PlayerChoice.ASK
        player?.pause()
        ExternalPlayer.open(this, uri, req.title, player?.currentPosition ?: 0, choice)
    }

    // --- Brightness / volume (vertical swipes) -----------------------------------------------

    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /** Current brightness 0..1 (system value until the player changes it). */
    fun brightness(): Float {
        val w = window.attributes.screenBrightness
        if (w >= 0) return w
        return runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }

    fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }

    fun volume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).toInt(), 0)
    }

    // --- Progress ----------------------------------------------------------------------------

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

        /** "eng"/"en" -> "English" (subtitle addons mostly use 3-letter codes). */
        fun languageName(code: String): String {
            val c = code.lowercase()
            val locale = if (c.length == 3) java.util.Locale.getAvailableLocales().firstOrNull { runCatching { it.isO3Language == c }.getOrDefault(false) } else null
            val name = (locale ?: java.util.Locale.forLanguageTag(c)).getDisplayLanguage(java.util.Locale.getDefault())
            return name.takeIf { it.isNotBlank() && it.lowercase() != c }?.replaceFirstChar { it.uppercase() } ?: code
        }
    }
}
