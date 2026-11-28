package app.kinostash.android.downloads

import android.content.Context
import android.os.StatFs
import android.widget.Toast
import app.kinostash.android.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** When watched downloads are deleted automatically. */
enum class AutoDeleteDelay(val label: String, val ms: Long) {
    OFF("Never", -1),
    IMMEDIATELY("Right after watching", 0),
    DAY("1 day after watching", 86_400_000L),
    THREE_DAYS("3 days after watching", 3 * 86_400_000L),
    WEEK("1 week after watching", 7 * 86_400_000L),
    MONTH("1 month after watching", 30 * 86_400_000L),
}

/** Free-space level under which "only when storage is low" kicks in. */
const val LOW_STORAGE_BYTES = 5L * 1024 * 1024 * 1024

/**
 * "Garbage collection" for downloads: removes downloads you've finished watching, following the
 * options in Settings → Downloads → Auto-delete. Runs when the app starts and when you close the player.
 */
object AutoClean {
    private fun watchedAt(videoId: String?): Long? {
        videoId ?: return null
        return App.instance.history.entries.value.firstOrNull { it.videoId == videoId && it.finished }?.updatedAt
    }

    /** Completed downloads that have been watched (newest watch last). */
    fun watchedDownloads(): List<DownloadItem> =
        OfflineDownloads.store.items.value.filter { it.status == DownloadStatus.COMPLETED && watchedAt(it.videoId) != null }

    fun freeBytes(ctx: Context): Long = runCatching { StatFs(OfflineDownloads.rootDir(ctx).apply { mkdirs() }.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun sizeOf(d: DownloadItem) = d.filePath?.let { File(it).length() } ?: d.bytesTotal

    /** Downloads the current settings say should go now. [excludeVideoId] protects what's playing. */
    fun due(ctx: Context, excludeVideoId: String? = null): List<DownloadItem> {
        val s = App.instance.settings
        val delay = s.autoDeleteDelay
        if (delay == AutoDeleteDelay.OFF) return emptyList()
        if (s.autoDeleteOnlyWhenLow && freeBytes(ctx) >= LOW_STORAGE_BYTES) return emptyList()
        val now = System.currentTimeMillis()
        return watchedDownloads().filter { d ->
            d.videoId != excludeVideoId &&
                (s.autoDeleteMovies || d.metaType != "movie") &&
                now - (watchedAt(d.videoId) ?: now) >= delay.ms
        }
    }

    /** Delete what's due; shows a short message when anything was removed. */
    fun run(ctx: Context, excludeVideoId: String? = null) {
        val app = ctx.applicationContext
        App.instance.appScope.launch {
            val list = due(app, excludeVideoId)
            if (list.isEmpty()) return@launch
            val freed = delete(app, list)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, "Auto-deleted ${list.size} watched download${if (list.size == 1) "" else "s"} (${formatBytes(freed)} freed)", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Delete [list] now; returns bytes freed. */
    fun delete(ctx: Context, list: List<DownloadItem>): Long {
        var freed = 0L
        list.forEach { d -> freed += sizeOf(d); OfflineDownloads.delete(ctx, d.id) }
        return freed
    }
}
