package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The genre shelves are read out of the supplier's category names, so this is the code that decides
 * what the Films and Series tabs are made of. Every name below is a real category from the account.
 */
class GenreTest {

    @Test
    fun `the three encodes of a genre are one shelf`() {
        listOf("TERROR 4K", "TERROR HD", "TERROR HD60FPS").forEach { name ->
            assertThat(Genres.of(name)).containsExactly(Genre.Horror)
        }
    }

    @Test
    fun `a theme is the genre it belongs to`() {
        assertThat(Genres.of("PELIS ZOMBIES 4K")).containsExactly(Genre.Horror)
        assertThat(Genres.of("SERIES VAMPIROS HD")).containsExactly(Genre.Horror)
        assertThat(Genres.of("SERIES NARCOS 4K")).containsExactly(Genre.Crime)
        assertThat(Genres.of("PELIS VIAJES EN EL TIEMPO HD")).containsExactly(Genre.SciFi)
    }

    @Test
    fun `one category the supplier bundled is two shelves here`() {
        assertThat(Genres.of("SCI-FI & FANTASIA 4K"))
            .containsExactly(Genre.SciFi, Genre.Fantasy)
        assertThat(Genres.of("ACCION & AVENTURA HD"))
            .containsExactly(Genre.Action, Genre.Adventure)
    }

    @Test
    fun `the shelf word is not the subject`() {
        // `PELIS`, `SERIES` and `TOP` say which list this is, never what is in it.
        assertThat(Genres.of("TOP INFANTIL 4K")).containsExactly(Genre.Family)
        assertThat(Genres.of("SERIES ANIMACIÓN ADULTOS HD")).containsExactly(Genre.Animation)
        assertThat(Genres.of("TOP DOCUPELIS HD")).containsExactly(Genre.Documentary)
    }

    @Test
    fun `a subject that starts with a shelf word keeps it`() {
        // Stripping every leading noise word rather than only the run of them would leave `NEGRO`.
        assertThat(Genres.theme("PELIS CINE NEGRO HD")).isEqualTo("cine negro")
        assertThat(Genres.of("PELIS CINE NEGRO HD")).containsExactly(Genre.Crime)
    }

    @Test
    fun `accents and punctuation do not decide a shelf`() {
        // The same account writes both `SERIES MÉDICOS` and `PELIS A.MARCIALES`.
        assertThat(Genres.of("SERIES MÉDICOS 4K")).containsExactly(Genre.Drama)
        assertThat(Genres.of("PELIS A.MARCIALES HD")).containsExactly(Genre.Action)
        assertThat(Genres.of("SERIES ROBOS/ATRACOS HD")).containsExactly(Genre.Crime)
        assertThat(Genres.of("SERIES MONTAÑA 4K")).containsExactly(Genre.Adventure)
    }

    @Test
    fun `what is not a genre becomes no row at all`() {
        // Two hundred actors, sixty leagues, thirty years and twenty platforms, none of which is a
        // shelf in a video shop.
        listOf(
            "JASON STATHAM 4K",
            "PREMIER LEAGUE 26-27 HD",
            "UEFA CHAMPIONS LEAGUE 25-26 4K",
            "PELICULAS 2026 HD",
            "SERIES 1900-1999 4K",
            "NETFLIX 4K",
            "MOVISTAR PLUS+ HD",
            "SERIES COREANAS HD",
            "TOP SERIES 4K",
            "PELIS DOLBY VISION 4K",
            "SERIES 4K"
        ).forEach { name ->
            assertThat(Genres.of(name)).isEmpty()
        }
    }

    @Test
    fun `a competition is sport whatever season it is`() {
        // Real names, and the season in them is why this cannot be a table of themes: every one of
        // these is renamed each summer.
        listOf(
            "UEFA CHAMPIONS LEAGUE 26-27 HD",
            "BRASILEIRAO SERIE A 26-27",
            "SERIE A 26-27",
            "LIGA EASPORTS 25-26",
            "MUNDIAL QATAR 2022 4K",
            "EL CLASICO",
            "CLÁSICOS LALIGA",
            "COPA DEL REY CLÁSICO",
            "FA COMMUNITY SHIELD 26-27",
            "MOTO GP",
            "UFC",
            "TOUR FRANCIA 2026 HD",
            "PELIS DEPORTES HD"
        ).forEach { name ->
            assertThat(Genres.isSport(name)).isTrue()
        }
    }

    @Test
    fun `a word that merely contains a sport is not one`() {
        // `TERREMOTOS` has `moto` in it and `ASESINOS EN SERIE` has `serie`; both are shelves of
        // films, and both were caught by an earlier version of this that matched substrings.
        listOf(
            "PELIS TERREMOTOS HD",
            "SERIES ASESINOS EN SERIE HD",
            "TOP CLASICOS 4K",
            "SERIE DE LA SEMANA HD",
            "TERROR 4K",
            "NETFLIX HD"
        ).forEach { name ->
            assertThat(Genres.isSport(name)).isFalse()
        }
    }

    @Test
    fun `a child's shelves are the ones the supplier means as children's`() {
        listOf("TOP INFANTIL HD", "ANIMACION 4K", "FAMILIA HD", "PELICULAS PIXAR 4K",
            "PELIS DREAMWORKS HD", "PELIS NAVIDAD HD").forEach { name ->
            assertThat(Genres.isForChildren(name)).isTrue()
        }

        // `ANIME` carries Chainsaw Man, and `DISNEY+` is a platform that carries The Bear — the one
        // that costs `PELICULAS DISNEY` its place, since folding makes the two names one theme.
        listOf("DISNEY+ HD", "PELICULAS DISNEY HD", "PELIS ANIME HD", "SERIES ANIMACIÓN ADULTOS HD",
            "NETFLIX 4K").forEach { name ->
            assertThat(Genres.isForChildren(name)).isFalse()
        }
    }

    @Test
    fun `the veto is what the whitelist cannot say on its own`() {
        // Every one of these also sits on `ANIMACION` or `TOP INFANTIL`, which is the whole problem:
        // the supplier files by medium, so its children's shelves hold Rick y Morty.
        listOf(
            "SERIES ANIMACIÓN ADULTOS HD",
            "ADULT SWIM HD",
            "PELIS ANIME HD",
            "CRUNCHYROLL HD",
            "TERROR HD",
            "SERIES THRILLER HD",
            "SERIES ASESINOS EN SERIE HD",
            "PELIS NAZIS HD"
        ).forEach { name ->
            assertThat(Genres.isForGrownUps(name)).isTrue()
        }

        listOf("TOP INFANTIL HD", "ANIMACION HD", "PELIS NAVIDAD HD", "COMEDIA HD")
            .forEach { name -> assertThat(Genres.isForGrownUps(name)).isFalse() }
    }

    @Test
    fun `the exceptions are written the way the database stores a name`() {
        // They are matched against `title.search_name`, which the sync writes as the folded name.
        // An entry that is not already folded silently matches nothing at all.
        Genres.CHILD_SAFE_TITLES.forEach { entry ->
            assertThat(entry).isEqualTo(TitleNaming.fold(entry))
        }

        // A prefix, so an entry that is a prefix of another is a line that does nothing.
        Genres.CHILD_SAFE_TITLES.forEach { entry ->
            val covered = Genres.CHILD_SAFE_TITLES.filter { it != entry && entry.startsWith(it) }
            assertThat(covered).isEmpty()
        }
    }

    @Test
    fun `the veto does not take Ghibli off a child's shelves`() {
        // Every name is a real listing from the account, written as the supplier writes it. All of
        // them are on `TOP INFANTIL` or `ANIMACION` *and* on `PELIS ANIME`, which is what used to
        // leave a child's videoclub with El castillo ambulante on it and Totoro not.
        listOf(
            "4K - Mi vecino Totoro (1988)",
            "El viaje de Chihiro (2001)",
            "Nicky, la aprendiz de bruja (1989)",
            "Nausicaä del valle del viento (1984)",
            "Susurros del corazón (1995)",
            "HD60 - Porco Rosso (1992)",
            "Doraemon en busca del escarabajo dorado (2012)",
            "Stand by Me Doraemon 2 (2020)",
            "Pokémon: Genesect y el despertar de una leyenda (2013)",
            "La película Pokémon:  ¡Te elijo a ti! (2017)",
            "Shin Chan: El Superhéroe (2023)",
            "Detective Conan 22: El caso Zero (2018)",
            "El Grúfalo (2009)"
        ).forEach { rawName -> assertThat(isSpared(rawName)).isTrue() }
    }

    @Test
    fun `and it still takes away what it was written for`() {
        // The other half of `PELIS ANIME`, and the supplier filed every one of these under a
        // children's shelf too.
        listOf(
            "Devilman Volumen 1: El nacimiento (1987)",
            "Baki Hanma vs. Kengan Ashura (2024)",
            "Chainsaw Man - La película: El arco de Reze (2025)",
            "Urotsukidôji. La leyenda del señor del mal (1989)",
            "4K - Ghost in the Shell (1995)",
            "Vampire Hunter D: Bloodlust (2001)",
            "Berserk. La edad de oro I: El huevo del rey conquistador (2012)"
        ).forEach { rawName -> assertThat(isSpared(rawName)).isFalse() }
    }

    /** The rule `Shelves.spared` is turned into, which the store writes as `search_name LIKE ?`. */
    private fun isSpared(rawName: String): Boolean {
        val searchName = TitleNaming.fold(TitleNaming.parse(rawName).name)
        return Genres.CHILD_SAFE_TITLES.any(searchName::startsWith)
    }

    @Test
    fun `a subject is what a film is, not where it was streamed`() {
        // The good half of the vocabulary, and the reason suggestions work at all: this account
        // files Gladiator under its two leads and its director.
        listOf("RUSSELL CROWE HD", "RIDLEY SCOTT HD", "PELIS ZOMBIES 4K", "TERROR HD",
            "TOP BRITANICAS 4K").forEach { name ->
            assertThat(Genres.isSubject(name)).isTrue()
        }
    }

    @Test
    fun `what every film is on says nothing about any of them`() {
        // `ULTIMOS ESTRENOS` and `50 MÁS VISTAS` are how Toy Story came to suggest a horror film:
        // everything uploaded that week shares them, and that is all they have in common.
        listOf(
            "NETFLIX HD",
            "AMAZON PRIME VIDEO 4K",
            "HULU HD",
            "PELICULAS HD 2000",
            "SERIES 1900-1999 4K",
            "ULTIMOS ESTRENOS HD",
            "50 MÁS VISTAS 4K",
            "EN CURSO - NOVEDADES HD",
            "TOP PELICULAS 4K",
            "PROGRAMAS TV HD",
            "UEFA CHAMPIONS LEAGUE 26-27 HD"
        ).forEach { name ->
            assertThat(Genres.isSubject(name)).isFalse()
        }
    }

    @Test
    fun `television is a shelf of its own`() {
        assertThat(Genres.isTelevision("PROGRAMAS TV HD")).isTrue()
        // Platforms are not television, whatever their name says.
        listOf("APPLE TV+ HD", "RAKUTEN TV 4K", "SKY SHOWTIME HD", "COMEDIA HD").forEach { name ->
            assertThat(Genres.isTelevision(name)).isFalse()
        }
    }

    @Test
    fun `a genre row is every category that names it, in Genre order`() {
        val categories = listOf(
            category(1, "ULTIMOS ESTRENOS HD", position = 0),
            category(2, "TERROR 4K", position = 1),
            category(3, "TERROR HD", position = 2),
            category(4, "DRAMA HD", position = 3),
            category(5, "JOHN WAYNE HD", position = 4),
            category(6, "PELIS ZOMBIES HD", position = 5)
        )

        val rows = RowPlan.rows(categories)

        assertThat(rows.map { it.heading }).containsExactly("Drama", "Terror").inOrder()
        assertThat(rows.first { it.heading == "Terror" }.categoryIds)
            .containsExactly(2L, 3L, 6L)
            .inOrder()
    }

    @Test
    fun `a row keeps the supplier's ordering of the categories in it`() {
        val rows = RowPlan.rows(
            listOf(
                category(9, "PELIS ZOMBIES HD", position = 40),
                category(7, "TERROR HD", position = 2)
            )
        )

        assertThat(rows.single().categoryIds).containsExactly(7L, 9L).inOrder()
    }

    private fun category(id: Long, name: String, position: Int) = Category(
        id = id,
        kind = Kind.Movie,
        remoteId = id.toString(),
        name = name,
        position = position
    )
}
