package app.kinostash.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kinostash.android.App
import app.kinostash.android.data.Addon
import app.kinostash.android.data.AddonClient
import app.kinostash.android.data.AddonRepository
import app.kinostash.android.data.CatalogDef
import app.kinostash.android.data.MetaPreview
import kotlinx.coroutines.delay

/** Home: search bar on top; catalog rows (and "Continue watching") below, or search results while searching. */
@Composable
fun HomeScreen(onOpen: (type: String, id: String) -> Unit) {
    val addons by App.instance.addons.addons.collectAsState()
    val history by App.instance.history.entries.collectAsState()
    val downloads by app.kinostash.android.downloads.OfflineDownloads.store.items.collectAsState()
    val recentSearches by App.instance.searches.items.collectAsState()
    val catalogs = remember(addons) { App.instance.addons.homeCatalogs() }
    val continueWatching = remember(history) { App.instance.history.continueWatching() }
    var query by rememberSaveable { mutableStateOf(PendingSearch.query.also { PendingSearch.query = null } ?: "") }
    var results by remember { mutableStateOf<List<AddonRepository.SearchSection>?>(emptyList()) }
    var menuFor by remember { mutableStateOf<MetaPreview?>(null) }
    val focus = LocalFocusManager.current

    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(450) // debounce typing
        results = null
        results = App.instance.addons.search(query.trim())
        delay(2_500) // remember searches people actually looked at
        App.instance.searches.add(query)
    }
    val poster: @Composable (MetaPreview) -> Unit = { m ->
        Poster(
            m.poster, m.name, m.releaseInfo, rating = m.imdbRating,
            downloaded = downloads.any { it.metaId == m.id && it.status == app.kinostash.android.downloads.DownloadStatus.COMPLETED },
            watched = m.type == "movie" && App.instance.history.isWatched(m.id),
            onLongClick = { menuFor = m },
        ) { onOpen(m.type, m.id) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                placeholder = { Text("Search movies and series") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); App.instance.searches.add(query) }),
            )
        }
        if (query.isBlank()) {
            if (recentSearches.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentSearches.take(12), key = { it }) { q ->
                        AssistChip(
                            onClick = { query = q }, label = { Text(q) },
                            leadingIcon = { Icon(Icons.Outlined.History, null) },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, "Remove", modifier = Modifier.clickable { App.instance.searches.remove(q) })
                            },
                        )
                    }
                }
            }
            if (continueWatching.isNotEmpty()) {
                item { SectionTitle("Continue watching") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueWatching, key = { it.metaId }) { e ->
                            Poster(e.poster, e.name, e.label, progress = e.progress) { onOpen(e.type, e.metaId) }
                        }
                    }
                }
            }
            if (catalogs.isEmpty()) item { Box(Modifier.fillMaxWidth().height(300.dp)) { Loading() } }
            items(
                catalogs.distinctBy { (a, c) -> "${a.transportUrl}|${c.type}|${c.id}" },
                key = { (a, c) -> "${a.transportUrl}|${c.type}|${c.id}" },
            ) { (a, c) -> CatalogRow(a, c, poster) }
        } else {
            val r = results
            when {
                r == null -> item { Box(Modifier.fillMaxWidth().height(300.dp)) { Loading() } }
                r.isEmpty() -> item { Box(Modifier.fillMaxWidth().height(200.dp)) { Message("Nothing found") } }
                else -> items(r, key = { it.key }) { section ->
                    SectionTitle(section.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.items, key = { it.type + it.id }) { m -> poster(m) }
                    }
                }
            }
        }
    }
    menuFor?.let { m -> TitleMenu(m.type, m.id, m.name, m.poster) { menuFor = null } }
}

@Composable
private fun CatalogRow(addon: Addon, catalog: CatalogDef, poster: @Composable (MetaPreview) -> Unit) {
    var items by remember { mutableStateOf<List<MetaPreview>?>(null) }
    LaunchedEffect(addon.transportUrl, catalog.id, catalog.type) {
        items = runCatching { AddonClient.catalog(addon, catalog).distinctBy { it.type + it.id } }.getOrDefault(emptyList())
    }
    val list = items
    if (list != null && list.isEmpty()) return // failed or empty: hide the row
    SectionTitle("${catalog.name} · ${catalog.type.replaceFirstChar { it.uppercase() }}")
    if (list == null) {
        Box(Modifier.fillMaxWidth().height(200.dp)) { Loading() }
        return
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.type + it.id }) { m -> poster(m) }
    }
}
