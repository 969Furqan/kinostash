package app.kinostash.android

import app.kinostash.android.update.AppUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun versionComparison() {
        assertTrue(AppUpdater.isNewer("0.3.0", "0.2.1"))
        assertTrue(AppUpdater.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.99.99"))
        assertTrue(AppUpdater.isNewer("0.2.2", "0.2.1"))
        assertFalse(AppUpdater.isNewer("0.2.1", "0.2.1"))
        assertFalse(AppUpdater.isNewer("0.2.0", "0.2.1"))
        assertFalse(AppUpdater.isNewer("0.3.0-beta", "0.3.0"))
    }
}
