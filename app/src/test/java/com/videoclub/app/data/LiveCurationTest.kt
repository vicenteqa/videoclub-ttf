package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The channel names in these tests are copied verbatim from a real Spanish lineup dump, not invented.
 * A curation rule that only survives names of my own choosing is worth nothing.
 */
class LiveCurationTest {

    private var nextId = 1

    private fun feeds(vararg names: String): List<Feed> =
        names.map { name -> FeedNaming.describe(nextId++, name, null, null) }

    private fun labels(vararg names: String): List<String> =
        LiveCuration.curate(feeds(*names)).map(Channel::label)

    @Test
    fun `rows come back in block order, not in the order the supplier sent them`() {
        val rows = labels(
            "Teledeporte FHD",
            "Discovery FHD",
            "La 1 FHD",
            "Estrenos FHD"
        )

        assertThat(rows).containsExactly("La 1", "Cine Estrenos", "Discovery", "Teledeporte").inOrder()
    }

    @Test
    fun `a lineup the rules were not written for produces nothing`() {
        val rows = labels("Animal Kingdom 24_7 FHD", "Aquarius 24_7 FHD", "BBC One FHD")

        assertThat(rows).isEmpty()
    }

    @Test
    fun `the supplier's abbreviation still lands on the right row`() {
        assertThat(labels("L.Campeones 3 FHD")).containsExactly("Liga Campeones 3")
        assertThat(labels("UEFA Champions League 3 FHD")).containsExactly("Liga Campeones 3")
    }

    @Test
    fun `a numbered sibling never answers for another`() {
        val rows = LiveCuration.curate(feeds("LaLigaTV 2 FHD", "LaLigaTV 3 FHD"))

        assertThat(rows.map(Channel::label)).containsExactly("LaLiga TV 2", "LaLiga TV 3").inOrder()
        assertThat(rows.flatMap { row -> row.feeds }.map(Feed::originalName)).hasSize(2)
    }

    @Test
    fun `the bare head of a family does not swallow its numbered siblings`() {
        val rows = LiveCuration.curate(feeds("LaLigaTV FHD", "LaLigaTV 2 FHD"))

        assertThat(rows.map(Channel::label)).containsExactly("LaLiga TV", "LaLiga TV 2").inOrder()
    }

    @Test
    fun `a different channel that starts with the same words is not absorbed`() {
        val rows = labels("La 1 FHD", "La 1 Internacional FHD", "Star Trek Discovery 24_7 FHD")

        assertThat(rows).containsExactly("La 1")
    }

    @Test
    fun `the runners-up stay behind the best feed as a fallback chain`() {
        val row = LiveCuration.curate(
            feeds(
                "LaLigaTV 2 SD",
                "LaLigaTV 2 HDR-HD",
                "LaLigaTV 2 HD (Opc2)",
                "LaLigaTV 2 HD",
                "LaLigaTV 2 HDR-FHD",
                "LaLigaTV 2 FHD"
            )
        ).single()

        assertThat(row.feeds.map(Feed::originalName)).containsExactly(
            // 1080p first, and within a resolution the plain feed before the decorated ones.
            "LaLigaTV 2 FHD",
            "LaLigaTV 2 HDR-FHD",
            "LaLigaTV 2 HD",
            "LaLigaTV 2 HD (Opc2)",
            "LaLigaTV 2 HDR-HD"
            // SD is not a height this block accepts, so it is gone rather than last.
        ).inOrder()
    }

    @Test
    fun `an untagged feed is dropped rather than guessed at`() {
        val rows = LiveCuration.curate(feeds("La 1", "La 1 Low"))

        assertThat(rows).isEmpty()
    }

    @Test
    fun `4K sits behind 1080p, because one connection and no 4K set`() {
        val row = LiveCuration.curate(feeds("La 1 UHD", "Teledeporte 4K", "Teledeporte FHD")).single()

        assertThat(row.label).isEqualTo("Teledeporte")
        assertThat(row.feeds.map(Feed::originalName))
            .containsExactly("Teledeporte FHD", "Teledeporte 4K").inOrder()
    }

    @Test
    fun `SD is allowed for music, where there is no better feed to prefer`() {
        val rows = LiveCuration.curate(feeds("Now 70s SD", "Now 80s SD", "Now 90s & 00s SD"))

        assertThat(rows.map(Channel::label))
            .containsExactly("Música 70", "Música 80", "Música 90").inOrder()
        assertThat(rows.map { row -> row.feeds.single().height }).containsExactly(576, 576, 576)
    }

    @Test
    fun `the chain is bounded, so a dead channel cannot be retried forever`() {
        val row = LiveCuration.curate(
            feeds(
                "Discovery FHD", "Discovery FHD (Opc2)", "Discovery FHD (Multiaudio)",
                "Discovery HDR-FHD", "Discovery HD", "Discovery HD (Opc2)",
                "Discovery HD (Multiaudio)", "Discovery HDR-HD"
            )
        ).single()

        assertThat(row.feeds).hasSize(6)
    }

    @Test
    fun `the row borrows the first logo and guide id it can find`() {
        val row = LiveCuration.curate(
            listOf(
                FeedNaming.describe(1, "La 1 FHD", null, null),
                FeedNaming.describe(2, "La 1 HD", "La1.es", "http://logos/la1.png")
            )
        ).single()

        assertThat(row.logoUrl).isEqualTo("http://logos/la1.png")
        assertThat(row.epgChannelId).isEqualTo("La1.es")
    }

    @Test
    fun `two rules never claim the same label`() {
        val labels = LiveCuration.blocks.flatMap { block -> block.entries.map { it.label } }

        assertThat(labels).containsNoDuplicates()
    }

    // ------------------------------------------------------- the language wins over the picture

    @Test
    fun `a Spanish feed is played ahead of a sharper foreign one`() {
        // Verbatim from the account: of DAZN 1 the supplier serves 1080p in Portuguese only.
        val row = LiveCuration.curate(feeds("PT | Dazn 1 FHD", "ES | Dazn 1 HD")).single()

        assertThat(row.feeds.first().originalName).isEqualTo("ES | Dazn 1 HD")
    }

    @Test
    fun `the foreign feed is demoted, never dropped`() {
        val row = LiveCuration.curate(feeds("PT | Dazn 1 FHD", "ES | Dazn 1 HD")).single()

        assertThat(row.feeds.map(Feed::originalName))
            .containsExactly("ES | Dazn 1 HD", "PT | Dazn 1 FHD").inOrder()
    }

    @Test
    fun `a row with nothing but foreign feeds still plays one`() {
        val row = LiveCuration.curate(feeds("PT | Dazn 3 FHD")).single()

        assertThat(row.label).isEqualTo("DAZN 3")
        assertThat(row.feeds.single().originalName).isEqualTo("PT | Dazn 3 FHD")
    }

    @Test
    fun `an untagged feed counts as local, since on this lineup only the foreign ones are tagged`() {
        val row = LiveCuration.curate(feeds("PT | Eurosport 1 FHD", "Eurosport 1 HD")).single()

        assertThat(row.feeds.first().originalName).isEqualTo("Eurosport 1 HD")
    }

    @Test
    fun `among feeds of one country the sharpest still wins`() {
        val row = LiveCuration.curate(feeds("ES | Dazn 2 HD", "ES | Dazn 2 FHD")).single()

        assertThat(row.feeds.first().originalName).isEqualTo("ES | Dazn 2 FHD")
    }

    @Test
    fun `Nat Geo Wild is its own row and does not swallow National Geographic`() {
        val rows = LiveCuration.curate(
            feeds(
                "National Geographic FHD",
                "National Geographic Wild FHD",
                "Nat Geo Wild HD"
            )
        )

        assertThat(rows.map(Channel::label))
            .containsExactly("National Geographic", "Nat Geo Wild").inOrder()
        // Las dos grafías del canal de naturaleza caen en la misma fila, que es lo que hace que un
        // solo alias baste.
        assertThat(rows.last().feeds.map(Feed::originalName))
            .containsExactly("National Geographic Wild FHD", "Nat Geo Wild HD")
    }

    @Test
    fun `À Punt is matched with its accent stripped`() {
        assertThat(labels("À Punt FHD")).containsExactly("À Punt")
    }
}
