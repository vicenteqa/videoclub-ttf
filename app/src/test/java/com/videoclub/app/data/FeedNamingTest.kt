package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedNamingTest {

    @Test
    fun `quality and codec tags are not part of the name`() {
        assertThat(FeedNaming.canonicalName("La 1 FHD H265")).isEqualTo("La 1")
        assertThat(FeedNaming.canonicalName("Antena 3 1080p")).isEqualTo("Antena 3")
        assertThat(FeedNaming.canonicalName("Discovery HD")).isEqualTo("Discovery")
    }

    @Test
    fun `the family number is never mistaken for a resolution`() {
        assertThat(FeedNaming.canonicalName("LaLigaTV 2 FHD")).isEqualTo("LaLigaTV 2")
        assertThat(FeedNaming.canonicalName("DAZN 4 FHD")).isEqualTo("DAZN 4")
    }

    @Test
    fun `a leading region code is dropped`() {
        assertThat(FeedNaming.canonicalName("ES: Telecinco FHD")).isEqualTo("Telecinco")
        assertThat(FeedNaming.canonicalName("ES - Cuatro HD")).isEqualTo("Cuatro")
    }

    @Test
    fun `the region the name was dropped from is kept as a fact of its own`() {
        assertThat(FeedNaming.region("ES | Dazn 1 HD")).isEqualTo("ES")
        assertThat(FeedNaming.region("PT | Dazn 1 FHD")).isEqualTo("PT")
        assertThat(FeedNaming.region("DE - Dazn 1 Bar FHD")).isEqualTo("DE")
        assertThat(FeedNaming.region("es: telecinco fhd")).isEqualTo("ES")
    }

    @Test
    fun `a name with no country in front of it has no region`() {
        assertThat(FeedNaming.region("La 1 FHD")).isNull()
        assertThat(FeedNaming.region("Movistar Plus+ FHD")).isNull()
        // Two letters, but nothing separating them from the rest: not a prefix.
        assertThat(FeedNaming.region("La Sexta HD")).isNull()
    }

    @Test
    fun `bracketed decoration is dropped from the name but the original keeps it`() {
        val feed = FeedNaming.describe(1, "LaLigaTV 2 FHD (Opc 2)", null, null)

        assertThat(feed.canonicalName).isEqualTo("LaLigaTV 2")
        assertThat(feed.originalName).isEqualTo("LaLigaTV 2 FHD (Opc 2)")
    }

    @Test
    fun `declared height reads both the tag and the bare number`() {
        assertThat(FeedNaming.declaredHeight("La 1 FHD")).isEqualTo(1080)
        assertThat(FeedNaming.declaredHeight("La 1 1080p")).isEqualTo(1080)
        assertThat(FeedNaming.declaredHeight("La 1 HD")).isEqualTo(720)
        assertThat(FeedNaming.declaredHeight("La 1 4K")).isEqualTo(2160)
        assertThat(FeedNaming.declaredHeight("Música 80 SD")).isEqualTo(576)
    }

    @Test
    fun `full hd wins over hd, which is the substring of it`() {
        assertThat(FeedNaming.declaredHeight("Cuatro FULL HD")).isEqualTo(1080)
        assertThat(FeedNaming.declaredHeight("Cuatro FullHD")).isEqualTo(1080)
    }

    @Test
    fun `an untagged feed declares no height`() {
        assertThat(FeedNaming.declaredHeight("Canal Sur")).isNull()
    }

    @Test
    fun `hdr is recognised however it is written`() {
        assertThat(FeedNaming.isHdr("La 1 4K HDR")).isTrue()
        assertThat(FeedNaming.isHdr("La 1 HDR10")).isTrue()
        assertThat(FeedNaming.isHdr("La 1 Dolby Vision")).isTrue()
        assertThat(FeedNaming.isHdr("La 1 FHD")).isFalse()
    }
}
