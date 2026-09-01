package com.videoclub.app.data

/**
 * Folds the episode lists of every copy of a series into one list.
 *
 * The lists arrive in the same order as [Title.sources], best encode first, and the merge keeps
 * that: the first copy to mention an episode supplies its description and its still, later ones
 * only add themselves as another way to play it and fill in whatever the first one left blank.
 *
 * The union matters more than it sounds. The supplier's 4K listing of a series is not a better
 * version of the ordinary one, it is a *different and usually smaller* one — a few seasons, often
 * only part of a season. Showing whichever single listing happened to win hides episodes that the
 * account can perfectly well play.
 */
fun mergeEpisodes(lists: List<List<Episode>>): List<Episode> {
    val merged = LinkedHashMap<Int, Episode>()
    for (list in lists) {
        for (episode in list) {
            val existing = merged[episode.key]
            merged[episode.key] = existing?.mergedWith(episode) ?: episode
        }
    }
    return merged.values.sortedWith(compareBy(Episode::season, Episode::number))
}

private fun Episode.mergedWith(other: Episode): Episode = copy(
    title = title.ifBlank { other.title },
    plot = plot ?: other.plot,
    stillUrl = stillUrl ?: other.stillUrl,
    durationSeconds = durationSeconds ?: other.durationSeconds,
    // Two listings of the same episode at the same quality would be the supplier duplicating itself,
    // which it does; one entry per encode is what the picker is for.
    sources = (sources + other.sources)
        .distinctBy(EpisodeSource::remoteId)
        .distinctBy(EpisodeSource::quality)
        .sortedBy { it.quality.ordinal }
)
