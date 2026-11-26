package app.kinostash.android.data

import android.content.Context

/** How often the app looks for a new Kinostash version. */
enum class UpdateFrequency(val label: String, val intervalMs: Long) {
    LAUNCH("Every time the app opens", 0L),
    DAILY("Once a day", 24L * 60 * 60 * 1000),
    WEEKLY("Once a week", 7L * 24 * 60 * 60 * 1000),
}

/** Which player opens streams and downloads. */
enum class PlayerChoice(val label: String, val packageName: String?) {
    BUILT_IN("Kinostash player", null),
    ASK("Ask every time", null),
    VLC("VLC", "org.videolan.vlc"),
    MX("MX Player", "com.mxtech.videoplayer.ad"),
    MX_PRO("MX Player Pro", "com.mxtech.videoplayer.pro"),
    MPV("mpv", "is.xyz.mpv"),
    JUST("Just Player", "com.brouken.player"),
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun setInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    // --- General -----------------------------------------------------------------------------

    /** Tab shown when the app opens: "home", "library" or "downloads". */
    var startPage: String
        get() = prefs.getString("start_page", "home") ?: "home"
        set(v) { prefs.edit().putString("start_page", v).apply() }

    var quality: QualityPref
        get() = runCatching { QualityPref.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(QualityPref.BEST)
        set(v) { prefs.edit().putString("quality", v.name).apply() }

    // --- Player ------------------------------------------------------------------------------

    var player: PlayerChoice
        get() = runCatching { PlayerChoice.valueOf(prefs.getString("player", null)!!) }.getOrDefault(PlayerChoice.BUILT_IN)
        set(v) { prefs.edit().putString("player", v.name).apply() }

    /** Double-tap seek: first double-tap jumps [seekStepSec]; each further tap adds the same, up to [seekMaxSec]. */
    var seekStepSec: Int get() = int("seek_step", 10); set(v) = setInt("seek_step", v)
    var seekMaxSec: Int get() = int("seek_max", 60); set(v) = setInt("seek_max", v)
    var swipeBrightness: Boolean get() = bool("swipe_brightness", true); set(v) = setBool("swipe_brightness", v)
    var swipeVolume: Boolean get() = bool("swipe_volume", true); set(v) = setBool("swipe_volume", v)
    /** Resume where you left off without asking. */
    var autoResume: Boolean get() = bool("auto_resume", true); set(v) = setBool("auto_resume", v)

    // --- Subtitles ---------------------------------------------------------------------------

    /** ISO language code ("en", "es", …) or "" for off by default. */
    var subtitleLanguage: String
        get() = prefs.getString("sub_lang", "en") ?: "en"
        set(v) { prefs.edit().putString("sub_lang", v).apply() }
    var subtitleSizePercent: Int get() = int("sub_size", 100); set(v) = setInt("sub_size", v)
    var subtitleTextColor: Int get() = int("sub_color", 0xFFFFFFFF.toInt()); set(v) = setInt("sub_color", v)
    var subtitleBackgroundColor: Int get() = int("sub_bg", 0x00000000); set(v) = setInt("sub_bg", v)
    var subtitleOutline: Boolean get() = bool("sub_outline", true); set(v) = setBool("sub_outline", v)
    /** Distance from the bottom of the screen, in percent of its height. */
    var subtitlePositionPercent: Int get() = int("sub_pos", 8); set(v) = setInt("sub_pos", v)
    /** Default subtitle delay in milliseconds (positive = later, negative = earlier). */
    var subtitleDelayMs: Int get() = int("sub_delay", 0); set(v) = setInt("sub_delay", v)

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
