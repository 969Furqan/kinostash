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

    @Synchronized
    fun add(item: LibraryItem) {
        if (contains(item.id)) return
        commit(listOf(item) + _items.value)
    }

    @Synchronized
    fun remove(id: String) = commit(_items.value.filterNot { it.id == id })

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
