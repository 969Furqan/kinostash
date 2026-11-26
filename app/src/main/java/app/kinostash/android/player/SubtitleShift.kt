package app.kinostash.android.player

import android.content.Context
import android.net.Uri
import app.kinostash.android.data.AddonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Applies a subtitle delay to addon subtitles (SRT/VTT) by rewriting their timestamps into a local
 * copy. ExoPlayer has no subtitle-offset setting, so this is how "+/- subtitle delay" works for them.
 */
object SubtitleShift {
    private val time = Regex("""(\d{1,2}):(\d{2}):(\d{2})([,.])(\d{3})""")

    /** Local file for [url] shifted by [delayMs], or null if it can't be fetched. Originals are cached. */
    suspend fun shifted(ctx: Context, url: String, delayMs: Int): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "subs").apply { mkdirs() }
            val key = md5(url)
            val original = File(dir, "$key.orig")
            if (!original.exists()) {
                AddonClient.http.newCall(Request.Builder().url(url).header("User-Agent", "Kinostash").build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching null
                    original.writeBytes(r.body?.bytes() ?: return@runCatching null)
                }
            }
            val text = original.readText(Charsets.UTF_8)
            val isVtt = text.trimStart('﻿').startsWith("WEBVTT")
            val out = File(dir, "$key.$delayMs." + if (isVtt) "vtt" else "srt")
            if (!out.exists()) out.writeText(if (delayMs == 0) text else shift(text, delayMs))
            Uri.fromFile(out)
        }.getOrNull()
    }

    fun shift(text: String, delayMs: Int): String = time.replace(text) { m ->
        val (h, mi, s, sep, ms) = m.destructured
        val total = ((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong() + delayMs
        val t = total.coerceAtLeast(0)
        "%02d:%02d:%02d%s%03d".format(t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, sep, t % 1000)
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
