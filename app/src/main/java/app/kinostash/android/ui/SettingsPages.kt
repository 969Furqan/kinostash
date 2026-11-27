package app.kinostash.android.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinostash.android.App
import app.kinostash.android.data.PlayerChoice
import app.kinostash.android.downloads.OfflineDownloads

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Text(label, fontSize = 14.sp)
    Slider(
        value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps,
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp)
    }
}

@Composable
fun GeneralSettings() {
    val s = App.instance.settings
    var start by remember { mutableStateOf(s.startPage) }
    var quality by remember { mutableStateOf(s.quality) }
    Section("Open the app on") {
        listOf("home" to "Home", "library" to "Library", "downloads" to "Downloads").forEach { (v, l) ->
            ChoiceRow(l, start == v) { start = v; s.startPage = v }
        }
        Text("Takes effect the next time the app starts.", color = Muted, fontSize = 12.sp)
    }
    Section("Preferred quality") {
        Text(
            "Used by \"Play best\", \"Download best\" and season downloads. If it isn't available, the next lower quality " +
                "is used (then higher), and among those the source with the most seeders, for the fastest start and downloads.",
            color = Muted, fontSize = 12.sp,
        )
        app.kinostash.android.data.QualityPref.entries.forEach { q -> ChoiceRow(q.label, quality == q) { quality = q; s.quality = q } }
    }
}

@Composable
fun PlayerSettings() {
    val s = App.instance.settings
    var player by remember { mutableStateOf(s.player) }
    var step by remember { mutableFloatStateOf(s.seekStepSec.toFloat()) }
    var max by remember { mutableFloatStateOf(s.seekMaxSec.toFloat()) }
    var bright by remember { mutableStateOf(s.swipeBrightness) }
    var vol by remember { mutableStateOf(s.swipeVolume) }
    var seekSwipe by remember { mutableStateOf(s.swipeSeek) }
    var resume by remember { mutableStateOf(s.autoResume) }

    Section("Play with") {
        Text("Streams and downloads open in this player. External players get the stream from Kinostash's streaming server.", color = Muted, fontSize = 12.sp)
        PlayerChoice.entries.forEach { c -> ChoiceRow(c.label, player == c) { player = c; s.player = c } }
    }
    Section("Double-tap to seek") {
        Text("Double-tap the left or right side to skip. Keep tapping to skip further.", color = Muted, fontSize = 12.sp)
        LabeledSlider("Skip per tap: ${step.toInt()} s", step, 5f..60f, steps = 10, onChange = {
            step = it; s.seekStepSec = it.toInt()
            if (max < it) { max = it; s.seekMaxSec = it.toInt() }
        })
        LabeledSlider("Maximum per gesture: ${max.toInt()} s", max, step..300f, onChange = { max = it; s.seekMaxSec = it.toInt() })
    }
    Section("Gestures") {
        Text("Turn these off if you swipe by accident. Brightness and volume can still be changed with your phone's buttons and quick settings.", color = Muted, fontSize = 12.sp)
        ToggleRow("Swipe on the left for brightness", "Slide up or down on the left half of the screen.", bright) { bright = it; s.swipeBrightness = it }
        ToggleRow("Swipe on the right for volume", "Slide up or down on the right half of the screen.", vol) { vol = it; s.swipeVolume = it }
        ToggleRow("Swipe sideways to seek", "Slide left or right anywhere to scrub back or forward; release to jump.", seekSwipe) { seekSwipe = it; s.swipeSeek = it }
    }
    Section("Resume") {
        ToggleRow("Continue where I left off", "Start each episode or movie from its saved position.", resume) { resume = it; s.autoResume = it }
    }
}

private val LANGUAGES = listOf(
    "" to "Off by default", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
    "pt" to "Portuguese", "ru" to "Russian", "ar" to "Arabic", "hi" to "Hindi", "ur" to "Urdu", "tr" to "Turkish",
    "nl" to "Dutch", "pl" to "Polish", "sv" to "Swedish", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
    "id" to "Indonesian", "fa" to "Persian", "bn" to "Bengali",
)
internal val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFFFFEB3B, 0xFFFFB547, 0xFF8FC7FF, 0xFF7CFC9A, 0xFFFF8A80, 0xFF000000).map { it.toInt() }
internal val BG_COLORS: List<Int> = listOf(0x00000000L, 0x66000000L, 0xAA000000L, 0xFF000000L, 0x99FFFFFFL).map { it.toInt() }

@Composable
internal fun Swatches(colors: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(colors) { c ->
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (c ushr 24 == 0) Color.Transparent else Color(c))
                    .border(if (c == selected) 3.dp else 1.dp, if (c == selected) Accent else Muted, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) { if (c ushr 24 == 0) Text("∅", color = Muted) }
        }
    }
}

@Composable
fun SubtitleSettings() {
    val s = App.instance.settings
    var lang by remember { mutableStateOf(s.subtitleLanguage) }
    var size by remember { mutableFloatStateOf(s.subtitleSizePercent.toFloat()) }
    var color by remember { mutableIntStateOf(s.subtitleTextColor) }
    var bg by remember { mutableIntStateOf(s.subtitleBackgroundColor) }
    var outline by remember { mutableStateOf(s.subtitleOutline) }
    var pos by remember { mutableFloatStateOf(s.subtitlePositionPercent.toFloat()) }
    var offX by remember { mutableFloatStateOf(s.subtitleOffsetXPercent.toFloat()) }
    var width by remember { mutableFloatStateOf(s.subtitleWidthPercent.toFloat()) }
    var delay by remember { mutableFloatStateOf(s.subtitleDelayMs.toFloat()) }

    // Live preview
    Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A3140)), contentAlignment = Alignment.BottomCenter) {
        Text(
            "This is how subtitles will look",
            color = Color(color), fontSize = (16 * size / 100).sp, fontWeight = FontWeight.Medium,
            style = if (outline) TextStyle(shadow = Shadow(Color.Black, blurRadius = 4f)) else TextStyle.Default,
            modifier = Modifier.padding(bottom = (pos * 1.2f).dp)
                .graphicsLayer { scaleX = width / 100f; translationX = offX / 100f * 300f }.background(Color(bg)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    Section("Default language") {
        Text("Subtitles in this language are turned on automatically when available.", color = Muted, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LANGUAGES) { (code, name) ->
                FilterChip(lang == code, { lang = code; s.subtitleLanguage = code }, { Text(name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentDim))
            }
        }
    }
    Section("Style") {
        LabeledSlider("Size: ${size.toInt()}%", size, 50f..250f, onChange = { size = it; s.subtitleSizePercent = it.toInt() })
        Text("Text colour", fontSize = 14.sp)
        Swatches(TEXT_COLORS, color) { color = it; s.subtitleTextColor = it }
        Text("Background", fontSize = 14.sp)
        Swatches(BG_COLORS, bg) { bg = it; s.subtitleBackgroundColor = it }
        ToggleRow("Outline", "A dark edge around the letters for readability.", outline) { outline = it; s.subtitleOutline = it }
        LabeledSlider("Position: ${pos.toInt()}% from the bottom", pos, 0f..40f, onChange = { pos = it; s.subtitlePositionPercent = it.toInt() })
        LabeledSlider("Horizontal position: ${"%+d".format(offX.toInt())}%", offX, -40f..40f, onChange = { offX = it; s.subtitleOffsetXPercent = it.toInt() })
        LabeledSlider("Text width: ${width.toInt()}% (− squeeze, + stretch)", width, 50f..150f, onChange = { width = it; s.subtitleWidthPercent = it.toInt() })
        Text("You can also change all of this while watching (CC button → Style).", color = Muted, fontSize = 12.sp)
    }
    Section("Timing") {
        LabeledSlider(
            "Default delay: ${"%+.1f".format(delay / 1000)} s (− earlier, + later)", delay, -5000f..5000f, steps = 39,
            onChange = { delay = (it / 250).toInt() * 250f; s.subtitleDelayMs = delay.toInt() },
        )
        Text("You can also shift subtitles while watching (CC button). Delay works for addon subtitles.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun DownloadSettings() {
    val ctx = LocalContext.current
    val wifi by OfflineDownloads.wifiOnly.collectAsState()
    Section("Network") {
        ToggleRow("Download on Wi-Fi only", "Downloads pause on mobile data and continue on Wi-Fi.", wifi) { OfflineDownloads.setWifiOnly(ctx, it) }
    }
    Section("Storage") {
        Text(OfflineDownloads.rootDir(ctx).absolutePath, color = Muted, fontSize = 12.sp)
        Text("Downloads are stored in Kinostash's own folder and are removed if you uninstall the app.", color = Muted, fontSize = 12.sp)
    }
}
