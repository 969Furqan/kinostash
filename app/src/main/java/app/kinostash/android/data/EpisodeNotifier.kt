package app.kinostash.android.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.kinostash.android.App
import app.kinostash.android.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Twice a day: refresh the release schedules of library series and notify about episodes that
 * came out since the last check.
 */
class EpisodeNotifier(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = App.instance
        if (!app.settings.episodeNotifications) return Result.success()
        runCatching { app.upcoming.refresh() }
        val prefs = applicationContext.getSharedPreferences("episode_notifier", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = prefs.getLong("last_check", now - 24 * 60 * 60 * 1000L)
        val fresh = app.upcoming.releases.value.filter { it.releasedAt in (since + 1)..now }
        prefs.edit().putLong("last_check", now).apply()
        if (fresh.isEmpty()) return Result.success()

        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fresh.groupBy { it.metaId }.forEach { (metaId, eps) ->
            val first = eps.first()
            val text = eps.joinToString(", ") { listOf(it.code, it.title).filter { s -> s.isNotBlank() }.joinToString(" ") }
            val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("New episode: ${first.showName}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            runCatching { nm.notify(metaId.hashCode(), n) }
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "new_episodes"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<EpisodeNotifier>(12, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("episode-notifier", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
