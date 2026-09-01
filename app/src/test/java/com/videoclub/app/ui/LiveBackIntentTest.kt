package com.videoclub.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Back is the one control a viewer presses when they are lost, so it is the one worth pinning down
 * in a test: there must be no state the television section can be in from which Back does nothing.
 */
class LiveBackIntentTest {

    @Test
    fun `from the picture, back shows the channel list`() {
        assertThat(liveBackIntent(LiveLayer.None, hasChannels = true))
            .isEqualTo(LiveBack.ShowList)
    }

    @Test
    fun `from the channel list, back leaves for the videoclub`() {
        assertThat(liveBackIntent(LiveLayer.List, hasChannels = true))
            .isEqualTo(LiveBack.Leave)
    }

    @Test
    fun `two presses is the whole distance from the picture to the videoclub`() {
        val first = liveBackIntent(LiveLayer.None, hasChannels = true)
        assertThat(first).isEqualTo(LiveBack.ShowList)
        assertThat(liveBackIntent(LiveLayer.List, hasChannels = true)).isEqualTo(LiveBack.Leave)
    }

    @Test
    fun `with no channels there is no list to show, so back leaves instead of doing nothing`() {
        assertThat(liveBackIntent(LiveLayer.None, hasChannels = false))
            .isEqualTo(LiveBack.Leave)
    }

    @Test
    fun `the rebuild panel is undone before anything else`() {
        assertThat(liveBackIntent(LiveLayer.RefreshResult, hasChannels = true))
            .isEqualTo(LiveBack.DismissRefresh)
        assertThat(liveBackIntent(LiveLayer.RefreshResult, hasChannels = false))
            .isEqualTo(LiveBack.DismissRefresh)
    }

    @Test
    fun `every state answers back with something`() {
        LiveLayer.entries.forEach { layer ->
            listOf(true, false).forEach { hasChannels ->
                assertThat(liveBackIntent(layer, hasChannels)).isNotNull()
            }
        }
    }
}
