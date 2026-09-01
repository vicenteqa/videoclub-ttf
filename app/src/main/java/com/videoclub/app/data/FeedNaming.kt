package com.videoclub.app.data

import java.util.Locale

/**
 * Reads the three things curation needs out of a supplier's stream name.
 *
 * Suppliers encode everything in the name: `ES: LaLigaTV 2 FHD H265 (Opc 2)`. The resolution and
 * the codec are facts worth keeping; for deciding *which channel this is* they are noise, and a
 * matcher that sees them rejects a perfectly good stream.
 *
 * The bracketed part is deliberately dropped from [canonicalName] but left alone in the original
 * name, because [LiveCuration] ranks second options and technical duplicates by reading it.
 */
object FeedNaming {

    /**
     * Resolution tags in descending specificity — `full hd` has to be tried before `hd`, or every
     * 1080p feed reads as 720p.
     */
    private val RESOLUTION_TAGS: Map<String, Int> = linkedMapOf(
        "8k" to 4320, "4320p" to 4320,
        "ultra hd" to 2160, "ultrahd" to 2160, "uhd" to 2160, "4k" to 2160, "2160p" to 2160,
        "qhd" to 1440, "2k" to 1440, "1440p" to 1440,
        "full hd" to 1080, "fullhd" to 1080, "fhd" to 1080, "1080p" to 1080, "1080i" to 1080,
        "hd" to 720, "720p" to 720,
        "576p" to 576, "hq" to 576, "sd" to 576,
        "540p" to 540,
        "480p" to 480
    )

    /**
     * Tags that say nothing about which channel this is. Two-letter entries are limited to the ones
     * that unambiguously mean a codec — a short token stripped by mistake mangles a channel name.
     */
    private val NOISE_TAGS: List<String> = listOf(
        "dolby vision", "hdr10", "hdr", "hevc", "h265", "x265", "h264", "x264", "av1",
        "mpeg-ts", "mpeg ts", "hls", "m3u8",
        "backup", "alternate", "multiaudio", "nodelay", "lite", "vip", "premium",
        "24fps", "25fps", "30fps", "50fps", "60fps", "fps"
    )

    private val bracketRegex = Regex("""\[[^]]*]|\([^)]*\)|\|[^|]*\|""")

    /** A leading country or region code: `ES: `, `ES - `, `UK | `. */
    private val leadingRegionRegex =
        Regex("""^\s*([a-z]{2,3})\s*[:|\-]\s*""", RegexOption.IGNORE_CASE)

    private val bareHeightRegex =
        Regex("""(?<!\d)(4320|2160|1440|1080|720|576|540|480|360|240)\s*[pi]?(?!\d)""")

    private val separatorRegex = Regex("""[\s_\-./]+""")

    /** Every strippable phrase as a standalone-token pattern, longest first. */
    private val strippableRegexes: List<Regex> = (RESOLUTION_TAGS.keys + NOISE_TAGS)
        .sortedByDescending(String::length)
        .map { phrase ->
            Regex("""(?<![a-z0-9])${Regex.escape(phrase)}(?![a-z0-9])""", RegexOption.IGNORE_CASE)
        }

    private val hdrRegex = Regex("""(?<![a-z0-9])(hdr\d*|dolby\s+vision|dv)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    /** The name with the quality, codec and bracketed decoration taken off. */
    fun canonicalName(rawName: String): String {
        var cleaned = rawName
            .replace(bracketRegex, " ")
            .replace(leadingRegionRegex, " ")

        strippableRegexes.forEach { regex -> cleaned = cleaned.replace(regex, " ") }
        cleaned = bareHeightRegex.replace(cleaned, " ")

        cleaned = cleaned
            .replace("+", " + ")
            .replace(Regex("""[:|]"""), " ")
            .replace(separatorRegex, " ")
            .trim()

        return cleaned.ifBlank { rawName.trim() }
    }

    /**
     * The vertical resolution the supplier declared, or null when it declared none.
     *
     * Null is not "unknown, assume the worst" — curation treats it as "not one of the heights this
     * block accepts" and drops the feed. That is deliberate: an untagged feed on an account that
     * tags everything else is usually the broken one.
     */
    fun declaredHeight(rawName: String): Int? {
        val lower = rawName.lowercase(Locale.ROOT)
        bareHeightRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        RESOLUTION_TAGS.forEach { (tag, height) ->
            val regex = Regex("""(?<![a-z0-9])${Regex.escape(tag)}(?![a-z0-9])""")
            if (regex.containsMatchIn(lower)) return height
        }
        return null
    }

    fun isHdr(rawName: String): Boolean = hdrRegex.containsMatchIn(rawName)

    /**
     * The country the supplier put in front of the name, uppercased, or null when there is none.
     *
     * The very same prefix [canonicalName] throws away, kept this time instead of only removed.
     * Reading it here rather than re-deriving it in the curation rules is what guarantees the two
     * can never disagree about where a prefix ends and a channel name begins — the failure that
     * would turn `DE | Dazn 1 Bar` into a channel called `Bar`.
     */
    fun region(rawName: String): String? =
        leadingRegionRegex.find(rawName)?.groupValues?.get(1)?.uppercase(Locale.ROOT)

    /** Everything at once, which is how callers actually use it. */
    fun describe(
        streamId: Int,
        rawName: String,
        epgChannelId: String?,
        logoUrl: String?
    ): Feed = Feed(
        streamId = streamId,
        originalName = rawName,
        canonicalName = canonicalName(rawName),
        region = region(rawName),
        height = declaredHeight(rawName),
        isHdr = isHdr(rawName),
        epgChannelId = epgChannelId?.takeIf(String::isNotBlank),
        logoUrl = logoUrl?.takeIf(String::isNotBlank)
    )
}
