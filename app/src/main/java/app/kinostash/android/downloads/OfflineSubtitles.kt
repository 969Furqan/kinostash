package app.kinostash.android.downloads

import android.content.Context
import app.kinostash.android.App
import app.kinostash.android.data.AddonClient
import app.kinostash.android.data.SubtitleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File

/**
 * Subtitles saved next to a download, so they work offline. Files live in the download's own
 * folder (`subs/<lang>.<n>.srt|vtt`) and are deleted with it.
 */
object OfflineSubtitles {
    /** How many subtitles to keep per download (different releases are often timed differently). */
    private const val PER_DOWNLOAD = 2

    private fun dir(ctx: Context, id: String) = File(OfflineDownloads.itemDir(ctx, id), "subs")

    /** Saved subtitles for a download, ready for the player (file:// URLs). */
    fun saved(ctx: Context, id: String): List<SubtitleItem> =
        dir(ctx, id).listFiles { f -> f.extension == "srt" || f.extension == "vtt" }.orEmpty().sortedBy { it.name }
            .map { f -> SubtitleItem("offline:${f.name}", f.toURI().toString(), f.name.substringBefore('.')) }

    fun hasSaved(ctx: Context, id: String, lang: String) = saved(ctx, id).any { sameLanguage(it.lang, lang) }

    /** "en" == "eng" == "EN": settings use 2-letter codes, subtitle addons mostly 3-letter ones. */
    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        fun norm(x: String) = runCatching { java.util.Locale.forLanguageTag(x).isO3Language }.getOrNull()?.ifBlank { null } ?: x.lowercase()
        return a.equals(b, ignoreCase = true) || norm(a) == norm(b)
    }

    /**
     * Fetch and save the default-language subtitles for [item]. Safe to call repeatedly: does
     * nothing when they're already saved or no default language is set. Returns how many were saved.
     */
    suspend fun fetch(ctx: Context, item: DownloadItem): Int = withContext(Dispatchers.IO) {
        val lang = App.instance.settings.subtitleLanguage
        val type = item.metaType ?: return@withContext 0
        val videoId = item.videoId ?: return@withContext 0
        if (lang.isBlank() || hasSaved(ctx, item.id, lang)) return@withContext 0
        val list = withTimeoutOrNull(15_000) { runCatching { App.instance.addons.subtitles(type, videoId) }.getOrNull() }.orEmpty()
        val picks = list.filter { sameLanguage(it.lang, lang) }.distinctBy { it.url }.take(PER_DOWNLOAD)
        if (picks.isEmpty()) return@withContext 0
        val out = dir(ctx, item.id).apply { mkdirs() }
        coroutineScope {
            picks.mapIndexed { i, sub ->
                async {
                    runCatching {
                        AddonClient.http.newCall(Request.Builder().url(sub.url).header("User-Agent", "Kinostash").build()).execute().use { r ->
                            if (!r.isSuccessful) return@runCatching false
                            val bytes = r.body?.bytes() ?: return@runCatching false
                            val text = String(bytes, Charsets.UTF_8)
                            if (!text.contains("-->")) return@runCatching false // not a usable SRT/VTT
                            val ext = if (text.trimStart('﻿').startsWith("WEBVTT")) "vtt" else "srt"
                            File(out, "$lang.${i + 1}.$ext").writeBytes(bytes)
                            true
                        }
                    }.getOrDefault(false)
                }
            }.awaitAll().count { it }
        }
    }
}
