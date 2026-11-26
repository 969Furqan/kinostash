package app.kinostash.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kinostash.android.App
import app.kinostash.android.data.Addon
import app.kinostash.android.data.AddonClient
import app.kinostash.android.data.CatalogDef
import app.kinostash.android.data.MetaPreview

@Composable
fun HomeScreen(onOpen: (type: String, id: String) -> Unit) {
    val addons by App.instance.addons.addons.collectAsState()
    val history by App.instance.history.entries.collectAsState()
    val catalogs = remember(addons) { App.instance.addons.homeCatalogs() }
    val continueWatching = remember(history) { App.instance.history.continueWatching() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Kinostash", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Accent,
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
            )
        }
        if (continueWatching.isNotEmpty()) {
            item { SectionTitle("Continue watching") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueWatching, key = { it.videoId }) { e ->
                        Poster(e.poster, e.name, e.label, progress = e.progress) { onOpen(e.type, e.metaId) }
                    }
                }
            }
        }
        if (catalogs.isEmpty()) item { Box(Modifier.fillMaxWidth().height(300.dp)) { Loading() } }
        items(catalogs, key = { (a, c) -> "${a.transportUrl}/${c.type}/${c.id}" }) { (a, c) -> CatalogRow(a, c, onOpen) }
    }
}

@Composable
private fun CatalogRow(addon: Addon, catalog: CatalogDef, onOpen: (String, String) -> Unit) {
    var items by remember { mutableStateOf<List<MetaPreview>?>(null) }
    LaunchedEffect(addon.transportUrl, catalog.id, catalog.type) {
        items = runCatching { AddonClient.catalog(addon, catalog) }.getOrDefault(emptyList())
    }
    val list = items
    if (list != null && list.isEmpty()) return // failed or empty: hide the row
    SectionTitle("${catalog.name} · ${catalog.type.replaceFirstChar { it.uppercase() }}")
    if (list == null) {
        Box(Modifier.fillMaxWidth().height(200.dp)) { Loading() }
        return
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.type + it.id }) { m -> Poster(m.poster, m.name, m.releaseInfo) { onOpen(m.type, m.id) } }
    }
}
