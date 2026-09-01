package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The catalogue writes the year and the encode into the name and nowhere else, so this is the code
 * that turns 232,000 listings into 33,000 works. Every case below is a real name from the account.
 */
class TitleNamingTest {

    @Test
    fun `reads the year out of the name`() {
        val parsed = TitleNaming.parse("El vuelo del Intruder (1991)")

        assertThat(parsed.name).isEqualTo("El vuelo del Intruder")
        assertThat(parsed.year).isEqualTo(1991)
    }

    @Test
    fun `reads the quality out of the prefix`() {
        assertThat(TitleNaming.parse("4K - Blade Runner 2049 (2017)").quality).isEqualTo(Quality.Uhd)
        assertThat(TitleNaming.parse("HD60 - Gladiator (2000)").quality).isEqualTo(Quality.Hd60)
    }

    @Test
    fun `an unprefixed listing is the ordinary encode, not an unknown one`() {
        assertThat(TitleNaming.parse("Gladiator (2000)").quality).isEqualTo(Quality.Hd)
    }

    @Test
    fun `strips the prefix and the year together`() {
        val parsed = TitleNaming.parse("4K - Spider-Man: Cruzando el Multiverso (2023)")

        assertThat(parsed.name).isEqualTo("Spider-Man: Cruzando el Multiverso")
        assertThat(parsed.year).isEqualTo(2023)
        assertThat(parsed.quality).isEqualTo(Quality.Uhd)
    }

    @Test
    fun `leaves alone a title that merely begins with a short word and a dash`() {
        // 25 rows in this catalogue are named like this. Stripping any leading token would eat them.
        val parsed = TitleNaming.parse("F1 2026 - Gran Premio de Baréin (2026)")

        assertThat(parsed.name).isEqualTo("F1 2026 - Gran Premio de Baréin")
        assertThat(parsed.quality).isEqualTo(Quality.Hd)
    }

    @Test
    fun `a name with no year at all survives intact`() {
        val parsed = TitleNaming.parse("Secretos del deporte El testimonio")

        assertThat(parsed.name).isEqualTo("Secretos del deporte El testimonio")
        assertThat(parsed.year).isNull()
    }

    @Test
    fun `a year in the middle of a title is not the release year`() {
        val parsed = TitleNaming.parse("Blade Runner 2049 (2017)")

        assertThat(parsed.name).isEqualTo("Blade Runner 2049")
        assertThat(parsed.year).isEqualTo(2017)
    }

    @Test
    fun `the three encodes of one film share a merge key`() {
        val keys = listOf(
            "Blade Runner 2049 (2017)",
            "4K - Blade Runner 2049 (2017)",
            "HD60 - Blade Runner 2049 (2017)"
        ).map { raw ->
            val parsed = TitleNaming.parse(raw)
            TitleNaming.mergeKey(Kind.Movie, parsed.name, parsed.year)
        }

        assertThat(keys.toSet()).hasSize(1)
    }

    @Test
    fun `two remakes of the same name stay apart`() {
        val original = TitleNaming.parse("It (1990)")
        val remake = TitleNaming.parse("It (2017)")

        assertThat(TitleNaming.mergeKey(Kind.Movie, original.name, original.year))
            .isNotEqualTo(TitleNaming.mergeKey(Kind.Movie, remake.name, remake.year))
    }

    @Test
    fun `a film and a series of the same name stay apart`() {
        assertThat(TitleNaming.mergeKey(Kind.Movie, "Fargo", 1996))
            .isNotEqualTo(TitleNaming.mergeKey(Kind.Series, "Fargo", 1996))
    }

    @Test
    fun `folding removes the accents nobody types into a remote`() {
        assertThat(TitleNaming.fold("Cigüeñas")).isEqualTo("ciguenas")
        assertThat(TitleNaming.fold("Baréin")).isEqualTo("barein")
    }

    @Test
    fun `folding flattens punctuation so a colon cannot hide a match`() {
        assertThat(TitleNaming.fold("Spider-Man: Cruzando el Multiverso"))
            .isEqualTo("spider man cruzando el multiverso")
    }

    @Test
    fun `both copies of an episode end up with the same short title`() {
        assertThat(
            TitleNaming.episodeTitle("4K - Cape Fear - S01E01 - Episodio 1", "Cape Fear", "?")
        ).isEqualTo("Episodio 1")

        assertThat(
            TitleNaming.episodeTitle("Cape Fear (2026) - S01E01 - Episodio 1", "Cape Fear", "?")
        ).isEqualTo("Episodio 1")
    }

    @Test
    fun `an episode that already has a real title keeps all of it`() {
        assertThat(TitleNaming.episodeTitle("Ceniza a las cenizas", "The Bear", "?"))
            .isEqualTo("Ceniza a las cenizas")

        assertThat(TitleNaming.episodeTitle("The Bear - S01E02 - Hands - Manos", "The Bear", "?"))
            .isEqualTo("Hands - Manos")
    }

    @Test
    fun `an episode named after its own series is not stripped down to nothing`() {
        assertThat(TitleNaming.episodeTitle("Fargo", "Fargo", "Episodio 1"))
            .isEqualTo("Episodio 1")
    }
}
