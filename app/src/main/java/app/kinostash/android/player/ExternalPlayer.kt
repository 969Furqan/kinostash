package app.kinostash.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import app.kinostash.android.data.PlayerChoice
import java.io.File

/** Hands a stream or a downloaded file to another player app (VLC, MX Player, mpv, …). */
object ExternalPlayer {
    fun open(ctx: Context, uri: Uri, title: String, positionMs: Long, choice: PlayerChoice): Boolean {
        val playable = if (uri.scheme == "file") {
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(uri.path!!))
        } else uri
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(playable, "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("title", title) // VLC, MX, Just Player
            .putExtra("position", positionMs.toInt()) // MX Player (ms, int)
            .putExtra("from_start", positionMs <= 0) // VLC
        if (choice.packageName != null) intent.setPackage(choice.packageName)
        if (uri.scheme == "http") ServeService.start(ctx, title) // keep the local streaming server alive meanwhile
        return try {
            ctx.startActivity(if (choice == PlayerChoice.ASK) Intent.createChooser(intent, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "${choice.label} isn't installed", Toast.LENGTH_LONG).show()
            false
        }
    }
}

/**
 * Keeps Kinostash (and the streaming server inside it) running while another app plays a torrent
 * stream from it. Stops when you tap "Stop" or after a few hours.
 */
class ServeService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streaming to other players", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ServeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Streaming to another player")
            .setContentText(intent?.getStringExtra("title") ?: "Kinostash keeps the stream running")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        val ok = runCatching {
            ServiceCompat.startForeground(this, 7302, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 4L * 60 * 60 * 1000)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "external_streaming"
        private const val ACTION_STOP = "stop"
        fun start(ctx: Context, title: String) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ServeService::class.java).putExtra("title", title))
            }
        }
    }
}
