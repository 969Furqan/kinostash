package app.kinostash.android.ui

import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Poster(
    url: String?,
    title: String,
    subtitle: String? = null,
    width: Dp = 112.dp,
    progress: Float? = null,
    rating: String? = null,
    downloaded: Boolean = false,
    watched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(Modifier.width(width).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            rating?.takeIf { it.isNotBlank() && it != "0" }?.let {
                Text(
                    "★ $it", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C1300),
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(Accent).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (downloaded || watched) {
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    if (downloaded) Badge(androidx.compose.material.icons.Icons.Outlined.DownloadDone, "Downloaded")
                    if (watched) Badge(androidx.compose.material.icons.Icons.Outlined.Check, "Watched")
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = Accent, trackColor = Color(0x66000000),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, fontSize = 11.sp, color = Muted, maxLines = 1) }
    }
}

@Composable
fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String) {
    Box(Modifier.padding(start = 3.dp).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(icon, desc, tint = Accent, modifier = Modifier.size(13.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
}

@Composable
fun Message(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, color = Muted) }
}

/** "Mon 30 Nov, 09:00" in the phone's time zone and locale. */
fun formatRelease(epochMs: Long): String =
    java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
