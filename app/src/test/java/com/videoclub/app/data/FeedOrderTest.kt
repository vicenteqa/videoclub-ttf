package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedOrderTest {

    @Test
    fun `a television plays the chain exactly as curation left it`() {
        val channel = channel(1080, 720, 1080)
        assertThat(channel.feedsFor(DeviceProfile.Tv)).isEqualTo(channel.feeds)
    }

    @Test
    fun `a handheld tries 720p first`() {
        val channel = channel(1080, 720, 1080)
        assertThat(channel.feedsFor(DeviceProfile.Handheld).map(Feed::height))
            .containsExactly(720, 1080, 1080)
            .inOrder()
    }

    /**
     * The point of a stable sort. Curation ranks the feeds behind the leader for good reasons — plain
     * before "Opc2" before HDR — and a device preference must not scramble them, only lift the 720p
     * ones to the front.
     */
    @Test
    fun `a handheld keeps the curated order within each group`() {
        val channel = channel(1080, 2160, 720, 720, 1080)
        assertThat(channel.feedsFor(DeviceProfile.Handheld).map(Feed::streamId))
            .containsExactly(3, 4, 1, 2, 5)
            .inOrder()
    }

    @Test
    fun `nothing is dropped, so a row with no 720p still has its whole chain`() {
        val channel = channel(1080, 1080, 2160)
        val ordered = channel.feedsFor(DeviceProfile.Handheld)
        assertThat(ordered).containsExactlyElementsIn(channel.feeds)
        assertThat(ordered).isEqualTo(channel.feeds)
    }

    @Test
    fun `an untagged height is not mistaken for the preferred one`() {
        val channel = channel(null, 720)
        assertThat(channel.feedsFor(DeviceProfile.Handheld).map(Feed::height))
            .containsExactly(720, null)
            .inOrder()
    }

    private fun channel(vararg heights: Int?) = Channel(
        label = "Test",
        logoUrl = null,
        epgChannelId = null,
        feeds = heights.mapIndexed { index, height ->
            Feed(
                streamId = index + 1,
                originalName = "Test ${height ?: "?"} #${index + 1}",
                canonicalName = "test",
                region = null,
                height = height,
                isHdr = false,
                epgChannelId = null,
                logoUrl = null
            )
        }
    )
}
