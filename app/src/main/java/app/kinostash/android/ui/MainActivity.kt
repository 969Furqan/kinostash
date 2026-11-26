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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch { App.instance.addons.ensureDefaults() }
        OfflineDownloads.kick(this) // continue unfinished downloads
        handle(intent)
        setContent { KinostashTheme { Root(openDownloads.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(DownloadService.EXTRA_OPEN_DOWNLOADS, false) == true) openDownloads.intValue++
        // stremio://…/manifest.json links install addons.
        val data = intent?.data
        if (data?.scheme == "stremio" && data.toString().endsWith("manifest.json")) {
            lifecycleScope.launch {
                val ok = runCatching { App.instance.addons.install(data.toString()) }.isSuccess
                android.widget.Toast.makeText(this@MainActivity, if (ok) "Addon installed" else "Couldn't install addon", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{type}/{id}"
    const val STREAMS = "streams/{type}/{id}/{videoId}"
    fun detail(type: String, id: String) = "detail/$type/${Uri.encode(id)}"
    fun streams(type: String, id: String, videoId: String) = "streams/$type/${Uri.encode(id)}/${Uri.encode(videoId)}"
}

@Composable
private fun Root(openDownloadsSignal: Int) {
    val nav = rememberNavController()
    LaunchedEffect(openDownloadsSignal) { if (openDownloadsSignal > 0) nav.tab(Routes.DOWNLOADS) }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf(
        Triple(Routes.HOME, "Home", Icons.Outlined.Home),
        Triple(Routes.SEARCH, "Search", Icons.Outlined.Search),
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
