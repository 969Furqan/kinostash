package app.kinostash.android.player

import android.graphics.Color as AColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.kinostash.android.App
import app.kinostash.android.data.StreamItem
import app.kinostash.android.ui.Accent
import app.kinostash.android.ui.Muted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Sheet { NONE, SUBTITLES, STYLE, AUDIO, SOURCES, SPEED }

@OptIn(UnstableApi::class)
@Composable
fun PlayerUi(a: PlayerActivity) {
    val settings = App.instance.settings
    var controls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var resize by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Double-tap seek feedback
    var seekSide by remember { mutableIntStateOf(0) }
    var seekTotal by remember { mutableIntStateOf(0) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    // Brightness / volume HUD
    var hud by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) }
    // Horizontal swipe-to-seek: (start position, target position) while dragging
    var scrub by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    fun touch() { lastInteraction = System.currentTimeMillis() }

    LaunchedEffect(controls, lastInteraction, a.isPlaying.value, sheet) {
        if (controls && a.isPlaying.value && sheet == Sheet.NONE) { delay(3_500); controls = false }
    }
    LaunchedEffect(lastSeekAt) { if (lastSeekAt > 0) { delay(900); seekSide = 0; seekTotal = 0 } }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(a.resumedFrom.longValue) { if (a.resumedFrom.longValue > 0) { delay(8_000); a.resumedFrom.longValue = 0 } }

    fun doubleTapSeek(side: Int) {
        val step = settings.seekStepSec.coerceIn(1, 600)
        val max = settings.seekMaxSec.coerceAtLeast(step)
        val now = System.currentTimeMillis()
        if (seekSide != side || now - lastSeekAt > 900) { seekSide = side; seekTotal = 0 }
        if (seekTotal + step > max) { lastSeekAt = now; return }
        seekTotal += step
        lastSeekAt = now
        a.seekBy(side * step * 1000L)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    player = a.player
                }
            },
            update = { v ->
                v.resizeMode = resize
                // The built-in subtitle view sits inside the video frame and is resized with it; we draw our own.
                v.subtitleView?.visibility = android.view.View.GONE
            },
        )

        // Subtitles, full-screen and independent of the video's resize mode.
        val styleVersion = a.subStyleVersion.intValue
        val cues = a.cues.value
        val showSample = sheet == Sheet.STYLE && cues.isEmpty()
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> SubtitleView(ctx) },
            update = { v ->
                @Suppress("UNUSED_EXPRESSION") styleVersion
                v.setApplyEmbeddedStyles(false)
                v.setApplyEmbeddedFontSizes(false)
                v.setStyle(
                    CaptionStyleCompat(
                        settings.subtitleTextColor, settings.subtitleBackgroundColor, AColor.TRANSPARENT,
                        if (settings.subtitleOutline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE,
                        AColor.BLACK, null,
                    ),
                )
                v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * settings.subtitleSizePercent / 100f)
                v.setBottomPaddingFraction(settings.subtitlePositionPercent / 100f)
                v.scaleX = settings.subtitleWidthPercent / 100f
                v.post { v.pivotX = v.width / 2f; v.translationX = v.width * settings.subtitleOffsetXPercent / 100f }
                v.setCues(
                    if (showSample) listOf(androidx.media3.common.text.Cue.Builder().setText("Subtitle preview\nThis is how subtitles look").build())
                    else cues,
                )
            },
        )

        // Gesture layer: tap = controls, double-tap sides = seek, vertical swipe sides = brightness / volume.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { o ->
                            if (sheet == Sheet.STYLE) { sheet = Sheet.NONE; return@detectTapGestures }
                            val side = if (o.x < size.width / 2) -1 else 1
                            if (seekSide == side && System.currentTimeMillis() - lastSeekAt < 900) doubleTapSeek(side)
                            else { controls = !controls; touch() }
                        },
                        onDoubleTap = { o -> doubleTapSeek(if (o.x < size.width / 2) -1 else 1) },
                    )
                }
                .pointerInput(Unit) {
                    var left = true
                    var value = 0f
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            left = o.x < size.width / 2
                            value = if (left) a.brightness() else a.volume()
                        },
                        onVerticalDrag = { change, dy ->
                            val enabled = if (left) settings.swipeBrightness else settings.swipeVolume
                            if (enabled) {
                                change.consume()
                                value = (value - dy / size.height * 1.3f).coerceIn(0f, 1f)
                                if (left) a.setBrightness(value) else a.setVolume(value)
                                hud = (if (left) Icons.Outlined.BrightnessMedium else Icons.Outlined.VolumeUp) to value
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    var startMs = 0L
                    var dxTotal = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { startMs = a.player?.currentPosition ?: 0L; dxTotal = 0f },
                        onDragEnd = { scrub?.let { (_, target) -> a.seekTo(target) }; scrub = null },
                        onDragCancel = { scrub = null },
                        onHorizontalDrag = { change, dx ->
                            if (!settings.swipeSeek || sheet == Sheet.STYLE) return@detectHorizontalDragGestures
                            change.consume()
                            dxTotal += dx
                            val duration = a.player?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
                            // A full screen-width swipe = 2 minutes; long swipes speed up slightly.
                            val frac = dxTotal / size.width
                            val deltaMs = (frac * 120_000 * (1 + kotlin.math.abs(frac))).toLong()
                            scrub = startMs to (startMs + deltaMs).coerceIn(0L, duration)
                        },
                    )
                },
        )

        // Swipe-to-seek indicator
        scrub?.let { (start, target) ->
            val d = target - start
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text((if (d >= 0) "+" else "−") + fmt(kotlin.math.abs(d)), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("${fmt(target)} / ${fmt(a.player?.duration?.coerceAtLeast(0) ?: 0)}", color = Color.White, fontSize = 13.sp)
            }
        }

        // Double-tap seek bubbles
        if (seekSide != 0) {
            Box(
                Modifier.align(if (seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 48.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text((if (seekSide < 0) "−" else "+") + "${seekTotal}s", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Brightness / volume HUD
        hud?.let { (icon, v) ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color(0xAA000000)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, null, tint = Color.White)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(Accent))
                }
                Text("${(v * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
            }
        }

        // Centre: buffering spinner with status + play/pause, or the big play/pause when controls are shown.
        val err = a.error.value
        val showBuffer = (a.isBuffering.value || a.status.value != null) && hud == null && seekSide == 0
        if (err != null) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { sheet = Sheet.SOURCES }) { Text("Try another source", color = Accent) }
            }
        } else if (showBuffer) {
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(84.dp))
                IconButton(onClick = { a.togglePlay(); touch() }, modifier = Modifier.size(64.dp)) {
                    Icon(if (a.player?.playWhenReady == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play/pause", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            a.status.value?.let {
                Text(
                    it, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(top = 130.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0x88000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        } else if (controls && sheet != Sheet.STYLE && hud == null && seekSide == 0 && scrub == null) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Outlined.Replay10, "Back") { a.seekBy(-settings.seekStepSec * 1000L); touch() }
                RoundButton(if (a.isPlaying.value) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Play/pause", big = true) { a.togglePlay(); touch() }
                RoundButton(Icons.Outlined.Forward10, "Forward") { a.seekBy(settings.seekStepSec * 1000L); touch() }
            }
        }

        // Top and bottom bars
        AnimatedVisibility(controls && sheet != Sheet.STYLE, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { a.finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
                Text(a.title.value, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { sheet = Sheet.SUBTITLES; touch() }) { Icon(Icons.Outlined.ClosedCaption, "Subtitles", tint = Color.White) }
                IconButton(onClick = { sheet = Sheet.AUDIO; touch() }) { Icon(Icons.Outlined.Audiotrack, "Audio", tint = Color.White) }
                if (a.req.stream != null || a.req.metaId.isNotBlank()) {
                    IconButton(onClick = { sheet = Sheet.SOURCES; touch() }) { Icon(Icons.Outlined.SwapHoriz, "Change source", tint = Color.White) }
                }
                IconButton(onClick = { sheet = Sheet.SPEED; touch() }) { Icon(Icons.Outlined.Speed, "Speed", tint = Color.White) }
                IconButton(onClick = {
                    resize = when (resize) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }; touch()
                }) { Icon(Icons.Outlined.AspectRatio, "Fit", tint = Color.White) }
                IconButton(onClick = { a.openExternally() }) { Icon(Icons.Outlined.OpenInNew, "Open in another player", tint = Color.White) }
            }
        }
        AnimatedVisibility(controls && sheet != Sheet.STYLE, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(Color(0x88000000)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (a.resumedFrom.longValue > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Resumed at ${fmt(a.resumedFrom.longValue)}", color = Color.White, fontSize = 13.sp)
                        TextButton(onClick = { a.startOver(); touch() }) { Text("Start over", color = Accent) }
                    }
                }
                SeekBar(a) { touch() }
            }
        }

        when (sheet) {
            Sheet.SUBTITLES -> SubtitleDialog(a, onStyle = { sheet = Sheet.STYLE; controls = false }) { sheet = Sheet.NONE }
            Sheet.STYLE -> SubtitleStylePanel(a) { sheet = Sheet.NONE }
            Sheet.AUDIO -> TrackDialog(a, C.TRACK_TYPE_AUDIO, "Audio") { sheet = Sheet.NONE }
            Sheet.SOURCES -> SourcesDialog(a) { sheet = Sheet.NONE }
            Sheet.SPEED -> SpeedDialog(a) { sheet = Sheet.NONE }
            Sheet.NONE -> Unit
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 72.dp else 56.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(if (big) 42.dp else 30.dp)) }
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(a: PlayerActivity, onTouch: () -> Unit) {
    val dur = a.durationMs.longValue.coerceAtLeast(1)
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    val progress = if (scrubbing) scrub else (a.positionMs.longValue.toFloat() / dur).coerceIn(0f, 1f)
    val buffered = (a.bufferedMs.longValue.toFloat() / dur).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(fmt((progress * dur).toLong()), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
        Slider(
            value = progress,
            onValueChange = { scrubbing = true; scrub = it; onTouch() },
            onValueChangeFinished = { a.seekTo((scrub * dur).toLong()); scrubbing = false },
            modifier = Modifier.weight(1f),
            thumb = { Box(Modifier.size(14.dp).clip(CircleShape).background(Accent)) },
            track = {
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Color(0x44FFFFFF), size = size, cornerRadius = r)
                    drawRoundRect(Color(0x88FFFFFF), size = Size(size.width * buffered, size.height), cornerRadius = r)
                    drawRoundRect(Accent, size = Size(size.width * progress, size.height), cornerRadius = r)
                }
            },
            colors = SliderDefaults.colors(),
        )
        Text(fmt(dur), color = Color.White, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
    }
}

@Composable
private fun SubtitleDialog(a: PlayerActivity, onStyle: () -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = a.tracksVersion.intValue
    val tracks = a.tracks(C.TRACK_TYPE_TEXT)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Subtitles") },
        text = {
            Column {
                Text("Delay: ${a.subtitleDelayMs.intValue / 1000.0}s", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-1000, -250, 250, 1000).forEach { d ->
                        TextButton(onClick = { a.changeSubtitleDelay(d) }) { Text((if (d > 0) "+" else "−") + "${kotlin.math.abs(d) / 1000.0}s") }
                    }
                }
                Text("Delay applies to addon subtitles.", color = Muted, fontSize = 12.sp)
                TextButton(onClick = onStyle) { Text("Style: size, colour, position, width…", color = Accent) }
                Spacer(Modifier.height(8.dp))
                val more = a.availableSubs.value.groupBy { it.lang }
                    .map { (lang, subs) -> Triple(lang, PlayerActivity.languageName(lang), subs.size - a.attachedAddonCount(lang)) }
                    .filter { it.third > 0 }.sortedBy { it.second }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item { TrackRow("Off", tracks.none { it.selected }) { a.selectTrack(C.TRACK_TYPE_TEXT, null); onClose() } }
                    items(tracks) { t -> TrackRow(t.label, t.selected) { a.selectTrack(C.TRACK_TYPE_TEXT, t); onClose() } }
                    if (a.loadingSubs.value) item { Text("Downloading subtitle…", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    if (more.isNotEmpty()) {
                        item { Text("More from addons (tap to load)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                        items(more, key = { it.first }) { (lang, name, left) ->
                            TrackRow("$name  ($left)", false) { a.loadAddonSubtitle(lang); onClose() }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun TrackDialog(a: PlayerActivity, type: Int, title: String, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = a.tracksVersion.intValue
    val tracks = a.tracks(type)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (tracks.isEmpty()) item { Text("No other tracks", color = Muted) }
                items(tracks) { t -> TrackRow(t.label, t.selected) { a.selectTrack(type, t); onClose() } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SourcesDialog(a: PlayerActivity, onClose: () -> Unit) {
    var streams by remember { mutableStateOf<List<StreamItem>?>(null) }
    LaunchedEffect(Unit) { streams = runCatching { a.alternativeStreams() }.getOrDefault(emptyList()) }
    val current = a.req.stream?.key
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Change source") },
        text = {
            val list = streams
            if (list == null) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else if (list.isEmpty()) Text("No other sources found.", color = Muted)
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(list) { s ->
                    Column(
                        Modifier.fillMaxWidth().clickable { a.switchSource(s); onClose() }.padding(vertical = 8.dp),
                    ) {
                        Text(
                            listOfNotNull(s.quality?.uppercase(), s.sizeLabel, s.seeders?.let { "👤 $it" }, s.addonName).joinToString(" · ") +
                                if (s.key == current) "  (playing)" else "",
                            color = if (s.key == current) Accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(s.description, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun SpeedDialog(a: PlayerActivity, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Playback speed") },
        text = {
            Column {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                    TrackRow("${s}×", a.speed.floatValue == s) { a.setSpeed(s); onClose() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Live subtitle styling over the video: changes show immediately and are saved as your defaults. */
@Composable
private fun BoxScope.SubtitleStylePanel(a: PlayerActivity, onClose: () -> Unit) {
    val s = App.instance.settings
    var size by remember { mutableFloatStateOf(s.subtitleSizePercent.toFloat()) }
    var color by remember { mutableIntStateOf(s.subtitleTextColor) }
    var bg by remember { mutableIntStateOf(s.subtitleBackgroundColor) }
    var outline by remember { mutableStateOf(s.subtitleOutline) }
    var posY by remember { mutableFloatStateOf(s.subtitlePositionPercent.toFloat()) }
    var posX by remember { mutableFloatStateOf(s.subtitleOffsetXPercent.toFloat()) }
    var width by remember { mutableFloatStateOf(s.subtitleWidthPercent.toFloat()) }
    fun changed() { a.subStyleVersion.intValue++ }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(360.dp)
                .background(Color(0xF0101218)).pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitle style", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    s.resetSubtitleStyle()
                    size = 100f; color = s.subtitleTextColor; bg = 0; outline = true; posY = 8f; posX = 0f; width = 100f; changed()
                }) { Text("Reset", color = Muted) }
                TextButton(onClick = onClose) { Text("Done", color = Accent, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelSlider("Size", "${size.toInt()}%", size, 50f..250f) { size = it; s.subtitleSizePercent = it.toInt(); changed() }
                PanelSlider("Width", "${width.toInt()}%", width, 50f..150f) { width = it; s.subtitleWidthPercent = it.toInt(); changed() }
                Text("Below 100% squeezes wide text, above stretches it.", color = Muted, fontSize = 11.sp)
                PanelSlider("Up / down", "${posY.toInt()}%", posY, 0f..60f) { posY = it; s.subtitlePositionPercent = it.toInt(); changed() }
                PanelSlider("Left / right", "%+d%%".format(posX.toInt()), posX, -40f..40f) { posX = it; s.subtitleOffsetXPercent = it.toInt(); changed() }
                Spacer(Modifier.height(6.dp))
                Text("Text colour", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                app.kinostash.android.ui.Swatches(app.kinostash.android.ui.TEXT_COLORS, color) { color = it; s.subtitleTextColor = it; changed() }
                Spacer(Modifier.height(6.dp))
                Text("Background", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                app.kinostash.android.ui.Swatches(app.kinostash.android.ui.BG_COLORS, bg) { bg = it; s.subtitleBackgroundColor = it; changed() }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Outline", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(outline, { outline = it; s.subtitleOutline = it; changed() },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent))
                }
            }
        }
    }
}

/** A compact one-line slider: label on the left, value on the right, slider below. */
@Composable
private fun PanelSlider(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = Accent)
        }
        androidx.compose.material3.Slider(
            value = v, onValueChange = onChange, valueRange = range, modifier = Modifier.height(32.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x44FFFFFF)),
        )
    }
}
