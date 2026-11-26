package app.kinostash.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import app.kinostash.android.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Smoke test: the app starts, renders home, and every tab opens without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun settle(ms: Long = 3_000) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { rule.mainClock.advanceTimeBy(250); rule.waitForIdle(); Thread.sleep(100) }
    }

    @Test
    fun allTabsOpen() {
        settle(15_000) // default addons + catalogs load from the network
        for (tab in listOf("Library", "Downloads", "Settings", "Home")) {
            rule.onAllNodesWithText(tab).onFirst().performClick()
            settle(2_000)
        }
        // Search for real (several catalogs return results at once)
        rule.onAllNodesWithText("Home").onFirst().performClick(); settle(1_000)
        rule.onNode(hasSetTextAction()).performTextInput("breaking bad")
        settle(10_000)
        rule.onAllNodesWithText("Popular · Movie", substring = true).onFirst().assertExists()
        rule.onAllNodesWithText("El Camino", substring = true).onFirst().performClick(); settle(8_000) // details page
        rule.onAllNodesWithText("Add to library").onFirst().performClick(); settle(1_000)
        rule.onAllNodesWithText("In library").onFirst().assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle(1_000)
        rule.onAllNodesWithText("Library").onFirst().performClick(); settle(2_000)
        rule.onAllNodesWithText("El Camino", substring = true).onFirst().assertExists() // saved title shows in the library
        // App updates section: options exist and a real check against GitHub Releases returns a result
        rule.onAllNodesWithText("Settings").onFirst().performClick(); settle(1_000)
        rule.onAllNodesWithText("App updates").onFirst().performScrollTo().performClick(); settle(1_000)
        rule.onAllNodesWithText("Check for updates automatically").onFirst().assertExists()
        rule.onAllNodesWithText("Download updates automatically on Wi-Fi").onFirst().assertExists()
        rule.onAllNodesWithText("Check now").onFirst().performScrollTo().performClick(); settle(8_000)
        // This build is 0.3.1 and the latest published release is 0.3.0, so it must report up to date.
        rule.onAllNodesWithText("You have the latest version.").onFirst().assertExists()
        rule.onAllNodesWithText("Last checked", substring = true).onFirst().assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle(500)
        // Every settings category opens
        for (page in listOf("General", "Player", "Subtitles", "Downloads", "Library & notifications", "Stremio account", "Addons", "Streaming server", "About")) {
            rule.onAllNodesWithText(page).onFirst().performScrollTo().performClick(); settle(700)
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; settle(300)
        }
        // Library sub-tabs
        rule.onAllNodesWithText("Library").onFirst().performClick(); settle(1_000)
        rule.onAllNodesWithText("Upcoming").onFirst().performClick(); settle(2_000)
        rule.onAllNodesWithText("New episodes").onFirst().performClick(); settle(1_000)
    }
}
