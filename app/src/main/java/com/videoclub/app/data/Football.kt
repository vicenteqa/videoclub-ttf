package com.videoclub.app.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * One league match, as the VPS paired it with the league's fixture list.
 *
 * The supplier files matches as films — `Atletico Madrid vs Real Madrid 20.09.2026`, with the same
 * picture on every one of them — and never says which matchday they belong to. `futbol.py`, next to
 * the catalogue mirror, works that out once for every household; see `server/admin/futbol.py`.
 *
 * [streamIds] are the supplier's own ids, best copy first. [titleId] is the local title they were
 * folded into, filled in by [FootballRepository] — null until then, and a match whose copies this
 * catalogue does not have yet is never shown at all.
 *
 * There is no score here and never will be: a list of matches to watch that says how each one ended
 * is a list of matches nobody needs to watch any more.
 */
@Immutable
data class FootballMatch(
    val home: String,
    val away: String,
    /** `yyyy-MM-dd`, as the fixture list has it. */
    val date: String,
    /** `HH:mm`, local to Spain; null when the fixture list had no kick-off time. */
    val time: String?,
    val streamIds: List<Int>,
    val titleId: Long? = null,
    /** Each side's crest, a full URL on the VPS, or null where it has none yet. */
    val homeCrest: String? = null,
    val awayCrest: String? = null
)

/** A match together with where it sits: what a match's own page says under its name. */
@Immutable
data class MatchPlace(val season: FootballSeason, val matchday: Matchday?, val match: FootballMatch)

/** Where the match filed as [titleId] sits, or null when that title is not a league match. */
fun List<FootballSeason>.placeOf(titleId: Long): MatchPlace? {
    for (season in this) {
        season.matchdays.forEach { day ->
            day.matches.firstOrNull { it.titleId == titleId }?.let { return MatchPlace(season, day, it) }
        }
        season.unassigned.firstOrNull { it.titleId == titleId }?.let { return MatchPlace(season, null, it) }
    }
    return null
}

/**
 * One row of a competition: a league matchday, or a knockout round.
 *
 * [name] is set for the rounds that are not simply numbered — `Octavos · ida`, `Final` — and null
 * for a matchday, which is written `Jornada [number]`. [number] orders the rows either way.
 */
@Immutable
data class Matchday(val number: Int?, val matches: List<FootballMatch>, val name: String? = null)

/** One season of one competition: `LaLiga`, `2026/27`, its matchdays in order. */
@Immutable
data class FootballSeason(
    val id: String,
    val name: String,
    val season: String,
    val matchdays: List<Matchday>,
    /** The competition's own logo, a full URL on the VPS, for the tile that opens it. */
    val logo: String? = null,
    /** Matches the VPS could not place in a matchday. Kept, because they are still matches. */
    val unassigned: List<FootballMatch>
)

/**
 * `futbol.json`, read. Pure, so the whole format is tested without a network.
 *
 * Forgiving in the way the catalogue parser is: a match without teams or streams is skipped, a
 * season without matches is dropped, and a document that does not parse is no football at all
 * rather than a crash.
 */
object FootballJson {

    /**
     * [baseUrl] is the directory the file was read from: crests are named relative to it, as
     * `escudos/elche.png`, so the VPS can be moved without rewriting the file.
     */
    fun seasons(body: String, baseUrl: String = ""): List<FootballSeason> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val competitions = root.optJSONArray("competiciones") ?: return emptyList()
        return (0 until competitions.length()).mapNotNull { index ->
            competitions.optJSONObject(index)?.let { season(it, baseUrl.trimEnd('/')) }
        }
    }

    private fun season(json: JSONObject, baseUrl: String): FootballSeason? {
        fun matches(array: JSONArray?) = matches(array, baseUrl)
        val matchdays = json.optJSONArray("jornadas").objects().mapNotNull { day ->
            val matches = matches(day.optJSONArray("partidos"))
            if (matches.isEmpty()) null
            else Matchday(
                number = day.optInt("numero").takeIf { it > 0 },
                matches = matches,
                name = day.optString("nombre").takeIf { it.isNotBlank() && it != "null" }
            )
        }
        val unassigned = matches(json.optJSONArray("sin_jornada"))
        if (matchdays.isEmpty() && unassigned.isEmpty()) return null
        return FootballSeason(
            id = json.optString("id"),
            name = json.optString("nombre"),
            season = json.optString("temporada"),
            matchdays = matchdays,
            unassigned = unassigned,
            logo = crest(json.optString("logo"), baseUrl)
        )
    }

    private fun matches(array: JSONArray?, baseUrl: String): List<FootballMatch> = array.objects().mapNotNull { match ->
        val home = match.optString("local").trim()
        val away = match.optString("visitante").trim()
        val streams = match.optJSONArray("streams")
            ?.let { ids -> (0 until ids.length()).map { ids.optInt(it) }.filter { it > 0 } }
            .orEmpty()
        if (home.isEmpty() || away.isEmpty() || streams.isEmpty()) return@mapNotNull null
        FootballMatch(
            home = home,
            away = away,
            date = match.optString("fecha"),
            time = match.optString("hora").takeIf { it.isNotBlank() && it != "null" },
            streamIds = streams,
            homeCrest = crest(match.optString("escudo_local"), baseUrl),
            awayCrest = crest(match.optString("escudo_visitante"), baseUrl)
        )
    }

    private fun crest(path: String, baseUrl: String): String? = when {
        path.isBlank() || path == "null" -> null
        path.startsWith("http") -> path
        baseUrl.isEmpty() -> null
        else -> "$baseUrl/${path.trimStart('/')}"
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
