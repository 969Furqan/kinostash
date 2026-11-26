package app.kinostash.android

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of a crash so the next launch can show it (with a Share button).
 * Nothing is sent anywhere automatically.
 */
object CrashReporter {
    private lateinit var file: File

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file.writeText(
                    "Kinostash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} " +
                        "(API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The crash from the previous run, if any (cleared once read). */
    fun takeLastCrash(): String? {
        if (!::file.isInitialized || !file.exists()) return null
        return runCatching { file.readText() }.getOrNull().also { file.delete() }
    }
}
