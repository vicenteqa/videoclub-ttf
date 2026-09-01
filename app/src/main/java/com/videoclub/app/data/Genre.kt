package com.videoclub.app.data

/**
 * The shelves this video shop has, as opposed to the ones the supplier ships with.
 *
 * Declaration order is the order they appear on screen, and it is by how often somebody browses for
 * one rather than alphabetical: a page that opens with `Animación` and `Bélico` buries the four
 * genres most of the catalogue is.
 *
 * Written as a person writes them and not as the supplier does. The supplier shouts — `TERROR 4K`,
 * `SERIES NARCOS HD` — because its names are database keys that happen to be shown to people. These
 * are the shop's own shelf labels, they sit above a wall of posters that is already loud, and a
 * heading in capitals reads as an announcement rather than as a place.
 */
enum class Genre(val label: String) {
    Action("Acción"),
    Comedy("Comedia"),
    Drama("Drama"),
    Horror("Terror"),
    Thriller("Suspense"),
    SciFi("Ciencia ficción"),
    Adventure("Aventura"),
    Crime("Crimen"),
    Mystery("Misterio"),
    Fantasy("Fantasía"),
    Animation("Animación"),
    Family("Infantil"),
    Romance("Romance"),
    History("Historia"),
    War("Bélico"),
    Western("Oeste"),
    Documentary("Documental"),
    Musical("Musical"),
    Reality("Reality")
}

/**
 * Reads a genre out of a supplier category name.
 *
 * ### Why the name and not the metadata
 *
 * Each title *does* have a real genre field, but only in the per-title detail call — one request per
 * film, 33,000 of them, to build a page. The category name is free and already on the device: the
 * supplier files the same film under `TERROR HD`, `PELIS ZOMBIES 4K` and `PELIS VAMPIROS HD`, and
 * every one of those names says what the film is.
 *
 * ### What is thrown away
 *
 * Most of the 625 film categories are not genres. Two hundred are one actor each, sixty are football
 * seasons, twenty are streaming services, thirty are release years. None of them map here, so none of
 * them become a row — which is the whole point of doing this. They remain reachable by searching.
 *
 * ### Why themes count as genres
 *
 * `SERIES ZOMBIES` is horror whether or not the supplier filed it under `TERROR`, and for series that
 * matters enormously: the supplier's own series genres are twelve coarse buckets, while its themed
 * lists are a hundred and forty sharp ones. Folding the themes into the genre they belong to is what
 * makes the series shelves as full as the film ones.
 *
 * Pure and keyed on text, so the whole mapping is testable without a device or a network.
 */
object Genres {

    /** Every genre this category belongs to, in [Genre] order. Empty for the ones that are not. */
    fun of(categoryName: String): Set<Genre> = BY_THEME[theme(categoryName)].orEmpty()

    /**
     * True for the shelves that are sport rather than cinema.
     *
     * Matched on the words of the name and not on the whole of it, because unlike a genre these are
     * not a fixed vocabulary: `UEFA CHAMPIONS LEAGUE 26-27 HD` becomes `27-28` next summer,
     * `BRASILEIRAO SERIE A 26-27` was not in the account last month, and `MUNDIAL USA 2026` will be
     * followed by whatever they call the next one. What does not change is that one of the words is
     * a competition or a sport.
     *
     * Fifty-one of the account's categories are these, and between them they hold 1,437 titles that
     * exist nowhere else — every one of them stamped with the day it was uploaded, which is why
     * `Novedades` was a list of football matches and not of films.
     */
    fun isSport(categoryName: String): Boolean {
        val folded = TitleNaming.fold(categoryName)
        if (folded.split(' ').any { it in SPORT_WORDS }) return true
        return SPORT_PHRASES.any { phrase -> " $folded ".contains(" $phrase ") }
    }

    /**
     * True for the shelf of things that are neither a film nor a series.
     *
     * `PROGRAMAS TV` is 344 titles of chat shows, magazines and the eight o'clock news — El
     * Chiringuito, Cuarto Milenio, Informativos Telecinco, En boca de todos. They arrive by the
     * dozen every week, each stamped with the day it was uploaded, which is exactly what it takes
     * to fill a row called `Añadido recientemente`.
     *
     * Unlike [isSport] this is not conditional on the title living nowhere else. It cannot be: El
     * Chiringuito is filed under `REALITY` and `SERIES ESPAÑOLAS` as well, so a rule that spared
     * anything with a second home would spare it. Being on this shelf is enough to say what it is.
     */
    fun isTelevision(categoryName: String): Boolean = theme(categoryName) in TELEVISION_THEMES

    /**
     * True when a category names a *subject* — a genre, a theme, an actor, a director.
     *
     * The opposite of one is a category that says where a film was streamed, what year it came out
     * or that somebody at the supplier liked it: `NETFLIX HD`, `PELICULAS HD 2000`, `TOP PELICULAS
     * 4K`. Every film has half a dozen of those and they are the same half dozen for all of them,
     * so they say nothing about what a film *is* — which makes them worthless for deciding what
     * somebody who liked it might like next.
     *
     * The editorial ones fall out for free: `TOP PELICULAS 4K` is a shelf word, a shelf word and an
     * encode, so [theme] has nothing left of it at all.
     */
    fun isSubject(categoryName: String): Boolean {
        val theme = theme(categoryName)
        return theme.isNotEmpty() &&
            theme !in PLATFORM_THEMES &&
            theme !in EDITORIAL_THEMES &&
            !YEAR.containsMatchIn(theme) &&
            !isSport(categoryName) &&
            !isTelevision(categoryName)
    }

    /**
     * True for the shelves a child can be handed.
     *
     * A whitelist, and a short one. There is no age rating anywhere in this catalogue — not in the
     * listing, not in the per-title detail — so the only thing to go on is which shelf the supplier
     * filed something under, and the supplier files by *medium*: `ANIMACION` and `TOP INFANTIL` both
     * contain Rick y Morty, BoJack Horseman and Chainsaw Man, because all three are drawn.
     *
     * So this is only half the rule. [isForGrownUps] is the other half, and a title has to pass both.
     */
    fun isForChildren(categoryName: String): Boolean = theme(categoryName) in CHILD_THEMES

    /**
     * True for the shelves that disqualify a title from a child's profile, whatever else it is on.
     *
     * The genres first — horror, thriller, crime and war are the four that are never a mistake to
     * keep out — and then the four themes that exist precisely to say "drawn, but not for children",
     * which is the distinction the whitelist cannot make on its own.
     */
    fun isForGrownUps(categoryName: String): Boolean {
        val theme = theme(categoryName)
        return theme in GROWN_UP_THEMES || of(categoryName).any { it in GROWN_UP_GENRES }
    }

    /**
     * The category name with everything that is not the subject removed.
     *
     * `SERIES ANIMACIÓN ADULTOS 4K`, `PELIS ANIME HD` and `TOP INFANTIL 4K` are all filed the same
     * way: a word saying which shelf of the shop it is, the subject, and the encode. Only the middle
     * one is a genre, so the other two go. Dropping the shelf word is also what lets one table entry
     * cover both kinds — `PELIS ZOMBIES` and `SERIES ZOMBIES` are the same theme written twice.
     */
    fun theme(categoryName: String): String = TitleNaming.fold(categoryName)
        .split(' ')
        .filterNot { it in QUALITY_WORDS }
        .dropWhile { it in SHELF_WORDS }
        .joinToString(" ")

    /** What the file is, never what the film is. */
    private val QUALITY_WORDS = setOf("4k", "hd", "sd", "fhd", "hd60", "hd60fps", "uhd")

    /**
     * Which shelf of the shop, never what the film is.
     *
     * Dropped only from the front and only while every word so far has been one of these, so
     * `PELIS CINE NEGRO` keeps `CINE NEGRO` — the rule would otherwise eat the subject of any
     * category whose subject happens to start with one of these words.
     *
     * Both of these sets are declared above the tables that use them: [theme] reads them, [BY_THEME]
     * calls [theme] while it is being built, and a Kotlin object initialises its properties in the
     * order they are written.
     */
    private val SHELF_WORDS = setOf("peliculas", "pelicula", "pelis", "series", "serie", "top")

    /**
     * A word that only ever appears in the name of a competition, a league or a sport.
     *
     * Deliberately words and not substrings: `PELIS TERREMOTOS` contains `moto`, and `SERIES
     * ASESINOS EN SERIE` contains `serie`. Checked against all 943 categories of the account, this
     * list catches the fifty-one that are sport and nothing else.
     */
    private val SPORT_WORDS = setOf(
        "deporte", "deportes", "futbol", "laliga", "liga", "ligue", "league", "championship",
        "mundial", "uefa", "fifa", "copa", "supercopa", "brasileirao", "eredivisie", "pokal",
        "coppa", "ufc", "nba", "nfl", "mlb", "motogp", "f1", "boxeo", "tenis", "golf", "olimpiadas",
        "premier", "efl", "amistosos", "tour", "hypermotion", "easports", "saudi", "superliga",
        "primeira", "shield"
    )

    /** The three competitions whose name is only a competition when the words are next to each other. */
    private val SPORT_PHRASES = setOf("serie a", "el clasico", "moto gp")

    /**
     * Where it was streamed, which is not what it is.
     *
     * Folded like everything else, so `MOVISTAR PLUS+` and `APPLE TV+` arrive without their signs.
     * `DISNEY` is in here for the same reason it is not a children's shelf: after folding, the
     * supplier's Disney *catalogue* and the Disney+ *platform* are one word.
     */
    private val PLATFORM_THEMES = setOf(
        "NETFLIX", "DISNEY", "MAX", "HBO", "MOVISTAR PLUS", "AMAZON PRIME VIDEO", "APPLE TV",
        "RAKUTEN TV", "FILMIN", "PARAMOUNT", "SKY SHOWTIME", "PLEX", "CRUNCHYROLL",
        "MEDIASET INFINITY", "RTVE PLAY", "ACORN TV", "BRITBOX", "ATRESPLAYER", "PLUTO TV",
        "STARZ", "MUBI", "TIVIFY", "SKY", "ORANGE TV", "VODAFONE TV", "HULU", "AMC+", "LIONSGATE+",
        "TCM", "RUNTIME"
    ).map(::theme).toSet()

    /**
     * Somebody at the supplier's opinion, which is not a subject either.
     *
     * These are the ones that cost a real suggestion before they were listed: Toy Story sits on
     * `ULTIMOS ESTRENOS` and `50 MÁS VISTAS`, and so does everything else that arrived that week —
     * which is how a children's film came to recommend `Posesión infernal. En llamas`. What they
     * have in common with each other is the date they were uploaded, and nothing else.
     */
    private val EDITORIAL_THEMES = setOf(
        "ULTIMOS ESTRENOS", "ESTRENOS", "NOVEDADES", "EN CURSO - NOVEDADES", "50 MÁS VISTAS",
        "MAS VISTAS", "PELICULA DE LA SEMANA", "SERIE DE LA SEMANA", "SAGAS", "PREMIADAS",
        "RECOMENDADAS", "IMPRESCINDIBLES", "MEJORES", "LARGAS", "CORTAS", "MINISERIES"
    ).map(::theme).toSet()

    /** `2026`, `1900-1999`: when a film came out, which is nobody's reason for liking it. */
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    /** Television, as opposed to cinema. See [isTelevision]. */
    private val TELEVISION_THEMES =
        setOf("PROGRAMAS TV", "PROGRAMAS", "TELEVISION", "PROGRAMAS TELEVISION")
            .map(::theme)
            .toSet()

    /**
     * The shelves a child's profile is built out of. See [isForChildren].
     *
     * `DISNEY` is deliberately absent. [TitleNaming.fold] turns punctuation into spaces, so the
     * supplier's `PELICULAS DISNEY` and its `DISNEY+` — which is the platform, and holds The Bear,
     * every Marvel film and the whole of FX — arrive here as the same theme, and there is no way to
     * keep the first without the second. It costs three titles: everything else on that shelf is
     * already on `ANIMACION` or `TOP INFANTIL`.
     */
    private val CHILD_THEMES =
        setOf("ANIMACION", "INFANTIL", "FAMILIA", "PIXAR", "DREAMWORKS", "NAVIDAD")
            .map(::theme)
            .toSet()

    /**
     * The works [isForGrownUps] is not allowed to take away, by name.
     *
     * ### Why a list of names and not another rule
     *
     * `PELIS ANIME` is a grown-up shelf because it carries Devilman, Baki, Ghost in the Shell and
     * Urotsukidôji — all four of which the supplier also filed under `TOP INFANTIL`, which is what
     * the veto exists for. But it carries the whole of Ghibli too, and there is nothing in the
     * catalogue that tells the two apart: `age` and `mpaa_rating` come back empty from the detail
     * call, and TMDB's own genres say `Familia` for four titles in fifty.
     *
     * What settles it is that the veto is already arbitrary. The supplier filed Totoro, Chihiro and
     * Nicky under `PELIS ANIME` and did not file El castillo ambulante, Ponyo, Arrietty or El
     * castillo en el cielo — so five Ghibli films are on the child's shelves today and nineteen are
     * not, for no reason anybody could defend. A hand-written list is not a worse rule than that
     * one. It is the only honest way to say a thing this catalogue does not record.
     *
     * ### What it does not do
     *
     * It lifts the veto, never the whitelist: a name here still has to be on one of [CHILD_THEMES]
     * to be shown at all. So a typo, or a name the supplier reuses for something else, cannot put a
     * film on a child's screen that was not already on the supplier's own children's shelf.
     *
     * ### How it is matched
     *
     * As a prefix of the folded title, which is what makes one line cover the twenty-two Doraemon
     * films and the twenty-one Pokémon ones. Written the way the shop writes them and folded here,
     * so that the accents and the punctuation are somebody else's problem — and so that no entry
     * can contain a `%` or a `_`, which is why the query that reads this needs no `ESCAPE`.
     */
    val CHILD_SAFE_TITLES: List<String> = listOf(
        // Ghibli, entire, including the five the supplier happens to leave alone today. The list is
        // of works, not of the supplier's mistakes this week, so it does not change when they do.
        "Mi vecino Totoro",
        "El viaje de Chihiro",
        "La princesa Mononoke",
        "Nausicaä del valle del viento",
        "Porco Rosso",
        "El castillo ambulante",
        "El castillo en el cielo",
        "Ponyo en el acantilado",
        "Arrietty y el mundo de los diminutos",
        "El viento se levanta",
        "La tumba de las luciérnagas",
        "El chico y la garza",
        "El cuento de la princesa Kaguya",
        "Nicky, la aprendiz de bruja",
        "Susurros del corazón",
        "La colina de las amapolas",
        "Recuerdos del ayer",
        "Pompoko",
        "Mis vecinos los Yamada",
        "Puedo escuchar el mar",
        "Cuentos de Terramar",
        "Haru en el reino de los gatos",
        "Earwig y la bruja",
        "La tortuga roja",
        "Mary y la flor de la Bruja",
        // Takahata and Ponoc before and beside Ghibli, filed by the supplier in the same place.
        "Las Aventuras de Hols",
        "Goshu, el Violoncelista",
        "Marco: no te vayas mamá",
        // The franchises a child's television has always been made of. One line each, because the
        // supplier holds twenty-two Doraemon films and twenty-one Pokémon ones.
        "Doraemon",
        "Stand by Me Doraemon",
        "Pokémon",
        "La película Pokémon",
        "Héroes Pokémon",
        "Shin Chan",
        "Le llamaban Shin Chan",
        "Detective Conan",
        "Digimon",
        "Inazuma Eleven",
        "Dr. Slump",
        "Buscando a la mágica Doremi",
        "Yo-kai Watch",
        "Astro Boy",
        "Las aventuras de Panda",
        // Drawn for small children, and swept up by the same veto for being drawn abroad.
        "El Grúfalo",
        "La hija del Grúfalo",
        "Unico",
        "El pequeño Nemo",
        "¡Socorro, soy un pez!",
        "Mune, el guardián de la luna",
        "Khumba",
        "Zambezia",
        "Gatos: Un viaje de vuelta a casa",
        "Ratchet & Clank",
        "La vuelta al mundo en 80 días por el gato con botas",
        "Continuaban llamándole el gato con botas"
    ).map(TitleNaming::fold)

    /** Drawn, and not for children. The whitelist cannot tell these from the rest. */
    private val GROWN_UP_THEMES =
        setOf("ANIMACION ADULTOS", "ADULT SWIM", "ANIME", "CRUNCHYROLL", "EROTICO", "ADULTOS")
            .map(::theme)
            .toSet()

    private val GROWN_UP_GENRES = setOf(Genre.Horror, Genre.Thriller, Genre.Crime, Genre.War)

    /** Written as the supplier writes them, minus the shelf word and the encode. */
    private val THEMES: Map<Genre, List<String>> = mapOf(
        Genre.Action to listOf(
            "ACCION", "ACCION & AVENTURA", "A.MARCIALES", "SUPERHEROES", "MARVEL", "DC",
            "DC COMICS", "VENGANZA", "VIDEOJUEGOS", "CATASTROFES", "TERREMOTOS", "TORNADOS",
            "VOLCANES"
        ),
        Genre.Comedy to listOf("COMEDIA", "SITCOM"),
        Genre.Drama to listOf(
            "DRAMA", "BASADAS HECHOS REALES", "MEDICOS", "BOMBEROS", "PERIODISTAS", "CORPORATIVAS",
            "ADOLESCENTES", "LGTBI+", "RELIGION", "GENIOS"
        ),
        Genre.Horror to listOf(
            "TERROR", "ZOMBIES", "VAMPIROS", "DEMONIOS", "EXORCISMOS", "POSESIONES", "BRUJERIA",
            "MONSTRUOS", "CASAS ENCANTADAS", "SECTAS", "SUPERNATURALES", "TIBURONES"
        ),
        Genre.Thriller to listOf(
            "SUSPENSE", "THRILLER", "PSICOLOGICAS", "ESPIONAJE", "HACKERS", "DESAPARICIONES",
            "SECUESTROS", "FALSAS IDENTIDADES", "CAZA ASESINOS", "PANDEMIAS"
        ),
        Genre.SciFi to listOf(
            "SCI-FI", "SCI-FI & FANTASIA", "ALIENIGENAS", "ESPACIALES", "INVASIONES", "ROBOTS",
            "IA", "VIAJES EN EL TIEMPO", "DISTOPICAS", "APOCALIPTICAS"
        ),
        Genre.Adventure to listOf(
            "AVENTURA", "ACCION & AVENTURA", "SELVA", "MONTAÑA", "SUPERVIVENCIA", "NAUFRAGIOS"
        ),
        Genre.Crime to listOf(
            "CRIMEN", "MAFIA", "NARCOS", "POLICIALES", "POLICIACAS", "CARCELARIAS", "ROBOS/ATRACOS",
            "ESTAFAS", "CORRUPCION", "JUICIOS", "ABOGADOS", "INVESTIGACION", "ASESINATOS",
            "ASESINOS EN SERIE", "CRIMENES REALES", "CINE NEGRO"
        ),
        Genre.Mystery to listOf("MISTERIO", "MISTERIOS"),
        Genre.Fantasy to listOf("FANTASIA", "SCI-FI & FANTASIA"),
        Genre.Animation to listOf(
            "ANIMACION", "ANIME", "ANIMACION ADULTOS", "ADULT SWIM", "DISNEY", "PIXAR", "DREAMWORKS"
        ),
        Genre.Family to listOf("INFANTIL", "FAMILIA", "NAVIDAD", "DISNEY", "PIXAR"),
        Genre.Romance to listOf("ROMANCE", "ROMANTICAS", "TELENOVELAS LATINAS"),
        Genre.History to listOf(
            "HISTORIA", "HISTORICAS", "MEDIEVALES", "REYES Y REINAS", "DINASTIAS", "VIKINGOS/REINOS"
        ),
        Genre.War to listOf("BELICAS", "BELICA & POLITICA", "NAZIS", "MILITARES"),
        Genre.Western to listOf("OESTE"),
        Genre.Documentary to listOf("DOCUMENTAL", "DOCUPELIS", "CRIMENES REALES"),
        Genre.Musical to listOf("MUSICALES", "CONCIERTOS"),
        Genre.Reality to listOf("REALITY")
    )

    /**
     * Theme to genres, inverted from [THEMES] and folded the same way a category name is.
     *
     * A theme can belong to two shelves — `SCI-FI & FANTASIA` is one supplier category and two
     * genres, and a Pixar film is both animation and something to put on for a child — so the value
     * is a set. Iterating [THEMES] in declaration order keeps those sets in [Genre] order.
     */
    private val BY_THEME: Map<String, Set<Genre>> = buildMap<String, MutableSet<Genre>> {
        THEMES.forEach { (genre, themes) ->
            themes.forEach { name -> getOrPut(theme(name)) { mutableSetOf() } += genre }
        }
    }
}
