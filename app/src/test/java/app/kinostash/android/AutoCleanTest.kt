package app.kinostash.android

import androidx.test.core.app.ApplicationProvider
import app.kinostash.android.data.WatchEntry
import app.kinostash.android.downloads.AutoClean
import app.kinostash.android.downloads.AutoDeleteDelay
import app.kinostash.android.downloads.DownloadItem
import app.kinostash.android.downloads.DownloadStatus
import app.kinostash.android.downloads.OfflineDownloads
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoCleanTest {
    private fun dl(id: String, type: String) = DownloadItem(
        id = id, title = id, subtitle = "", poster = null, metaType = type, metaId = "m-$id", videoId = "v-$id",
        sourceUrl = "http://x/$id", infoHash = null, fileIdx = null, filename = null, status = DownloadStatus.COMPLETED,
    )

    private fun watch(id: String, type: String, finished: Boolean, at: Long) =
        WatchEntry("v-$id", "m-$id", type, id, null, null, if (finished) 1 else 60_000, if (finished) 1 else 24 * 60_000, at)

    @Test fun deletesOnlyWatchedAndRespectsOptions() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val app = App.instance
        listOf(dl("ep1", "series"), dl("ep2", "series"), dl("movie", "movie"), dl("half", "series")).forEach { OfflineDownloads.store.add(it) }
        val now = System.currentTimeMillis()
        app.history.update(watch("ep1", "series", true, now - 2 * 86_400_000L), force = true)
        app.history.update(watch("ep2", "series", true, now), force = true)
        app.history.update(watch("movie", "movie", true, now - 2 * 86_400_000L), force = true)
        app.history.update(watch("half", "series", false, now - 9 * 86_400_000L), force = true)

        app.settings.autoDeleteDelay = AutoDeleteDelay.OFF
        assertEquals(emptyList<String>(), AutoClean.due(ctx).map { it.id })

        app.settings.autoDeleteDelay = AutoDeleteDelay.IMMEDIATELY
        assertEquals(setOf("ep1", "ep2", "movie"), AutoClean.due(ctx).map { it.id }.toSet())
        assertEquals(setOf("ep1", "movie"), AutoClean.due(ctx, excludeVideoId = "v-ep2").map { it.id }.toSet())

        app.settings.autoDeleteDelay = AutoDeleteDelay.DAY
        assertEquals(setOf("ep1", "movie"), AutoClean.due(ctx).map { it.id }.toSet())

        app.settings.autoDeleteMovies = false
        assertEquals(setOf("ep1"), AutoClean.due(ctx).map { it.id }.toSet())
    }
}
