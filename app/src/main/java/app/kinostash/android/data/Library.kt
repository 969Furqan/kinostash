package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryItem(val id: String, val type: String, val name: String, val poster: String?, val addedAt: Long)

/** Your saved movies and series (stored on the phone; can be imported from a Stremio account). */
class Library(context: Context) {
    private val file = File(context.filesDir, "library.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items

    private fun load(): List<LibraryItem> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LibraryItem(o.getString("id"), o.getString("type"), o.getString("name"), o.optStr("poster"), o.optLong("addedAt"))
        }
    }.getOrDefault(emptyList())

    fun contains(id: String) = _items.value.any { it.id == id }

    // --- Categories (your own lists, e.g. "Anime", "Watch later") ---------------------------
    private val catFile = File(context.filesDir, "library_categories.json")
    private val _categories = MutableStateFlow(loadCategories())
    /** Category name -> ids of titles in it, in creation order. */
    val categories: StateFlow<Map<String, Set<String>>> = _categories

    private fun loadCategories(): Map<String, Set<String>> = runCatching {
        val o = JSONObject(catFile.readText())
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
    }.getOrDefault(emptyMap())

    private fun saveCategories(map: Map<String, Set<String>>) {
        _categories.value = map
        runCatching {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) }
            catFile.writeText(o.toString())
        }
    }

    @Synchronized fun createCategory(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n !in _categories.value) saveCategories(_categories.value + (n to emptySet()))
    }
    @Synchronized fun deleteCategory(name: String) = saveCategories(_categories.value - name)
    @Synchronized fun renameCategory(old: String, new: String) {
        val n = new.trim()
        val ids = _categories.value[old] ?: return
        if (n.isEmpty() || n in _categories.value) return
        saveCategories(_categories.value.toList().map { (k, v) -> if (k == old) n to ids else k to v }.toMap())
    }
    /** Put [item] in exactly these categories (adds it to the library too). */
    @Synchronized fun setCategories(item: LibraryItem, names: Set<String>) {
        add(item)
        saveCategories(_categories.value.mapValues { (k, v) -> if (k in names) v + item.id else v - item.id })
    }
    fun categoriesOf(id: String): Set<String> = _categories.value.filterValues { id in it }.keys

    @Synchronized
    fun add(item: LibraryItem) {
        if (contains(item.id)) return
        commit(listOf(item) + _items.value)
    }

    @Synchronized
    fun remove(id: String) {
        commit(_items.value.filterNot { it.id == id })
        if (_categories.value.values.any { id in it }) saveCategories(_categories.value.mapValues { it.value - id })
    }

    /** Merge items (e.g. from a Stremio account), keeping what's already here. */
    @Synchronized
    fun merge(items: List<LibraryItem>): Int {
        val have = _items.value.map { it.id }.toSet()
        val new = items.filter { it.id !in have }
        if (new.isNotEmpty()) commit(_items.value + new)
        return new.size
    }

    private fun commit(list: List<LibraryItem>) {
        _items.value = list
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster).put("addedAt", it.addedAt)) }
            file.writeText(arr.toString())
        }
    }
}
