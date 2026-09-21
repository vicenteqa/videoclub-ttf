package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Lines exactly as `catalogo-maestro.py` writes them: `json.dumps(..., separators=(",", ":"))`. */
class MirrorLinesTest {

    @Test
    fun `cuts the items out of a listing line`() {
        val line = """{"tipo":"listado","kind":"vod","category_id":"413","items":[{"num":1,"name":"4K - Gladiator (2000)","stream_id":"42"}]}"""

        val (kind, category, items) = MirrorLines.listing(line)!!

        assertThat(kind).isEqualTo(Kind.Movie)
        assertThat(category).isEqualTo("413")
        assertThat(CatalogJson.listings(Kind.Movie, items).single().remoteId).isEqualTo(42)
    }

    @Test
    fun `reads series lines and empty listings`() {
        val (kind, category, items) =
            MirrorLines.listing("""{"tipo":"listado","kind":"series","category_id":"9","items":[]}""")!!

        assertThat(kind).isEqualTo(Kind.Series)
        assertThat(category).isEqualTo("9")
        assertThat(items).isEqualTo("[]")
    }

    @Test
    fun `leaves any other line to the parser`() {
        assertThat(MirrorLines.listing("""{"tipo":"meta","generado_en":1,"version":2}""")).isNull()
        assertThat(MirrorLines.listing("""{"tipo":"categorias","kind":"vod","items":[]}""")).isNull()
        // Same fields, another order: not the shape this cuts, so it goes to the JSON parser.
        assertThat(MirrorLines.listing("""{"kind":"vod","tipo":"listado","category_id":"1","items":[]}""")).isNull()
        assertThat(MirrorLines.listing("""{"tipo":"listado","kind":"vod","category_id":"1","items":{}}""")).isNull()
    }

    @Test
    fun `packs and unpacks without losing a character`() {
        val text = """[{"name":"Él, Ñandú y el camión — 4K ★","stream_id":"1"}]""".repeat(500)

        val packed = MirrorLines.pack(text)

        assertThat(packed.size).isLessThan(text.length / 4)
        assertThat(MirrorLines.unpack(packed)).isEqualTo(text)
    }
}
