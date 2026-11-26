package app.kinostash.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File

/** Recent searches (stored on the phone only). */
class SearchHistory(context: Context) {
    private val file = File(context.filesDir, "search_history.json")
    private val _items = MutableStateFlow(runCatching {
        val a = JSONArray(file.readText()); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList()))
    val items: StateFlow<List<String>> = _items

    @Synchronized fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        commit((listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) }).take(50))
    }
    @Synchronized fun remove(query: String) = commit(_items.value - query)
    @Synchronized fun clear() = commit(emptyList())

    private fun commit(list: List<String>) {
        _items.value = list
        runCatching { file.writeText(JSONArray(list).toString()) }
    }
}
