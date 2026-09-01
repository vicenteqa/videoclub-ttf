package com.videoclub.app.data

import java.text.Normalizer
import java.util.Locale

/**
 * Turns a supplier's raw lineup into the short, ordered list this app shows.
 *
 * A typical Spanish IPTV account carries ~2000 live streams, most of them the same channel repeated
 * at SD/HD/FHD and with second feeds ("Opc2", "Multiaudio", "nodelay"). That is unusable on a living
 * room TV with a remote. This cuts it to the channels worth having, in an order that reads like a
 * remote control: generalistas, news, regional, cinema, documentaries, music, football, other sport.
 *
 * A channel is **one row**, with its remaining feeds kept behind it as a fallback chain. The viewer
 * never sees a quality menu; the player walks the chain by itself when a feed fails.
 *
 * ## Matching survives a rename
 *
 * Suppliers rewrite their own channel names constantly, and an exact-name rule turns that into a row
 * that silently disappears. So a rule does not match a string, it matches a **bag of words plus the
 * family number**: every distinctive word of the rule must be present, the numbers must agree
 * exactly, and whatever the supplier added on top must be [FILLER]. That is what makes `L.Campeones
 * 3`, `Liga de Campeones 3` and `UEFA Champions League 3` land on the same row.
 *
 * Numbers are compared for equality, never containment, which is what keeps `LaLigaTV 2` and
 * `LaLigaTV 3` — different matches played at the same time — as two separate rows whose fallback
 * chains never cross into each other.
 *
 * The rules are deliberately data, not code: adding a channel is one line in [blocks].
 */
object LiveCuration {

    /**
     * One curated row: what to call it on screen, and the ways the supplier might name it.
     *
     * An alias is itself matched as words + numbers, so it only has to cover the *distinctive* part:
     * `Entry("Cine Estrenos", "estrenos")` already catches `Estrenos FHD` and `M+ Estrenos FHD`.
     * Give the head of a numbered family two aliases, with and without the digit, because suppliers
     * disagree about whether the first channel of a family carries a `1`.
     */
    data class Entry(val label: String, val aliases: List<String>) {
        constructor(label: String, vararg aliases: String) :
            this(label, if (aliases.isEmpty()) listOf(label) else aliases.toList())
    }

    /**
     * A run of channels kept together and in this order.
     *
     * [heights] is both the filter and the preference: only these declared heights survive, and the
     * first one listed is what actually plays. Anything else — "Low", an undeclared height — is
     * dropped outright.
     */
    data class Block(val name: String, val heights: List<Int>, val entries: List<Entry>)

    /** Everyday viewing: 1080p only, with 720p kept out of sight in case the good feed dies. */
    private val STANDARD_HEIGHTS = listOf(1080, 720)

    /**
     * Live sport: the feed that works matters more than the feed that looks best, so 720p and the
     * second options are legitimate fallbacks. 4K sits behind 1080p rather than in front of it —
     * the account allows a single connection and the TV is not a 4K set.
     */
    private val LIVE_SPORT_HEIGHTS = listOf(1080, 2160, 720)

    /**
     * Music is the one place SD is allowed, because for these channels there is no better feed to
     * prefer — the supplier carries the decade channels at SD only, and the videoclips were shot
     * that way anyway. Dropping SD here would not raise the quality, it would empty the block.
     */
    private val MUSIC_HEIGHTS = listOf(1080, 720, 576)

    val blocks: List<Block> = listOf(
        Block(
            name = "Generalistas",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("La 1", "la 1", "tve 1"),
                Entry("La 2", "la 2", "tve 2"),
                Entry("Antena 3", "antena 3", "a3"),
                Entry("Cuatro", "cuatro"),
                Entry("Telecinco", "telecinco", "t5"),
                Entry("La Sexta", "lasexta")
            )
        ),
        Block(
            name = "Noticias",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("24 Horas", "24 horas", "24h", "rtve 24"),
                Entry("Euronews", "euronews")
            )
        ),
        Block(
            name = "Andalucía",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("Canal Sur", "canal sur", "canal sur 1", "csur", "csur 1"),
                Entry("Canal Sur 2", "canal sur 2", "csur 2"),
                Entry("Andalucía TV", "andalucia"),
                Entry("Betis TV", "betis", "real betis")
            )
        ),
        Block(
            name = "Catalunya",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("TV3", "tv3"),
                Entry("Esport3", "esport3")
            )
        ),
        Block(
            // Bloque propio y no dentro de «Catalunya»: comparten lengua, no comunidad, y los
            // bloques de aquí agrupan por territorio — igual que «Andalucía» está aparte.
            name = "Comunitat Valenciana",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                // El proveedor la escribe «À Punt»; la tokenización quita el acento antes de
                // comparar, así que el alias va en ASCII a propósito.
                Entry("À Punt", "a punt", "apunt")
            )
        ),
        Block(
            name = "Cine",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("Cine Estrenos", "estrenos"),
                Entry("Cine Hollywood", "hollywood"),
                Entry("Cine Acción", "accion"),
                Entry("Cine Comedia", "comedia"),
                Entry("AMC", "amc"),
                Entry("TCM", "tcm"),
                // Fox was renamed Star Channel in 2021; suppliers still flip between the two.
                Entry("Star Channel", "star", "fox"),
                Entry("Warner TV", "warner")
            )
        ),
        Block(
            name = "Documentales",
            heights = STANDARD_HEIGHTS,
            entries = listOf(
                Entry("Discovery", "discovery"),
                Entry("National Geographic", "natgeo"),
                // Un alias basta para las dos grafías: el pegamento ya convierte tanto
                // «National Geographic» como «Nat Geo» en `natgeo` antes de comparar. Y no le roba
                // nada a la fila de arriba, porque una regla sólo se lleva un nombre si *todas* sus
                // palabras están en el alias: «wild» sobra allí, así que allí no encaja.
                Entry("Nat Geo Wild", "natgeo wild"),
                Entry("Historia", "historia", "history"),
                Entry("Odisea", "odisea"),
                Entry("Documentales", "documentales")
            )
        ),
        Block(
            name = "Música",
            heights = MUSIC_HEIGHTS,
            entries = listOf(
                Entry("Música 70", "70s"),
                Entry("Música 80", "80s"),
                // The supplier pairs the nineties with the noughties on one feed.
                Entry("Música 90", "90s", "90s 00s")
            )
        ),
        Block(
            name = "Fútbol",
            heights = LIVE_SPORT_HEIGHTS,
            entries = listOf(
                Entry("LaLiga TV", "laliga tv", "laliga tv 1"),
                Entry("LaLiga TV 2", "laliga tv 2"),
                Entry("LaLiga TV 3", "laliga tv 3"),
                Entry("DAZN LaLiga", "dazn laliga", "dazn laliga 1"),
                Entry("DAZN LaLiga 2", "dazn laliga 2"),
                Entry("Liga Campeones 1", "campeones", "campeones 1", "champions", "champions 1", "ucl"),
                Entry("Liga Campeones 2", "campeones 2", "champions 2", "ucl 2"),
                Entry("Liga Campeones 3", "campeones 3", "champions 3", "ucl 3"),
                Entry("Liga Campeones 4", "campeones 4", "champions 4", "ucl 4"),
                Entry("Liga Campeones 5", "campeones 5", "champions 5", "ucl 5"),
                Entry("Hypermotion 1", "hypermotion", "hypermotion 1"),
                Entry("Hypermotion 2", "hypermotion 2"),
                Entry("Hypermotion 3", "hypermotion 3"),
                Entry("DAZN 1", "dazn 1"),
                Entry("DAZN 2", "dazn 2"),
                Entry("DAZN 3", "dazn 3"),
                Entry("DAZN 4", "dazn 4"),
                Entry("Movistar Deportes", "deportes", "deportes 1"),
                Entry("Movistar Deportes 2", "deportes 2"),
                Entry("Vamos", "vamos", "vamos 1"),
                Entry("Vamos 2", "vamos 2"),
                Entry("Vamos 3", "vamos 3"),
                Entry("Gol", "gol", "gol play", "goltv"),
                Entry("Movistar Plus+", "movistar plus", "m plus"),
                Entry("1ª Federación", "federacion 1", "fef 1")
            )
        ),
        Block(
            name = "Deportes",
            heights = LIVE_SPORT_HEIGHTS,
            entries = listOf(
                Entry("Teledeporte", "teledeporte", "tdp"),
                Entry("Eurosport 1", "eurosport", "eurosport 1"),
                Entry("Eurosport 2", "eurosport 2"),
                Entry("DAZN F1", "f1", "dazn f1"),
                Entry("DAZN MotoGP", "motogp", "dazn motogp"),
                Entry("DAZN Baloncesto", "baloncesto", "baloncesto 1"),
                Entry("DAZN Baloncesto 2", "baloncesto 2")
            )
        )
    )

    /**
     * Applies the rules. Returns the curated rows in block order, or an empty list when the lineup
     * is not the one the rules were written for — callers keep whatever list they already had rather
     * than replacing it with an almost empty screen.
     */
    fun curate(feeds: List<Feed>): List<Channel> {
        if (feeds.isEmpty()) return emptyList()

        val groups = LinkedHashMap<Int, MutableList<Feed>>()
        feeds.forEach { feed ->
            val rule = resolve(feed.canonicalName.ifBlank { feed.originalName })
            if (rule != null) {
                groups.getOrPut(rule.order) { mutableListOf() }.add(feed)
            }
        }

        return groups
            .mapNotNull { (order, members) -> buildRow(rules[order], members)?.let { order to it } }
            .sortedBy { (order, _) -> order }
            .map { (_, row) -> row }
    }

    // -------------------------------------------------------------------------------- the matcher

    /** A channel name reduced to what identifies it: distinctive words, and the family number. */
    private class NameTokens(val words: Set<String>, val numbers: Set<String>)

    private class Rule(
        val order: Int,
        val label: String,
        val heights: List<Int>,
        val aliases: List<NameTokens>
    )

    /**
     * Words a supplier can add or drop without changing which channel it is: the operator, the
     * region, the word "canal", the competition a channel belongs to. Everything *not* in here is
     * treated as distinctive, which is what stops `Discovery` from swallowing `Discovery Science` —
     * there is no blocklist to keep up to date.
     */
    private val FILLER = setOf(
        "tv", "television", "televisio", "canal", "cine", "channel", "chanel",
        "es", "esp", "espana", "spain", "de", "del", "la", "el", "los", "las", "y", "por",
        "liga", "laliga", "uefa", "league", "movistar", "m", "m+", "plus", "dazn",
        "cat", "catalunya", "now",
        // "L.Campeones", "L.Hypermotion" — the supplier's abbreviation of "Liga".
        "l"
    )

    /**
     * The same brand written glued or split, and the same number written as a word. Rules and
     * channel names both go through this, so they converge: "LaLigaTV", "LaLiga TV" and
     * "La Liga TV" all end up as the same two tokens.
     *
     * Each entry carries a literal that the pattern cannot match without. Tokenising runs on every
     * stream of a ~2000-entry lineup, and a `contains` that fails costs nothing next to a regex that
     * fails — which is the common case for all fifteen.
     */
    private class Glue(val literal: String, pattern: String, val replacement: String) {
        val pattern = Regex(pattern)
    }

    private val GLUE: List<Glue> = listOf(
        Glue("laligatv", """\blaligatv\b""", "laliga tv"),
        Glue("liga", """\bla\s+liga\b""", "laliga"),
        Glue("geographic", """\bnational\s+geographic\b""", "natgeo"),
        Glue("geo", """\bnat\s+geo\b""", "natgeo"),
        Glue("sport", """\beuro\s+sport\b""", "eurosport"),
        Glue("gp", """\bmoto\s*gp\b""", "motogp"),
        Glue("deporte", """\btele\s+deporte\b""", "teledeporte"),
        Glue("cinco", """\btele\s*cinco\b""", "telecinco"),
        Glue("tele", """\btele\s+5\b""", "telecinco"),
        Glue("sexta", """\bla\s+sexta\b""", "lasexta"),
        Glue("esport", """\besport\s+3\b""", "esport3"),
        Glue("primera", """\bprimera\b""", "1"),
        Glue("segunda", """\bsegunda\b""", "2"),
        Glue("formula", """\bformula\s*(?:1|uno)\b""", "f1"),
        Glue("f", """\bf\s+1\b""", "f1")
    )

    private val diacriticRegex = Regex("""\p{InCombiningDiacriticalMarks}+""")

    private fun tokenize(text: String): NameTokens {
        val ascii = if (text.all { char -> char.code < 0x80 }) {
            text
        } else {
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(diacriticRegex, "")
        }
        var flat = ascii.lowercase(Locale.ROOT)
        GLUE.forEach { glue ->
            if (flat.contains(glue.literal)) flat = flat.replace(glue.pattern, glue.replacement)
        }

        val words = mutableSetOf<String>()
        val numbers = mutableSetOf<String>()
        val token = StringBuilder()
        var allDigits = true

        fun flush() {
            if (token.isNotEmpty()) {
                if (allDigits) numbers.add(token.toString()) else words.add(token.toString())
                token.setLength(0)
                allDigits = true
            }
        }

        flat.forEach { char ->
            if (char in 'a'..'z' || char in '0'..'9' || char == '+') {
                if (char !in '0'..'9') allDigits = false
                token.append(char)
            } else {
                flush()
            }
        }
        flush()
        return NameTokens(words, numbers)
    }

    private fun matches(alias: NameTokens, name: NameTokens): Boolean {
        if (!name.words.containsAll(alias.words)) return false
        // Equality, not containment: "LaLigaTV 2" must never answer for "LaLigaTV 3", and the bare
        // head of a family must never answer for one of its numbered siblings.
        if (alias.numbers != name.numbers) return false
        // "+" survives canonicalisation as a token of its own ("Movistar Plus+"). A stray letter
        // gets no such pass, or "DAZN Formula 1" would read as "DAZN 1" with an extra "f".
        return name.words.all { word -> word in alias.words || word in FILLER || word == "+" }
    }

    private val rules: List<Rule> by lazy {
        var order = 0
        blocks.flatMap { block ->
            block.entries.map { entry ->
                Rule(order++, entry.label, block.heights, entry.aliases.map(::tokenize))
            }
        }
    }

    /**
     * Aliases bucketed by one word they require. Every word of an alias has to be present for it to
     * match, so looking up any single one of them is a sound way to skip the rest of the rules.
     */
    private val aliasIndex: Map<String, List<Pair<Rule, NameTokens>>> by lazy {
        rules
            .flatMap { rule -> rule.aliases.map { alias -> rule to alias } }
            .groupBy { (_, alias) -> alias.words.minOrNull().orEmpty() }
    }

    /**
     * The rule this name belongs to, or null.
     *
     * A name can satisfy more than one rule — `Dazn LaLiga 2` satisfies both `DAZN LaLiga 2` and
     * `DAZN 2`, since "laliga" is filler. The alias demanding the most words wins, because it is the
     * more specific claim; block order breaks a genuine tie.
     */
    private fun resolve(name: String): Rule? {
        val tokens = tokenize(name)
        return tokens.words
            .asSequence()
            .flatMap { word -> aliasIndex[word].orEmpty() }
            .filter { (_, alias) -> matches(alias, tokens) }
            .minWithOrNull(
                compareByDescending<Pair<Rule, NameTokens>> { (_, alias) -> alias.words.size }
                    .thenBy { (rule, _) -> rule.order }
            )
            ?.first
    }

    // ------------------------------------------------------------------------------- row assembly

    /**
     * One row: the best feed of the rule, with the runners-up behind it as its fallback chain.
     *
     * Nothing that passed the height filter is discarded, because on a living-room TV there is no
     * one to pick a different quality when a feed goes down — the chain is what the player falls
     * back to on its own. That includes every feed the ordering below puts last: being ranked worst
     * is not being thrown away.
     */
    private fun buildRow(row: Rule, members: List<Feed>): Channel? {
        val feeds = members
            .distinctBy(Feed::streamId)
            .filter { feed -> row.heights.contains(feed.height ?: -1) }
            .sortedWith(
                compareBy(
                    { feed -> regionRank(feed) },
                    { feed -> row.heights.indexOf(feed.height ?: -1) },
                    { feed -> decorationRank(feed) },
                    { feed -> feed.originalName.length },
                    { feed -> feed.originalName }
                )
            )
            .take(MAX_FALLBACK_CHAIN)

        if (feeds.isEmpty()) return null

        return Channel(
            label = row.label,
            // The feed that plays is not always the one the supplier tagged, and every feed here is
            // the same channel, so the row borrows the first tag and logo it can find.
            logoUrl = members.firstNotNullOfOrNull(Feed::logoUrl),
            epgChannelId = feeds.firstNotNullOfOrNull(Feed::epgChannelId),
            feeds = feeds
        )
    }

    /**
     * Whether a feed is in the language this house watches television in. Ranked **above the
     * resolution**, which is the one place in this table where a worse picture wins.
     *
     * Measured on this account: of `DAZN 1`, `DAZN 3` and `DAZN 4` the supplier serves 1080p only
     * as `PT | Dazn 1 FHD`, while every Spanish variant of the same channel is 720p or below. With
     * resolution deciding first, all three rows opened on the Portuguese commentary — a sharper
     * picture of a match nobody in the room could follow. Four hundred and eighty extra lines are
     * worth less than understanding what is being said, and it is not close.
     *
     * A no-prefix feed ranks with `ES` rather than behind it. That is not a guess about its
     * language: on this lineup the prefix is the exception, and it is precisely the foreign feeds
     * that carry one — every Spanish channel from `La 1` to `Teledeporte` is named bare.
     *
     * The demotion is a preference and never a filter. A row whose only feed is foreign still ranks
     * it first by default, because there is nothing else, and still plays.
     */
    private fun regionRank(feed: Feed): Int =
        if (feed.region == null || feed.region in HOME_REGIONS) 0 else 1

    /** The ways this supplier spells the country the television is in. */
    private val HOME_REGIONS = setOf("ES", "ESP")

    /**
     * Rank among feeds of the same resolution: the plain one first, then a second option, then the
     * technical duplicates. HDR goes last — on a TV that cannot display it the picture washes out.
     */
    private fun decorationRank(feed: Feed): Int = when {
        feed.isHdr -> 3
        technicalVariantRegex.containsMatchIn(feed.originalName) -> 2
        secondOptionRegex.containsMatchIn(feed.originalName) -> 1
        else -> 0
    }

    private val secondOptionRegex = Regex("""\(\s*opc""", RegexOption.IGNORE_CASE)
    private val technicalVariantRegex =
        Regex("""\(\s*(?:multiaudio|nodelay)""", RegexOption.IGNORE_CASE)

    /**
     * How deep the per-row fallback chain goes. Six is well past the point of diminishing returns
     * for a channel whose feeds all come from the same supplier, and it bounds the work the player
     * can do retrying a channel that is simply off the air.
     */
    private const val MAX_FALLBACK_CHAIN = 6

    /**
     * Below this the lineup is assumed not to be the one the rules were written for, and a saved
     * list is left alone rather than overwritten with a handful of coincidental matches.
     */
    const val MIN_USABLE_CHANNELS = 8
}
