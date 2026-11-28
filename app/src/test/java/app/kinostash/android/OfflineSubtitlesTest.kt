package app.kinostash.android

import androidx.test.core.app.ApplicationProvider
import app.kinostash.android.downloads.OfflineDownloads
import app.kinostash.android.downloads.OfflineSubtitles
import app.kinostash.android.player.SubtitleShift
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineSubtitlesTest {
    @Test fun languageCodesMatchAcrossFormats() {
        assertTrue(OfflineSubtitles.sameLanguage("en", "eng"))
        assertTrue(OfflineSubtitles.sameLanguage("ENG", "en"))
        assertTrue(!OfflineSubtitles.sameLanguage("en", "fre"))
    }

    @Test fun savedSubtitlesAreFoundAndPlayableOffline() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dir = File(OfflineDownloads.itemDir(ctx, "dl1"), "subs").apply { mkdirs() }
        File(dir, "en.1.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n")
        val saved = OfflineSubtitles.saved(ctx, "dl1")
        assertEquals(1, saved.size)
        assertTrue(OfflineSubtitles.hasSaved(ctx, "dl1", "eng"))
        val shifted = SubtitleShift.shifted(ctx, saved[0].url, 500)!!
        assertTrue(File(shifted.path!!).readText().contains("00:00:01,500 --> 00:00:02,500"))
    }
}
