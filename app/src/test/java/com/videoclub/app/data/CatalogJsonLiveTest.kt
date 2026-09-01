package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

/**
 * The payloads here are trimmed copies of real answers from the account this app uses, captured in
 * August 2026. Field types included: `stream_id` is a number in one call and the timestamps are
 * quoted strings in the other, which is exactly the sort of thing a strict parser dies on.
 */
class CatalogJsonLiveTest {

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray())

    private fun decode(value: String): String =
        runCatching { String(Base64.getDecoder().decode(value)) }.getOrDefault("")

    @Test
    fun `a live listing keeps the name, the tags and the artwork`() {
        val feeds = CatalogJson.liveStreams(
            """
            [
              {
                "stream_id": 2275011,
                "name": "La 1 FHD",
                "epg_channel_id": "La 1 HD",
                "stream_icon": "http://logo/la1.png"
              }
            ]
            """.trimIndent()
        )

        assertThat(feeds).hasSize(1)
        val feed = feeds.single()
        assertThat(feed.streamId).isEqualTo(2275011)
        assertThat(feed.originalName).isEqualTo("La 1 FHD")
        assertThat(feed.canonicalName).isEqualTo("La 1")
        assertThat(feed.height).isEqualTo(1080)
        assertThat(feed.epgChannelId).isEqualTo("La 1 HD")
        assertThat(feed.logoUrl).isEqualTo("http://logo/la1.png")
    }

    @Test
    fun `a stream id sent as a quoted string is still a stream id`() {
        val feeds = CatalogJson.liveStreams("""[{"stream_id": "42", "name": "La 2 HD"}]""")

        assertThat(feeds.single().streamId).isEqualTo(42)
    }

    @Test
    fun `category separators are not channels`() {
        val feeds = CatalogJson.liveStreams(
            """
            [
              {"stream_id": 1, "name": "#### DEPORTES ####"},
              {"stream_id": 2, "name": "Teledeporte FHD"}
            ]
            """.trimIndent()
        )

        assertThat(feeds.map(Feed::originalName)).containsExactly("Teledeporte FHD")
    }

    @Test
    fun `a body that is not a lineup reads as no channels rather than throwing`() {
        assertThat(CatalogJson.liveStreams("")).isEmpty()
        assertThat(CatalogJson.liveStreams("""{"user_info": {}}""")).isEmpty()
    }

    @Test
    fun `guide entries come back decoded, in seconds turned into millis, and in order`() {
        val programmes = CatalogJson.shortEpg(
            """
            {"epg_listings": [
              {
                "title": "${encode("Malas lenguas T2 E241")}",
                "start_timestamp": "1787852100",
                "stop_timestamp": "1787855100"
              },
              {
                "title": "${encode("La Promesa T1 E894")}",
                "start_timestamp": 1787848500,
                "stop_timestamp": 1787852100
              }
            ]}
            """.trimIndent(),
            ::decode
        )

        assertThat(programmes.map(Programme::title))
            .containsExactly("La Promesa T1 E894", "Malas lenguas T2 E241").inOrder()
        assertThat(programmes.first().startMillis).isEqualTo(1787848500_000L)
        assertThat(programmes.first().endMillis).isEqualTo(1787852100_000L)
    }

    @Test
    fun `an entry the decoder cannot read is dropped, not fatal`() {
        val programmes = CatalogJson.shortEpg(
            """
            {"epg_listings": [
              {"title": "!!not base64!!", "start_timestamp": 1, "stop_timestamp": 2},
              {"title": "${encode("Telediario")}", "start_timestamp": 3, "stop_timestamp": 4}
            ]}
            """.trimIndent(),
            ::decode
        )

        assertThat(programmes.map(Programme::title)).containsExactly("Telediario")
    }

    @Test
    fun `a channel with no guide reads as no guide`() {
        assertThat(CatalogJson.shortEpg("""{"epg_listings": []}""", ::decode)).isEmpty()
        assertThat(CatalogJson.shortEpg("", ::decode)).isEmpty()
    }

    // --- the account's connections -------------------------------------------------------------

    /** Exactly as this supplier sends it: both numbers, quoted. */
    private fun account(active: String, allowed: String) =
        """{"user_info": {"active_cons": "$active", "max_connections": "$allowed"}}"""

    @Test
    fun `the account is full when it has as many connections open as it allows`() {
        assertThat(CatalogJson.accountIsFull(account("1", "1"))).isTrue()
        assertThat(CatalogJson.accountIsFull(account("2", "2"))).isTrue()
    }

    @Test
    fun `the account is not full while it has room`() {
        assertThat(CatalogJson.accountIsFull(account("0", "1"))).isFalse()
        assertThat(CatalogJson.accountIsFull(account("1", "2"))).isFalse()
    }

    @Test
    fun `numbers sent unquoted are read the same`() {
        assertThat(
            CatalogJson.accountIsFull("""{"user_info": {"active_cons": 1, "max_connections": 1}}""")
        ).isTrue()
    }

    /**
     * The three "not known" cases, none of which is a "no": with the figure missing, with a limit
     * of zero — which on some suppliers means "no limit" — or with an answer that is not the
     * expected document, the app must not accuse anybody of using the account.
     */
    @Test
    fun `an answer that does not say is not a no`() {
        assertThat(CatalogJson.accountIsFull("""{"user_info": {"active_cons": "1"}}""")).isNull()
        assertThat(CatalogJson.accountIsFull(account("0", "0"))).isNull()
        assertThat(CatalogJson.accountIsFull("""{"user_info": {}}""")).isNull()
        assertThat(CatalogJson.accountIsFull("<html>404</html>")).isNull()
        assertThat(CatalogJson.accountIsFull("")).isNull()
    }
}
