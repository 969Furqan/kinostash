package app.kinostash.android.data

/** A catalog an addon offers (from its manifest). */
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String,
    val supportsSearch: Boolean,
    val requiresExtra: Boolean,
)

/** An installed Stremio addon. */
data class Addon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val logo: String?,
    val types: List<String>,
    val catalogs: List<CatalogDef>,
    /** resource name -> (types, idPrefixes); empty lists mean "any". */
    val resources: Map<String, Pair<List<String>, List<String>>>,
) {
    val baseUrl: String get() = transportUrl.substringBefore("/manifest.json").trimEnd('/')

    fun supports(resource: String, type: String, id: String): Boolean {
        val (rTypes, rPrefixes) = resources[resource] ?: return false
        val typeOk = rTypes.isEmpty() || type in rTypes
        val prefixOk = rPrefixes.isEmpty() || rPrefixes.any { id.startsWith(it) }
        return typeOk && prefixOk
    }
}

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val releaseInfo: String?,
    val imdbRating: String? = null,
)

data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    /** Release time as ISO-8601 (e.g. 2026-11-30T04:00:00.000Z), if known. */
    val released: String?,
    val thumbnail: String?,
) {
    val releasedAt: Long? get() = released?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    val releasedDate: String? get() = released?.take(10)
}

data class MetaDetail(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val genres: List<String>,
    val imdbRating: String?,
    val videos: List<Video>,
    /** e.g. "Continuing" or "Ended" for series. */
    val status: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
)

/** A stream from a stream addon: a torrent ([infoHash]) or a direct link ([url]). */
data class StreamItem(
    val addonName: String,
    val name: String,
    val description: String,
    val infoHash: String?,
    val fileIdx: Int?,
    val url: String?,
    val trackers: List<String>,
    val filename: String?,
) {
    val isTorrent get() = infoHash != null
    val isPlayable get() = infoHash != null || (url?.startsWith("http") == true)
    val seeders: Int? get() = Regex("""👤\s*(\d+)""").find(description)?.groupValues?.get(1)?.toIntOrNull()
    val sizeLabel: String? get() = Regex("""💾\s*([\d.,]+\s*[KMGT]B)""").find(description)?.groupValues?.get(1)
    val quality: String? get() = Regex("""(?i)\b(2160p|4k|1080p|720p|480p)\b""").find("$name $description")?.value?.lowercase()
        ?.let { if (it == "4k") "2160p" else it }

    /** Stable key for "is this exact stream already downloaded". */
    val key: String get() = infoHash?.let { "$it/${fileIdx ?: -1}" } ?: url.orEmpty()
}

data class SubtitleItem(val id: String, val url: String, val lang: String)
