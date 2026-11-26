package app.kinostash.android

import app.kinostash.android.data.QualityPref
import app.kinostash.android.data.StreamItem
import app.kinostash.android.data.StreamPicker
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPickerTest {
    private fun s(q: String, seeds: Int) = StreamItem("Torrentio", q, "👤 $seeds 💾 1 GB", "h$q$seeds", 0, null, emptyList(), null)

    private val streams = listOf(s("1080p", 50), s("1080p", 400), s("720p", 900), s("2160p", 12), s("2160p", 80))

    @Test fun bestAvailablePicksHighestQualityWithMostSeeders() {
        assertEquals("h2160p80", StreamPicker.pick(streams, QualityPref.BEST)?.infoHash)
    }

    @Test fun preferredQualityWithMostSeeders() {
        assertEquals("h1080p400", StreamPicker.pick(streams, QualityPref.Q1080)?.infoHash)
    }

    @Test fun fallsBackToNextLowerQuality() {
        val no720 = streams.filterNot { it.quality == "720p" } + s("480p", 30)
        assertEquals("h480p30", StreamPicker.pick(no720, QualityPref.Q720)?.infoHash)
    }

    @Test fun fallsBackToHigherWhenNothingLower() {
        val only4k = listOf(s("2160p", 5), s("2160p", 9))
        assertEquals("h2160p9", StreamPicker.pick(only4k, QualityPref.Q480)?.infoHash)
    }

    @Test fun orderIsPreferredThenLowerThenHigher() {
        assertEquals(listOf("1080p", "720p", "480p", "2160p", null), StreamPicker.qualityOrder(QualityPref.Q1080))
    }
}
