package com.videoclub.app.data

import java.text.Normalizer

/**
 * Everything this app knows about how the supplier writes a name.
 *
 * The catalogue has no `year` field and no `quality` field. Both are written into the name:
 *
 *     4K - Blade Runner 2049 (2017)
 *     HD60 - Gladiator (2000)
 *     El vuelo del Intruder (1991)
 *
 * 98.7% of films and 99.6% of series carry the trailing year, and 34,000 rows carry a quality
 * prefix. Reading them back out is what turns 177,653 listings into 26,477 films: the same work,
 * listed once per encode and once per category, collapses onto one [Title].
 *
 * Pure functions on purpose — this is the part that has to be right, and it is the part that can be
 * tested without a device.
 */
object TitleNaming {

    data class Parsed(val name: String, val year: Int?, val quality: Quality)

    /**
     * Splits a raw catalogue name into what to show, when it was made, and which encode it is.
     *
     * A name that carries neither marker comes back unchanged, with a null year and [Quality.Hd] —
     * the unprefixed listing *is* the ordinary encode, as the categories it appears in confirm.
     */
    fun parse(rawName: String): Parsed {
        var rest = rawName.trim()

        val quality = QUALITY_PREFIX.find(rest)?.let { match ->
            rest = rest.removeRange(match.range).trimStart()
            byPrefix(match.groupValues[1])
        } ?: Quality.Hd

        val year = TRAILING_YEAR.find(rest)?.let { match ->
            rest = rest.removeRange(match.range).trimEnd()
            match.groupValues[1].toIntOrNull()
        }

        return Parsed(name = rest.ifBlank { rawName.trim() }, year = year, quality = quality)
    }

    /**
     * The part of an episode's title that is actually about the episode.
     *
     * The supplier writes an episode title as the whole filename it came from:
     *
     *     4K - Cape Fear - S01E01 - Episodio 1
     *     Cape Fear (2026) - S01E01 - Episodio 1
     *
     * Both are the same episode, and neither line is worth the width of a phone. Only *leading*
     * segments are dropped, and only while each one is the encode prefix, the series name or the
     * `S01E01` marker — an episode genuinely called `Hands - Manos` keeps both halves, because the
     * stripping stops at the first segment that is none of those. When the whole title turns out to
     * be scaffolding, [fallback] is used: an episode of Fargo titled `Fargo` is not a title.
     */
    fun episodeTitle(rawTitle: String, seriesName: String, fallback: String): String {
        val cleaned = QUALITY_PREFIX.replace(rawTitle.trim(), "")
        val segments = cleaned.split(SEGMENT).map(String::trim).filter(String::isNotEmpty)
        val series = fold(seriesName)
        val kept = segments.dropWhile { segment ->
            val folded = fold(TRAILING_YEAR.replace(segment, ""))
            folded == series || EPISODE_MARKER.matches(segment)
        }
        return kept.joinToString(" - ").ifBlank { fallback }
    }

    /**
     * The key two listings must share to be the same work.
     *
     * Name and year are all there is: `tmdb_id` exists only in the per-title detail call, and asking
     * for it 177,653 times to build a catalogue is not a trade anyone would make. Two different
     * films released the same year under exactly the same name would merge — it has not been seen in
     * this catalogue, and the cost if it happens is one stray entry in a quality picker.
     */
    fun mergeKey(kind: Kind, name: String, year: Int?): String =
        "${kind.name}|${fold(name)}|${year ?: 0}"

    /**
     * Lowercased, stripped of accents, and with punctuation flattened to single spaces.
     *
     * Used both for the merge key and for the search index, so that `blade runner 2049` finds
     * `Blade Runner 2049` and `cigüeñas` finds `Cigueñas`. Spanish titles make the accent folding
     * non-negotiable: nobody types the diaeresis into a television remote.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()
            .replace(NON_ALPHANUMERIC, " ")
            .trim()

    private fun byPrefix(prefix: String): Quality = when (prefix.uppercase()) {
        "4K", "UHD" -> Quality.Uhd
        "HD60", "HD60FPS" -> Quality.Hd60
        "SD" -> Quality.Sd
        else -> Quality.Hd
    }

    /**
     * Only an allowlist is stripped, and only followed by a dash.
     *
     * The catalogue is full of names that legitimately begin with a short word and a dash —
     * `F1 2026 - ...`, `Star Trek X - ...`, `Millennium - ...`. A rule that removed any leading
     * token would quietly mangle those.
     */
    private val QUALITY_PREFIX = Regex("""^(4K|UHD|FHD|HD60FPS|HD60|HD|SD)\s*-\s*""", RegexOption.IGNORE_CASE)
    private val TRAILING_YEAR = Regex("""\s*\(((?:19|20)\d{2})\)\s*$""")
    /** How the supplier joins the parts of an episode filename, spaces around the dash required. */
    private val SEGMENT = Regex("""\s+-\s+""")
    private val EPISODE_MARKER = Regex("""^S\d{1,3}\s*E\d{1,4}$""", RegexOption.IGNORE_CASE)
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    /** Matches runs, so collapsing punctuation to spaces cannot leave doubled ones behind. */
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
}
