package app.kinostash.android.data

/** Preferred video quality for "play best" and automatic downloads. */
enum class QualityPref(val label: String, val value: String?) {
    BEST("Best available", null), Q2160("4K (2160p)", "2160p"), Q1080("1080p", "1080p"), Q720("720p", "720p"), Q480("480p", "480p"),
}

object StreamPicker {
    private val ORDER = listOf("2160p", "1080p", "720p", "480p")

    /**
     * Qualities to try, best match first: the preferred one, then the next lower ones, then higher
     * ones (e.g. 1080p -> 720p -> 480p -> 2160p). "Best available" is simply highest first.
     */
    fun qualityOrder(pref: QualityPref): List<String?> {
        val p = pref.value ?: return ORDER + listOf(null)
        val i = ORDER.indexOf(p)
        return ORDER.drop(i) + ORDER.take(i).reversed() + listOf(null)
    }

    /** The stream to use: the first quality in [qualityOrder] that exists, and within it the one with the most seeders. */
    fun pick(streams: List<StreamItem>, pref: QualityPref, forDownload: Boolean = false): StreamItem? {
        val usable = streams.filter { it.isPlayable }
        for (q in qualityOrder(pref)) {
            val candidates = usable.filter { it.quality == q }
            if (candidates.isNotEmpty()) {
                // Direct (debrid) links are instant; otherwise most seeders = fastest.
                return candidates.maxWithOrNull(compareBy<StreamItem>({ if (!it.isTorrent) 1 else 0 }, { it.seeders ?: 0 }))
            }
        }
        return usable.maxByOrNull { it.seeders ?: 0 }
    }
}
