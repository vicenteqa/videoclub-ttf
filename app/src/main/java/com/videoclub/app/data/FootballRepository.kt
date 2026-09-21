package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The league, as matchdays: what the `Fútbol` tab draws.
 *
 * The pairing of matches with matchdays is done on the VPS, once for every household — see
 * [FootballMatch]. What is left for the device is the one thing only it can know: which of those
 * matches are in *this* catalogue yet. The VPS reads the mirror the moment it is written, and a
 * device picks the same matches up on its next catch-up, so for a few minutes after a match is
 * published the list can be ahead of the shelves. Such a match is simply left out until it is here;
 * a card that opens nothing would be worse than a card that arrives ten minutes later.
 *
 * Empty until the VPS publishes something, and the tab strip leaves the tab out while it is.
 */
class FootballRepository(
    private val client: VodClient,
    private val store: CatalogStore,
    private val scope: CoroutineScope
) {

    private val _seasons = MutableStateFlow<List<FootballSeason>>(emptyList())

    /** Newest season first, each with only the matches this catalogue can open. */
    val seasons: StateFlow<List<FootballSeason>> = _seasons.asStateFlow()

    private var job: Job? = null
    private var fetchedAtMillis = 0L

    /** The VPS's file as last read, before this catalogue was asked which matches it has. */
    private var published: List<FootballSeason> = emptyList()

    /**
     * Asks the VPS again at most every [REFRESH_INTERVAL_MS] — the file only changes when the mirror
     * runs, every two hours — but asks this catalogue every time. That half is a few dozen indexed
     * reads, and it is what brings a match onto the tab as soon as the catalogue catches up with it,
     * rather than ten minutes later; on a fresh install, where the file arrives before the catalogue
     * does, it is the difference between an empty tab and a full one.
     */
    fun refresh(nowMillis: Long) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (nowMillis - fetchedAtMillis >= REFRESH_INTERVAL_MS) {
                // Stamped whether or not it arrived: a VPS without the file yet is asked again in
                // ten minutes, not on every poll.
                fetchedAtMillis = nowMillis
                client.football()?.let { published = it }
            }
            val playable = withContext(Dispatchers.IO) { published.mapNotNull(::playable) }
            if (playable != _seasons.value) {
                _seasons.value = playable
                Log.i(TAG, "Football: ${playable.sumOf { s -> s.matchdays.sumOf { it.matches.size } }} matches")
            }
        }
    }

    /** [season] with every match given its local title, and the ones this catalogue lacks removed. */
    private fun playable(season: FootballSeason): FootballSeason? {
        fun resolve(matches: List<FootballMatch>) = matches.mapNotNull { match ->
            match.streamIds.firstNotNullOfOrNull(store::movieTitleForStream)
                ?.let { match.copy(titleId = it) }
        }

        val matchdays = season.matchdays.mapNotNull { day ->
            resolve(day.matches).takeIf { it.isNotEmpty() }?.let { day.copy(matches = it) }
        }
        val unassigned = resolve(season.unassigned)
        if (matchdays.isEmpty() && unassigned.isEmpty()) return null
        return season.copy(matchdays = matchdays, unassigned = unassigned)
    }

    private companion object {
        const val TAG = "FootballRepository"
        const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    }
}
