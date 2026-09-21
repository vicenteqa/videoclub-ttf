package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The shape below is what `server/admin/futbol.py` publishes, trimmed to one season. */
class FootballJsonTest {

    private val body = """
        {"version": 1, "competiciones": [
          {"id": "es.1-2026-27", "nombre": "LaLiga", "temporada": "2026/27",
           "jornadas": [
             {"numero": 6, "partidos": [
               {"local": "Elche", "visitante": "Real Madrid", "fecha": "2026-09-15", "hora": "21:00",
                "streams": [4573001, 4573002]}]},
             {"numero": 7, "partidos": []}
           ],
           "sin_jornada": [
             {"local": "Uno", "visitante": "Dos", "fecha": "2026-10-01", "streams": [9]}]}
        ]}
    """.trimIndent()

    @Test
    fun `reads a season with its matchdays`() {
        val season = FootballJson.seasons(body).single()

        assertThat(season.name).isEqualTo("LaLiga")
        assertThat(season.season).isEqualTo("2026/27")
        assertThat(season.matchdays.single().number).isEqualTo(6)
        assertThat(season.matchdays.single().matches.single()).isEqualTo(
            FootballMatch("Elche", "Real Madrid", "2026-09-15", "21:00", listOf(4573001, 4573002))
        )
    }

    @Test
    fun `a matchday with nothing in it is left out`() {
        assertThat(FootballJson.seasons(body).single().matchdays.map { it.number }).containsExactly(6)
    }

    @Test
    fun `matches the VPS could not place are kept`() {
        val other = FootballJson.seasons(body).single().unassigned.single()

        assertThat(other.home).isEqualTo("Uno")
        assertThat(other.time).isNull()
    }

    @Test
    fun `a match without streams is not a match`() {
        val seasons = FootballJson.seasons(
            """{"competiciones": [{"id": "x", "jornadas": [{"numero": 1, "partidos": [
                {"local": "A", "visitante": "B", "fecha": "2026-08-15", "streams": []}]}]}]}"""
        )

        assertThat(seasons).isEmpty()
    }

    @Test
    fun `crests are resolved against the directory the file came from`() {
        val match = FootballJson.seasons(
            """{"competiciones": [{"id": "x", "jornadas": [{"numero": 1, "partidos": [
                {"local": "Elche", "visitante": "Real Madrid", "fecha": "2026-09-15", "streams": [1],
                 "escudo_local": "escudos/elche.png"}]}]}]}""",
            baseUrl = "https://vps.example/videoclub/_catalogo/"
        ).single().matchdays.single().matches.single()

        assertThat(match.homeCrest).isEqualTo("https://vps.example/videoclub/_catalogo/escudos/elche.png")
        assertThat(match.awayCrest).isNull()
    }

    @Test
    fun `a title is found in its matchday`() {
        val seasons = FootballJson.seasons(body).map { season ->
            season.copy(matchdays = season.matchdays.map { day ->
                day.copy(matches = day.matches.map { it.copy(titleId = 77) })
            })
        }

        val place = seasons.placeOf(77)

        assertThat(place?.matchday?.number).isEqualTo(6)
        assertThat(place?.season?.season).isEqualTo("2026/27")
        assertThat(seasons.placeOf(78)).isNull()
    }

    @Test
    fun `anything that does not parse is no football at all`() {
        assertThat(FootballJson.seasons("<html>404</html>")).isEmpty()
        assertThat(FootballJson.seasons("{}")).isEmpty()
    }
}
