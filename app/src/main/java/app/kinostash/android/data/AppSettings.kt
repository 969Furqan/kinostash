package app.kinostash.android.data

import android.content.Context

/** How often the app looks for a new Kinostash version. */
enum class UpdateFrequency(val label: String, val intervalMs: Long) {
    LAUNCH("Every time the app opens", 0L),
    DAILY("Once a day", 24L * 60 * 60 * 1000),
    WEEKLY("Once a week", 7L * 24 * 60 * 60 * 1000),
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var episodeNotifications: Boolean
        get() = prefs.getBoolean("episode_notifications", true)
        set(v) { prefs.edit().putBoolean("episode_notifications", v).apply() }

    // --- App updates -----------------------------------------------------------------------

    /** Look for new versions automatically (on launch and in the background). */
    var autoCheckUpdates: Boolean
        get() = prefs.getBoolean("auto_check_updates", true)
        set(v) { prefs.edit().putBoolean("auto_check_updates", v).apply() }

    var updateFrequency: UpdateFrequency
        get() = runCatching { UpdateFrequency.valueOf(prefs.getString("update_frequency", null)!!) }.getOrDefault(UpdateFrequency.DAILY)
        set(v) { prefs.edit().putString("update_frequency", v.name).apply() }

    /** Show a system notification when an update is found in the background. */
    var notifyUpdates: Boolean
        get() = prefs.getBoolean("notify_updates", true)
        set(v) { prefs.edit().putBoolean("notify_updates", v).apply() }

    /** Download (and verify) updates in the background when on Wi-Fi, so installing is one tap. */
    var autoDownloadUpdates: Boolean
        get() = prefs.getBoolean("auto_download_updates", false)
        set(v) { prefs.edit().putBoolean("auto_download_updates", v).apply() }
}
