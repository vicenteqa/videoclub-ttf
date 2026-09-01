package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelJsonTest {

    private val list = listOf(
        Channel(
            label = "La 1",
            logoUrl = "http://logos/la1.png",
            epgChannelId = "La1.es",
            feeds = listOf(
                Feed(1, "La 1 FHD", "La 1", null, 1080, false, "La1.es", "http://logos/la1.png"),
                Feed(2, "La 1 HD", "La 1", null, 720, false, null, null)
            )
        ),
        Channel(
            label = "Música 80",
            logoUrl = null,
            epgChannelId = null,
            feeds = listOf(Feed(3, "Now 80s SD", "Now 80s", null, 576, false, null, null))
        ),
        // The one row on this account whose feeds do not all come from the same country.
        Channel(
            label = "DAZN 1",
            logoUrl = null,
            epgChannelId = null,
            feeds = listOf(
                Feed(4, "ES | Dazn 1 HD", "Dazn 1", "ES", 720, false, null, null),
                Feed(5, "PT | Dazn 1 FHD", "Dazn 1", "PT", 1080, false, null, null)
            )
        )
    )

    @Test
    fun `a saved list reads back exactly as it was written`() {
        assertThat(ChannelJson.decode(ChannelJson.encode(list))).isEqualTo(list)
    }

    @Test
    fun `an unset height stays unset instead of becoming zero`() {
        val feed = Feed(9, "Canal Sur", "Canal Sur", null, null, false, null, null)
        val row = listOf(Channel("Canal Sur", null, null, listOf(feed)))

        assertThat(ChannelJson.decode(ChannelJson.encode(row)).single().feeds.single().height).isNull()
    }

    @Test
    fun `a cache written by another format version is ignored, not misread`() {
        val version = ChannelJson.FORMAT_VERSION
        val stale = ChannelJson.encode(list)
            .replace("\"v\":$version", "\"v\":${version + 1}")

        assertThat(ChannelJson.decode(stale)).isEmpty()
    }

    @Test
    fun `a row with no feeds is dropped, because there is nothing to play`() {
        val text = """{"v":${ChannelJson.FORMAT_VERSION},"channels":[{"label":"La 1","feeds":[]},{"label":"La 2","feeds":[{"id":4,"name":"La 2 FHD","canon":"La 2","h":1080,"hdr":false}]}]}"""

        assertThat(ChannelJson.decode(text).map(Channel::label)).containsExactly("La 2")
    }

    @Test
    fun `a feed saved before regions existed reads back with none, rather than not at all`() {
        val text = """{"v":${ChannelJson.FORMAT_VERSION},"channels":[{"label":"La 2","feeds":[{"id":4,"name":"La 2 FHD","canon":"La 2","h":1080,"hdr":false}]}]}"""

        assertThat(ChannelJson.decode(text).single().feeds.single().region).isNull()
    }

    @Test
    fun `truncated json is a thrown parse error, which the store turns into an empty cache`() {
        val truncated = ChannelJson.encode(list).dropLast(20)

        assertThat(runCatching { ChannelJson.decode(truncated) }.isFailure).isTrue()
    }
}
