package com.videoclub.app.data

import androidx.compose.runtime.Immutable

/** Films and series behave identically almost everywhere, so they share one table and one screen. */
enum class Kind { Movie, Series }

/**
 * Which encode of a title this is.
 *
 * The supplier does not expose this as a field. It publishes the same film several times under
 * different `stream_id`s and writes the difference into the name: `4K - Gladiator (2000)`,
 * `HD60 - Gladiator (2000)`, `Gladiator (2000)`. [TitleNaming] reads it back out.
 *
 * Declaration order is preference order, best first — the ordinal is what the source list is sorted
 * by, so do not reorder these casually. A name with no prefix at all is the plain [Hd] encode.
 *
 * [notable] is whether the badge is worth drawing: nearly everything is [Hd], so a wall of "HD"
 * stickers would say nothing. Only the encodes that beat it earn one.
 */
enum class Quality(val label: String, val notable: Boolean) {
    Uhd("4K", true),
    Hd60("HD60", true),
    Hd("HD", false),
    Sd("SD", false)
}

/**
 * One playable encode of a title: what to ask the server for, and how good it is.
 *
 * ### Why the models carry `@Immutable`
 *
 * Every one of them holds a `List`, and Compose assumes a list can change under it — so a screen
 * handed the same row twice recomposes twice, because it has no way to know the second copy says
 * the same thing. These lists never change: they are read out of SQLite into `val`s and thrown
 * away. Saying so is what lets a poster wall sit still while a sync bumps the catalogue two hundred
 * times behind it, and what keeps the ten-second position save during playback from redrawing a
 * screen that is not even on.
 */
@Immutable
data class Source(
    /** `stream_id` for a film, `series_id` for a series. */
    val remoteId: Int,
    val quality: Quality,
    /** `mkv` for the overwhelming majority; `avi`, `mp4` and `ts` also occur. Empty for series. */
    val container: String
)

/**
 * One film or one series, after the duplicates have been folded together.
 *
 * The raw catalogue lists a popular film sixty-odd times: three encodes across some forty
 * categories. A row here is the *work*, not a listing — which is what a poster wall needs.
 */
@Immutable
data class Title(
    /** Local row id. Stable for as long as the database lives, and meaningless to the supplier. */
    val id: Long,
    val kind: Kind,
    /** Display name with the quality prefix and the trailing year removed. */
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    /** TMDB's 0–10 score, when the supplier passed it through. */
    val rating: Double?,
    /** When the supplier added it, epoch seconds. The only ordering that means "new". */
    val addedSeconds: Long,
    /**
     * The best encode available, as a single aggregate — a cheap `MIN(quality)` rather than the
     * full [sources] list, which is what lets a row of posters be read in one query.
     */
    val bestQuality: Quality? = null,
    /** Best encode first. Only loaded for the one title being looked at, never for a whole row. */
    val sources: List<Source> = emptyList()
) {
    val bestSource: Source? get() = sources.firstOrNull()
}

/**
 * One card on the home screen: a title already started, and exactly what to play to carry on.
 *
 * The resolution happens in the repository rather than in the screen because the answer is not
 * always "where you stopped". Finish episode four of a series and what you want next is episode
 * five, from the beginning — which means asking the supplier what episode five is.
 */
@Immutable
data class ContinueEntry(
    val title: Title,
    /** Season × 1000 + number, matching [Episode.key]. Zero for a film. */
    val episodeKey: Int,
    val season: Int?,
    val episodeNumber: Int?,
    /** Only known when this is the *next* episode, since that one had to be fetched to be found. */
    val episodeTitle: String?,
    /** Where playback starts. Zero when this is the next episode rather than the one half-watched. */
    val startMillis: Long,
    /** 0f–1f through it, for the bar under the poster. Zero for a next episode. */
    val fraction: Float,
    /** What is left of it, when the duration is known. */
    val minutesLeft: Int?
) {
    /** True when this offers the next episode instead of resuming the one before it. */
    val isNextEpisode: Boolean get() = startMillis == 0L && fraction == 0f
}

/** A supplier category, i.e. one row of the home screen. */
@Immutable
data class Category(
    val id: Long,
    val kind: Kind,
    val remoteId: String,
    /** As the supplier writes it: `ÚLTIMOS ESTRENOS HD`, `NETFLIX HD`, `DRAMA HD`. */
    val name: String,
    /** Position in the supplier's own ordering, which is roughly editorial. */
    val position: Int,
    val titleCount: Int = 0
)

/**
 * The expensive half of a title, fetched one at a time from `get_vod_info` / `get_series_info` and
 * cached afterwards. Nothing here is available in the catalogue listing for films.
 */
@Immutable
data class TitleDetail(
    val plot: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val releaseDate: String? = null,
    val durationSeconds: Int? = null,
    val tmdbId: String? = null,
    val backdropUrl: String? = null,
    /** YouTube video id or URL, as the supplier stored it. Present on about four titles in ten. */
    val trailer: String? = null,
    val videoCodec: String? = null,
    val videoHeight: Int? = null,
    val audioCodec: String? = null,
    val bitrateBps: Long? = null
) {
    /** `HEVC 2160p · E-AC3 · 26 Mbps`, or null when the supplier probed nothing useful. */
    val techLine: String?
        get() = listOfNotNull(
            videoCodec?.uppercase()?.let { codec -> videoHeight?.let { "$codec ${it}p" } ?: codec },
            audioCodec?.uppercase(),
            bitrateBps?.takeIf { it > 0 }?.let { "${it / 1_000_000} Mbps" }
        ).takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/**
 * The audio and the subtitles somebody was watching something in.
 *
 * Languages rather than track indices, and that is the whole reason this is worth storing: the
 * fourth audio track of the 4K copy is not the fourth of the HD one, and next week's re-encode may
 * have a different number of them again. A language survives all of that, and Media3 can be asked
 * for one directly.
 */
@Immutable
data class TrackChoice(
    /** ISO code of the audio, or null when nothing was ever chosen. */
    val audio: String? = null,
    /** ISO code of the subtitles, `""` for deliberately off, null for never having said. */
    val subtitle: String? = null
) {
    val isEmpty: Boolean get() = audio == null && subtitle == null

    /** True when subtitles were switched off on purpose, as opposed to never having been touched. */
    val subtitlesOff: Boolean get() = subtitle?.isEmpty() == true
}

/** One playable copy of one episode. The same idea as [Source], one level down. */
@Immutable
data class EpisodeSource(
    /** The episode's own `id`, which is what the `/series/` URL is built from. */
    val remoteId: Int,
    /** Inherited from the series listing this copy came out of, not from the episode itself. */
    val quality: Quality,
    val container: String
)

/**
 * One episode of a series, folded across every copy the supplier publishes.
 *
 * A series is duplicated the same way a film is — `4K - The Bear (2022)` and `The Bear (2022)` are
 * two `series_id`s — but with a catch films do not have: **the copies are not the same series.** The
 * 4K listing of The Bear carries seasons 2, 3 and 5, and only five of season 2's ten episodes; the
 * ordinary one carries all five seasons complete. Reading either alone loses episodes, so this is
 * the union of both, and [sources] is which copies happen to have this one.
 */
@Immutable
data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String? = null,
    val stillUrl: String? = null,
    val durationSeconds: Int? = null,
    /** Best encode first, like [Title.sources]. Never empty in practice. */
    val sources: List<EpisodeSource> = emptyList()
) {
    /**
     * Which episode this is, independent of which copy plays it.
     *
     * Resume positions are stored against this rather than against an `id`, so that watching half of
     * the 4K copy and finishing on the HD one is one viewing and not two. Season and episode number
     * are the only things the two copies agree on.
     */
    val key: Int get() = season * 1000 + number

    val bestSource: EpisodeSource? get() = sources.firstOrNull()

    /** The copy that will play unless the viewer picks otherwise. */
    fun sourceFor(quality: Quality?): EpisodeSource? =
        sources.firstOrNull { it.quality == quality } ?: bestSource
}
