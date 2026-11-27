package app.kinostash.android

import androidx.test.core.app.ApplicationProvider
import app.kinostash.android.data.WatchEntry
import app.kinostash.android.data.WatchHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryTest {
    private fun e(id: String, pos: Long = 1, dur: Long = 1) = WatchEntry(id, "tt-op", "series", "One Piece", null, id, pos, dur, System.currentTimeMillis())

    @Test fun playingAnEpisodeKeepsHundredsOfWatchedMarks() {
        val h = WatchHistory(ApplicationProvider.getApplicationContext())
        h.setWatched((1..1100).map { e("ep$it") }, true)
        h.update(e("ep1101", pos = 60_000, dur = 24 * 60_000), force = true)
        assertTrue((1..1100).all { h.isWatched("ep$it") })
        assertEquals(1101, h.entries.value.size)
    }
}
