package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `simple` sigue el mismo contrato que el resto de campos del documento alojado: ausente significa
 * «deja lo que haya en caché», nunca «apágalo». Es lo que permite escribir el documento a mano con
 * una sola línea sin tirar del resto de la casa al videoclub completo por accidente.
 */
class ProviderOverridesTest {

    @Test
    fun `simple is absent from a document that does not mention it`() {
        val parsed = ProviderOverrides.parse("""{"password": "nueva"}""")

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.simple).isNull()
    }

    @Test
    fun `simple true is read as true`() {
        val parsed = ProviderOverrides.parse("""{"simple": true}""")

        assertThat(parsed!!.simple).isTrue()
    }

    @Test
    fun `simple false is read as false, not as absent`() {
        val parsed = ProviderOverrides.parse("""{"simple": false}""")

        assertThat(parsed!!.simple).isFalse()
    }

    @Test
    fun `absent simple leaves the cached value alone`() {
        val cached = ProviderConfig.empty().copy(simple = true)
        val merged = cached.mergedWith(ProviderOverrides.parse("""{"password": "nueva"}""")!!)

        assertThat(merged.simple).isTrue()
    }

    @Test
    fun `an explicit false in the document turns simple mode off`() {
        val cached = ProviderConfig.empty().copy(simple = true)
        val merged = cached.mergedWith(ProviderOverrides.parse("""{"simple": false}""")!!)

        assertThat(merged.simple).isFalse()
    }

    @Test
    fun `simple alone is not an empty override`() {
        val overrides = ProviderOverrides.parse("""{"simple": true}""")!!

        assertThat(overrides.isEmpty).isFalse()
    }

    @Test
    fun `round-trips through encode`() {
        val overrides = ProviderOverrides(simple = true)

        val reparsed = ProviderOverrides.parse(overrides.encode())

        assertThat(reparsed!!.simple).isTrue()
    }

    // --- canales de la casa -------------------------------------------------------------------

    @Test
    fun `a document that does not mention channels leaves the cached ones alone`() {
        val cached = ProviderConfig.empty()
            .copy(extraChannels = listOf(ExtraChannel(name = "Penedès TV", url = "https://x/y.m3u8")))
        val merged = cached.mergedWith(ProviderOverrides.parse("""{"password": "nueva"}""")!!)

        assertThat(merged.extraChannels).hasSize(1)
    }

    @Test
    fun `an empty list removes the channels the house had`() {
        val cached = ProviderConfig.empty()
            .copy(extraChannels = listOf(ExtraChannel(name = "Penedès TV", url = "https://x/y.m3u8")))
        val merged = cached.mergedWith(ProviderOverrides.parse("""{"canales": []}""")!!)

        assertThat(merged.extraChannels).isEmpty()
    }

    @Test
    fun `a channel is read with its name, url, logo and user agent`() {
        val parsed = ProviderOverrides.parse(
            """
            {"canales": [
              {"nombre": "Penedès TV", "url": "https://cdn/live.m3u8",
               "logo": "https://cdn/logo.png", "userAgent": "Mozilla/5.0"}
            ]}
            """.trimIndent()
        )

        val canal = parsed!!.extraChannels!!.single()
        assertThat(canal.name).isEqualTo("Penedès TV")
        assertThat(canal.url).isEqualTo("https://cdn/live.m3u8")
        assertThat(canal.logoUrl).isEqualTo("https://cdn/logo.png")
        assertThat(canal.userAgent).isEqualTo("Mozilla/5.0")
    }

    @Test
    fun `a channel with no url is dropped instead of refusing the whole document`() {
        val parsed = ProviderOverrides.parse(
            """{"password": "nueva", "canales": [{"nombre": "Rota"}]}"""
        )

        assertThat(parsed!!.password).isEqualTo("nueva")
        assertThat(parsed.extraChannels).isEmpty()
    }

    @Test
    fun `a channel becomes a row whose feed carries the url and the user agent`() {
        val canal = ExtraChannel(
            name = "Penedès TV",
            url = "https://cdn/live.m3u8",
            userAgent = "Mozilla/5.0"
        )

        val row = canal.toChannel(position = 0)

        assertThat(row.label).isEqualTo("Penedès TV")
        assertThat(row.feeds.single().url).isEqualTo("https://cdn/live.m3u8")
        assertThat(row.feeds.single().userAgent).isEqualTo("Mozilla/5.0")
        // Los del proveedor son positivos; éstos nunca deben chocar con uno.
        assertThat(row.feeds.single().streamId).isLessThan(0)
    }

    // --- «pon este canal» --------------------------------------------------------------------

    @Test
    fun `a tune order is read with its channel and its timestamp`() {
        val parsed = ProviderOverrides.parse(
            """{"poner": {"canal": "Betis TV", "cuando": 1788261207}}"""
        )

        assertThat(parsed!!.tune).isEqualTo(TuneOrder("Betis TV", 1788261207L))
    }

    /**
     * Sin fecha no hay orden, y no es un capricho: la marca es lo que permite obedecerla una sola
     * vez. Una orden sin ella se cumpliría en cada consulta del documento, para siempre.
     */
    @Test
    fun `an order with no timestamp is not an order`() {
        assertThat(ProviderOverrides.parse("""{"poner": {"canal": "Betis TV"}}""")!!.tune).isNull()
        assertThat(ProviderOverrides.parse("""{"poner": {"cuando": 1788261207}}""")!!.tune).isNull()
        assertThat(ProviderOverrides.parse("""{"poner": {}}""")!!.tune).isNull()
    }

    /**
     * El recado no se guarda en la caché: si se guardara, una caja que arrancara mañana sin red se
     * encontraría en disco la orden de anoche y la cumpliría.
     */
    @Test
    fun `an order does not survive the cache`() {
        val overrides = ProviderOverrides(tune = TuneOrder("Betis TV", 1788261207L))

        assertThat(ProviderOverrides.parse(overrides.encode())!!.tune).isNull()
    }

    @Test
    fun `channels round-trip through encode`() {
        val overrides = ProviderOverrides(
            extraChannels = listOf(
                ExtraChannel(name = "Penedès TV", url = "https://cdn/live.m3u8", userAgent = "UA")
            )
        )

        val reparsed = ProviderOverrides.parse(overrides.encode())

        assertThat(reparsed!!.extraChannels!!.single().url).isEqualTo("https://cdn/live.m3u8")
        assertThat(reparsed.extraChannels!!.single().userAgent).isEqualTo("UA")
    }
}
