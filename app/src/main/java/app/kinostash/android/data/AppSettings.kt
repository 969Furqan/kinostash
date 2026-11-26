package app.kinostash.android.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var episodeNotifications: Boolean
        get() = prefs.getBoolean("episode_notifications", true)
        set(v) { prefs.edit().putBoolean("episode_notifications", v).apply() }
}
