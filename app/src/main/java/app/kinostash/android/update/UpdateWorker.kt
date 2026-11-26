package app.kinostash.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kinostash.android.App
import app.kinostash.android.ui.MainActivity
import java.util.concurrent.TimeUnit

/** Background update check (even when the app isn't opened), following Settings → App updates. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val settings = App.instance.settings
        val rel = AppUpdater.checkIfDue(applicationContext, settings) ?: return Result.success()
        if (!settings.notifyUpdates) return Result.success()
        val prefs = applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)
        if (prefs.getString("notified_version", null) == rel.version) return Result.success() // once per version
        prefs.edit().putString("notified_version", rel.version).apply()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val ready = AppUpdater.state.value is UpdateState.ReadyToInstall
        val open = PendingIntent.getActivity(
            applicationContext, 1,
            Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Kinostash ${rel.version} is available")
            .setContentText(if (ready) "Downloaded. Tap to install." else "Tap to update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(rel.notes.ifBlank { "A new version of Kinostash is ready." }.take(500)))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 7301
        private const val WORK = "app-update-check"

        /** (Re)schedule according to the settings; called on start and when settings change. */
        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val s = App.instance.settings
            if (!s.autoCheckUpdates) { wm.cancelUniqueWork(WORK); return }
            val hours = if (s.updateFrequency == app.kinostash.android.data.UpdateFrequency.WEEKLY) 24L * 7 else 24L
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
