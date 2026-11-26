package app.kinostash.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kinostash.android.App
import app.kinostash.android.downloads.DownloadService
import app.kinostash.android.downloads.OfflineDownloads
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openDownloads = mutableIntStateOf(0)
    private val openLibrary = mutableIntStateOf(0)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch { App.instance.addons.ensureDefaults() }
        lifecycleScope.launch { app.kinostash.android.update.AppUpdater.checkIfDue(this@MainActivity) }
        OfflineDownloads.kick(this) // continue unfinished downloads
        handle(intent)
        val lastCrash = app.kinostash.android.CrashReporter.takeLastCrash()
        setContent {
            KinostashTheme {
                Root(openDownloads.intValue, openLibrary.intValue)
                // "Update available" prompt (from the daily check).
                val update by app.kinostash.android.update.AppUpdater.state.collectAsState()
                var dismissedUpdate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                val avail = update as? app.kinostash.android.update.UpdateState.Available
                if (avail != null && avail.release.version != dismissedUpdate) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { dismissedUpdate = avail.release.version },
                        title = { Text("Update available: ${avail.release.version}") },
                        text = { Text(avail.release.notes.ifBlank { "A new version of Kinostash is ready." }.take(1500)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                dismissedUpdate = avail.release.version
                                lifecycleScope.launch { app.kinostash.android.update.AppUpdater.downloadAndInstall(this@MainActivity, avail.release) }
                            }) { Text("Update") }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { dismissedUpdate = avail.release.version }) { Text("Later") } },
                    )
                }
                var crash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(lastCrash) }
                crash?.let { text ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { crash = null },
                        title = { Text("Kinostash crashed last time") },
                        text = {
                            androidx.compose.foundation.layout.Column(
                                androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            ) {
                                Text("Please share this report so it can be fixed.")
                                Text(text.take(3000), fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), color = Muted)
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share crash report"))
                            }) { Text("Share") }
                        },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { crash = null }) { Text("Close") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadService.EXTRA_OPEN_DOWNLOADS, false) == true) openDownloads.intValue++
        if (intent?.getBooleanExtra(EXTRA_OPEN_LIBRARY, false) == true) openLibrary.intValue++
        // stremio://…/manifest.json links install addons.
        val data = intent?.data
        if (data?.scheme == "stremio" && data.toString().endsWith("manifest.json")) {
            lifecycleScope.launch {
                val ok = runCatching { App.instance.addons.install(data.toString()) }.isSuccess
                android.widget.Toast.makeText(this@MainActivity, if (ok) "Addon installed" else "Couldn't install addon", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_LIBRARY = "app.kinostash.android.OPEN_LIBRARY"
    }
}

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{type}/{id}"
    const val STREAMS = "streams/{type}/{id}/{videoId}"
    fun detail(type: String, id: String) = "detail/$type/${Uri.encode(id)}"
    fun streams(type: String, id: String, videoId: String) = "streams/$type/${Uri.encode(id)}/${Uri.encode(videoId)}"
}

@Composable
private fun Root(openDownloadsSignal: Int, openLibrarySignal: Int) {
    val nav = rememberNavController()
    LaunchedEffect(openDownloadsSignal) { if (openDownloadsSignal > 0) nav.tab(Routes.DOWNLOADS) }
    LaunchedEffect(openLibrarySignal) { if (openLibrarySignal > 0) nav.tab(Routes.LIBRARY) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf(
        Triple(Routes.HOME, "Home", Icons.Outlined.Home),
        Triple(Routes.SEARCH, "Search", Icons.Outlined.Search),
        Triple(Routes.LIBRARY, "Library", Icons.Outlined.VideoLibrary),
        Triple(Routes.DOWNLOADS, "Downloads", Icons.Outlined.Download),
        Triple(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
    )
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (route in tabs.map { it.first } || route == null) {
                NavigationBar(containerColor = Surface1) {
                    tabs.forEach { (r, label, icon) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = { nav.tab(r) },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = AccentDim),
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(pad)) {
            composable(Routes.HOME) { HomeScreen(onOpen = { t, id -> nav.navigate(Routes.detail(t, id)) }) }
            composable(Routes.SEARCH) { SearchScreen(onOpen = { t, id -> nav.navigate(Routes.detail(t, id)) }) }
            composable(Routes.DOWNLOADS) { DownloadsScreen() }
            composable(Routes.LIBRARY) { LibraryScreen(onOpen = { t, id -> nav.navigate(Routes.detail(t, id)) }) }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.DETAIL) { e ->
                val type = e.arguments?.getString("type")!!
                val id = e.arguments?.getString("id")!!
                DetailScreen(
                    type = type, id = id,
                    onBack = { nav.popBackStack() },
                    onPickVideo = { videoId -> nav.navigate(Routes.streams(type, id, videoId)) },
                )
            }
            composable(Routes.STREAMS) { e ->
                StreamsScreen(
                    type = e.arguments?.getString("type")!!,
                    metaId = e.arguments?.getString("id")!!,
                    videoId = e.arguments?.getString("videoId")!!,
                    onBack = { nav.popBackStack() },
                    onOpenDownloads = { nav.tab(Routes.DOWNLOADS) },
                )
            }
        }
    }
}

private fun NavHostController.tab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
