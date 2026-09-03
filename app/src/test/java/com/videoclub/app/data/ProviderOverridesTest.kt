package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `simple` follows the same contract as every other field of the hosted document: absent means
 * "leave whatever is cached", never "switch it off". That is what makes the document safe to edit by
 * hand one line at a time without accidentally dragging a household back to the full video shop.
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

    // --- the household's own channels -----------------------------------------------------------

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
        // The supplier's are positive; these must never collide with one.
        assertThat(row.feeds.single().streamId).isLessThan(0)
    }

    // --- "tune to this channel" -----------------------------------------------------------------

    @Test
    fun `a tune order is read with its channel and its timestamp`() {
        val parsed = ProviderOverrides.parse(
            """{"poner": {"canal": "Betis TV", "cuando": 1788261207}}"""
        )

        assertThat(parsed!!.tune).isEqualTo(TuneOrder("Betis TV", 1788261207L))
    }

    /**
     * With no timestamp there is no order, and that is not fussiness: the stamp is what allows it
     * to be obeyed exactly once. An order without one would be carried out on every check of the
     * document, forever.
     */
    @Test
    fun `an order with no timestamp is not an order`() {
        assertThat(ProviderOverrides.parse("""{"poner": {"canal": "Betis TV"}}""")!!.tune).isNull()
        assertThat(ProviderOverrides.parse("""{"poner": {"cuando": 1788261207}}""")!!.tune).isNull()
        assertThat(ProviderOverrides.parse("""{"poner": {}}""")!!.tune).isNull()
    }

    /**
     * The errand is not written to the cache: if it were, a box starting tomorrow with no network
     * would find last night's order on disk and carry it out.
     */
    @Test
    fun `an order does not survive the cache`() {
        val overrides = ProviderOverrides(tune = TuneOrder("Betis TV", 1788261207L))

        assertThat(ProviderOverrides.parse(overrides.encode())!!.tune).isNull()
    }

    // --- a published release ----------------------------------------------------------------------

    @Test
    fun `a release is read with its version, url and hash`() {
        val parsed = ProviderOverrides.parse(
            """{"apk": {"version": 26090114, "url": "https://x/y.apk", "sha256": "abc"}}"""
        )

        assertThat(parsed!!.apk).isEqualTo(ApkRelease(26090114, "https://x/y.apk", "abc"))
    }

    @Test
    fun `a release with no hash is still a release`() {
        val parsed = ProviderOverrides.parse(
            """{"apk": {"version": 26090114, "url": "https://x/y.apk"}}"""
        )

        assertThat(parsed!!.apk).isEqualTo(ApkRelease(26090114, "https://x/y.apk", ""))
    }

    /**
     * With no version or no url there is nothing to compare against `BuildConfig.VERSION_CODE` or
     * nowhere to fetch from, so the whole object is dropped rather than half-trusted.
     */
    @Test
    fun `a release with no version or no url is not a release`() {
        assertThat(ProviderOverrides.parse("""{"apk": {"url": "https://x/y.apk"}}""")!!.apk).isNull()
        assertThat(ProviderOverrides.parse("""{"apk": {"version": 26090114}}""")!!.apk).isNull()
        assertThat(ProviderOverrides.parse("""{"apk": {"version": 0, "url": "https://x/y.apk"}}""")!!.apk)
            .isNull()
        assertThat(ProviderOverrides.parse("""{"apk": {}}""")!!.apk).isNull()
    }

    /**
     * A release is a fact about what is available right now, not a setting: storing it would mean a
     * box starting tomorrow with no network finding yesterday's "download this" still in its cache.
     */
    @Test
    fun `a release does not survive the cache`() {
        val overrides = ProviderOverrides(apk = ApkRelease(26090114, "https://x/y.apk", "abc"))

        assertThat(ProviderOverrides.parse(overrides.encode())!!.apk).isNull()
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
