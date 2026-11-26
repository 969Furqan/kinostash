package app.kinostash.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFB547)       // warm amber: "stash" / film-reel gold
val AccentDim = Color(0xFF7A5A26)
val Bg = Color(0xFF0E0F13)
val Surface1 = Color(0xFF17191F)
val Surface2 = Color(0xFF20232B)
val Muted = Color(0xFFA3A8B4)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1C1300),
    secondary = Color(0xFF8FC7FF),
    background = Bg,
    surface = Surface1,
    surfaceVariant = Surface2,
    onBackground = Color(0xFFECEDEF),
    onSurface = Color(0xFFECEDEF),
    onSurfaceVariant = Muted,
)

@Composable
fun KinostashTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
