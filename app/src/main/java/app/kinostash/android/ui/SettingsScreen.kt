package app.kinostash.android.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import app.kinostash.android.App
import app.kinostash.android.BuildConfig
import app.kinostash.android.server.ServerState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        UpdatesCard()
        AccountCard()
        AddonsCard()
        NotificationsCard()
        ServerCard()
        AboutCard()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun AccountCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = App.instance.account
    val email by account.email.collectAsState()
    var busy by remember { mutableStateOf(false) }
    Section("Stremio account (optional)") {
        if (email != null) {
            Text("Signed in as $email", color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = runCatching { App.instance.addons.replaceAll(account.addons()) }
                            busy = false
                            Toast.makeText(ctx, if (r.isSuccess) "Addons imported from your account" else "Import failed: ${r.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Import addons") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val r = runCatching { App.instance.library.merge(account.library()) }
                            busy = false
                            Toast.makeText(ctx, r.fold({ "Imported $it titles into your library" }, { "Import failed: ${it.message}" }), Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Import library") }
            }
            Row {
                OutlinedButton(onClick = { account.logout() }) { Text("Sign out") }
            }
        } else {
            Text("Sign in to import your Stremio addons and library. Kinostash only stores the login token.", color = Muted, fontSize = 13.sp)
            var mail by remember { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            OutlinedTextField(mail, { mail = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(
                enabled = !busy && mail.isNotBlank() && pass.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                onClick = {
                    busy = true
                    scope.launch {
                        val r = runCatching {
                            account.login(mail.trim(), pass)
                            App.instance.addons.replaceAll(account.addons())
                            runCatching { App.instance.library.merge(account.library()) }
                        }
                        busy = false
                        pass = ""
                        Toast.makeText(ctx, if (r.isSuccess) "Signed in, addons imported" else "Sign-in failed: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        }
    }
}

@Composable
private fun AddonsCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = App.instance.addons
    val addons by repo.addons.collectAsState()
    var url by remember { mutableStateOf("") }
    Section("Addons") {
        Text("Paste an addon link (https://…/manifest.json or stremio://…). Configure Torrentio at torrentio.strem.fun/configure and paste its link here.", color = Muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, singleLine = true, placeholder = { Text("Addon URL") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                onClick = {
                    scope.launch {
                        val r = runCatching { repo.install(url) }
                        Toast.makeText(ctx, r.fold({ "Installed ${it.name}" }, { "Couldn't install: ${it.message}" }), Toast.LENGTH_SHORT).show()
                        if (r.isSuccess) url = ""
                    }
                },
            ) { Text("Add") }
        }
        addons.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(a.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.resources.keys.joinToString(" · ") + " · v${a.version}", color = Muted, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(enabled = i > 0, onClick = { repo.move(a, up = true) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Move up") }
                IconButton(enabled = i < addons.lastIndex, onClick = { repo.move(a, up = false) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Move down") }
                IconButton(onClick = { repo.remove(a) }) { Icon(Icons.Outlined.Delete, "Remove", tint = Muted) }
            }
        }
        if (addons.isEmpty()) {
            OutlinedButton(onClick = { scope.launch { repo.ensureDefaults() } }) { Text("Install default addons") }
        }
    }
}

@Composable
private fun NotificationsCard() {
    var on by remember { mutableStateOf(App.instance.settings.episodeNotifications) }
    Section("New episodes") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notify me about new episodes", fontSize = 14.sp)
                Text("For series in your library, checked twice a day.", color = Muted, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(
                checked = on,
                onCheckedChange = { on = it; App.instance.settings.episodeNotifications = it },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent),
            )
        }
    }
}

@Composable
private fun ServerCard() {
    val ctx = LocalContext.current
    val server = App.instance.server
    val state by server.state.collectAsState()
    val pending by server.pendingUpdate.collectAsState()
    Section("Streaming server") {
        Text(
            when (val s = state) {
                is ServerState.Ready -> "Running: Stremio server ${s.version}"
                ServerState.Starting -> "Starting…"
                ServerState.Stopped -> "Not running (starts when you play a torrent)"
                is ServerState.Failed -> "Failed: ${s.message}"
            },
            fontSize = 14.sp,
        )
        Text(
            pending?.let { "Update $it downloaded. It will be used the next time the server starts." }
                ?: "Kinostash runs Stremio's own streaming server (downloaded from dl.strem.io) and keeps it up to date automatically.",
            color = if (pending != null) Accent else Muted, fontSize = 12.sp,
        )
        OutlinedButton(onClick = {
            val log = runCatching { server.logFile.readLines().takeLast(200).joinToString("\n") }.getOrDefault("(no log)")
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Share server log"))
        }) { Text("Share server log") }
    }
}

@Composable
private fun UpdatesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = App.instance.settings
    val state by app.kinostash.android.update.AppUpdater.state.collectAsState()
    val lastCheck by app.kinostash.android.update.AppUpdater.lastCheck.collectAsState()
    var autoCheck by remember { mutableStateOf(settings.autoCheckUpdates) }
    var frequency by remember { mutableStateOf(settings.updateFrequency) }
    var notify by remember { mutableStateOf(settings.notifyUpdates) }
    var autoDownload by remember { mutableStateOf(settings.autoDownloadUpdates) }
    fun reschedule() = runCatching { app.kinostash.android.update.UpdateWorker.schedule(ctx) }

    Section("App updates") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Kinostash ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (lastCheck > 0) "Last checked: ${dayLabel(lastCheck)}, ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastCheck))}"
                    else "Not checked yet",
                    color = Muted, fontSize = 12.sp,
                )
            }
            val busy = state is app.kinostash.android.update.UpdateState.Checking ||
                state is app.kinostash.android.update.UpdateState.Downloading ||
                state is app.kinostash.android.update.UpdateState.Installing
            OutlinedButton(enabled = !busy, onClick = { scope.launch { app.kinostash.android.update.AppUpdater.check(ctx) } }) { Text("Check now") }
        }
        UpdateStatus(state)
        when (val s = state) {
            is app.kinostash.android.update.UpdateState.Available -> Button(
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                onClick = { scope.launch { app.kinostash.android.update.AppUpdater.downloadAndInstall(ctx, s.release) } },
            ) { Text("Download & install ${s.release.version}") }
            is app.kinostash.android.update.UpdateState.ReadyToInstall -> Button(
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                onClick = { scope.launch { app.kinostash.android.update.AppUpdater.installPrepared(ctx, s) } },
            ) { Text("Install ${s.release.version}") }
            else -> Unit
        }
        val pageUrl = when (val s = state) {
            is app.kinostash.android.update.UpdateState.Available -> s.release.pageUrl
            is app.kinostash.android.update.UpdateState.ReadyToInstall -> s.release.pageUrl
            else -> "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
        }
        androidx.compose.material3.TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(pageUrl))) }
        }) { Text("Release notes & all versions") }

        androidx.compose.material3.HorizontalDivider(color = Surface2)
        ToggleRow("Check for updates automatically", "Looks for new versions on GitHub.", autoCheck) {
            autoCheck = it; settings.autoCheckUpdates = it; reschedule()
        }
        if (autoCheck) {
            Text("How often", fontSize = 13.sp, color = Muted)
            app.kinostash.android.data.UpdateFrequency.entries.forEach { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { frequency = f; settings.updateFrequency = f; reschedule() },
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = frequency == f, onClick = null,
                        colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(f.label, fontSize = 14.sp)
                }
            }
            ToggleRow("Notify me about updates", "A notification when a new version is found, even if the app is closed.", notify) {
                notify = it; settings.notifyUpdates = it
            }
            ToggleRow("Download updates automatically on Wi-Fi", "Ready to install with one tap. Never uses mobile data.", autoDownload) {
                autoDownload = it; settings.autoDownloadUpdates = it
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

@Composable
fun UpdateStatus(state: app.kinostash.android.update.UpdateState) {
    when (val s = state) {
        app.kinostash.android.update.UpdateState.Checking -> Text("Checking…", color = Muted, fontSize = 13.sp)
        app.kinostash.android.update.UpdateState.UpToDate -> Text("You have the latest version.", color = Muted, fontSize = 13.sp)
        is app.kinostash.android.update.UpdateState.Available -> {
            Text("Version ${s.release.version} is available (${app.kinostash.android.downloads.formatBytes(s.release.sizeBytes)}).", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (s.release.notes.isNotBlank()) Text(s.release.notes.take(1200), color = Muted, fontSize = 12.sp)
        }
        is app.kinostash.android.update.UpdateState.Downloading -> {
            Text("Downloading ${s.release.version}… ${(s.progress * 100).toInt()}%", fontSize = 13.sp)
            androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Accent)
        }
        is app.kinostash.android.update.UpdateState.ReadyToInstall ->
            Text("Version ${s.release.version} is downloaded and verified. Tap Install.", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        is app.kinostash.android.update.UpdateState.Installing -> Text("Installing… confirm in the system dialog. (The first time, Android asks you to allow installs from Kinostash.)", fontSize = 13.sp)
        is app.kinostash.android.update.UpdateState.Failed -> Text(s.message, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 13.sp)
        app.kinostash.android.update.UpdateState.Idle -> Unit
    }
}

@Composable
private fun AboutCard() {
    Section("About") {
        Text("Kinostash ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
        Text(
            "Open source. Not affiliated with Stremio. Uses Stremio addons and Stremio's streaming server " +
                "(downloaded from Stremio at runtime), nodejs-mobile (MIT) and AndroidX Media3 (Apache-2.0). " +
                "Only stream and download content you have the right to.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(2.dp))
    }
}
