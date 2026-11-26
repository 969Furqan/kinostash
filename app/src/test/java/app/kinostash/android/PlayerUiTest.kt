package app.kinostash.android

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.click
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.kinostash.android.data.StreamItem
import app.kinostash.android.player.PlayRequest
import app.kinostash.android.player.PlayerActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player UI composes and its gestures and menus work without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { rule.mainClock.advanceTimeBy(200); rule.waitForIdle(); Thread.sleep(50) }
    }

    @Test
    fun controlsAndGestures() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val stream = StreamItem("Test", "Direct", "test", null, null, "http://127.0.0.1:9/none.mp4", emptyList(), null)
        runCatching {
            PlayerActivity.start(ctx.applicationContext, PlayRequest(stream, "Test video", "movie", "tt0000001", "tt0000001", "Test", null, null))
        }
        ActivityScenario.launch(PlayerActivity::class.java).use {
            settle(2_000)
            rule.mainClock.autoAdvance = false
            rule.onRoot().performTouchInput { doubleClick(centerRight) }
            rule.mainClock.advanceTimeBy(400)
            rule.onRoot().performTouchInput { click(centerRight) }   // a further tap keeps accumulating
            rule.mainClock.advanceTimeBy(400)
            val step = App.instance.settings.seekStepSec
            rule.onAllNodesWithText("+${step * 2}s").onFirst().assertExists() // e.g. "+20s" bubble
            rule.mainClock.autoAdvance = true
            settle(1_200)
            rule.onRoot().performTouchInput { doubleClick(centerLeft) }; settle(1_200)
            rule.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top + bottom * 0.2f) }; settle(300)
            rule.onRoot().performTouchInput { click(center) }; settle(300) // show controls
            for (desc in listOf("Subtitles", "Audio", "Speed", "Change source")) {
                rule.onAllNodesWithContentDescription(desc).onFirst().performClick(); settle(1_500)
                rule.onAllNodesWithText("Close").onFirst().performClick(); settle(300)
                rule.onRoot().performTouchInput { click(center) }; settle(300)
            }
            // Subtitle style panel: opens over the video, sliders/swatches render, Reset and Done work.
            rule.onAllNodesWithContentDescription("Subtitles").onFirst().performClick(); settle(800)
            rule.onAllNodesWithText("Style: size, colour, position, width…").onFirst().performClick(); settle(800)
            rule.onAllNodesWithText("Subtitle style").onFirst().assertExists()
            rule.onAllNodesWithText("Width: 100% (− squeeze, + stretch)").onFirst().assertExists()
            rule.onAllNodesWithText("Reset").onFirst().performClick(); settle(300)
            rule.onAllNodesWithText("Done").onFirst().performClick(); settle(300)
        }
    }
}
