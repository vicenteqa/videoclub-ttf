package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The numbers are the ones the account actually returns. `Ben-Hur (1959)` is the case that started
 * this: three copies, and the plain one is a two-hour film filed under a three-and-three-quarter
 * hour title.
 */
class SourceAgreementTest {

    private val uhd = Source(remoteId = 4258110, quality = Quality.Uhd, container = "mkv")
    private val hd60 = Source(remoteId = 4251042, quality = Quality.Hd60, container = "mkv")
    private val hd = Source(remoteId = 4276510, quality = Quality.Hd, container = "mkv")

    @Test
    fun `drops the copy that is a different film`() {
        val kept = SourceAgreement.agreeing(
            listOf(uhd to 13348, hd60 to 13348, hd to 7399)
        )

        assertThat(kept).containsExactly(uhd, hd60).inOrder()
    }

    @Test
    fun `keeps encodes that differ by a few minutes`() {
        // Different credits and a frame rate conversion, not a different picture.
        val kept = SourceAgreement.agreeing(
            listOf(uhd to 13348, hd60 to 13100, hd to 13500)
        )

        assertThat(kept).containsExactly(uhd, hd60, hd).inOrder()
    }

    @Test
    fun `two copies that disagree decide nothing`() {
        // `Juego sucio (2025)`: 127 minutes and 100 minutes, and no way to tell which is the film.
        val kept = SourceAgreement.agreeing(listOf(uhd to 7620, hd to 6000))

        assertThat(kept).containsExactly(uhd, hd).inOrder()
    }

    @Test
    fun `a copy the supplier never probed is kept`() {
        val kept = SourceAgreement.agreeing(
            listOf(uhd to 13348, hd60 to 13348, hd to null)
        )

        assertThat(kept).containsExactly(uhd, hd60, hd).inOrder()
    }

    @Test
    fun `a copy the supplier never probed does not vote`() {
        // Two known durations that disagree, plus an unknown: still a tie, still no verdict.
        val kept = SourceAgreement.agreeing(
            listOf(uhd to 13348, hd60 to 7399, hd to null)
        )

        assertThat(kept).containsExactly(uhd, hd60, hd).inOrder()
    }

    @Test
    fun `a single running time is nothing to disagree with`() {
        assertThat(SourceAgreement.agreeing(listOf(uhd to 13348, hd to null)))
            .containsExactly(uhd, hd).inOrder()
        assertThat(SourceAgreement.agreeing(listOf(uhd to 13348)))
            .containsExactly(uhd)
    }

    @Test
    fun `zero is not a running time`() {
        // The supplier writes `0` for a title it never probed as often as it omits the field.
        assertThat(SourceAgreement.agreeing(listOf(uhd to 13348, hd60 to 13348, hd to 0)))
            .containsExactly(uhd, hd60, hd).inOrder()
    }

    @Test
    fun `two against two is not a majority`() {
        val sd = Source(remoteId = 1, quality = Quality.Sd, container = "mkv")
        val kept = SourceAgreement.agreeing(
            listOf(uhd to 13348, hd60 to 13348, hd to 7399, sd to 7399)
        )

        assertThat(kept).containsExactly(uhd, hd60, hd, sd).inOrder()
    }
}
