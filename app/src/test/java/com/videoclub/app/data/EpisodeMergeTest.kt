package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The numbers below are The Bear as this account actually publishes it, which is the case that
 * showed the bug: `4K - The Bear (2022)` carries seasons 2, 3 and 5 and only five of season 2's ten
 * episodes, while `The Bear (2022)` carries all five seasons complete. Playing the best encode and
 * ignoring the rest lost two whole seasons.
 */
class EpisodeMergeTest {

    @Test
    fun `the union of two copies keeps every season either of them has`() {
        val uhd = season(2, 1..5, Quality.Uhd) + season(3, 1..10, Quality.Uhd) +
            season(5, 1..8, Quality.Uhd)
        val hd = season(1, 1..8, Quality.Hd) + season(2, 1..10, Quality.Hd) +
            season(3, 1..10, Quality.Hd) + season(4, 1..10, Quality.Hd) +
            season(5, 1..8, Quality.Hd)

        val merged = mergeEpisodes(listOf(uhd, hd))

        assertThat(merged.groupingBy(Episode::season).eachCount())
            .containsExactly(1, 8, 2, 10, 3, 10, 4, 10, 5, 8)
    }

    @Test
    fun `an episode both copies have is playable either way, best first`() {
        val merged = mergeEpisodes(
            listOf(season(2, 1..1, Quality.Uhd), season(2, 1..1, Quality.Hd))
        )

        assertThat(merged.single().sources.map(EpisodeSource::quality))
            .containsExactly(Quality.Uhd, Quality.Hd)
            .inOrder()
        assertThat(merged.single().bestSource?.quality).isEqualTo(Quality.Uhd)
    }

    @Test
    fun `an episode only the ordinary copy has plays from it`() {
        val merged = mergeEpisodes(
            listOf(season(2, 1..1, Quality.Uhd), season(1, 1..1, Quality.Hd))
        )
        val pilot = merged.first { it.season == 1 }

        assertThat(pilot.sourceFor(Quality.Uhd)?.quality).isEqualTo(Quality.Hd)
    }

    @Test
    fun `the key survives a change of copy so a half-watched episode stays one viewing`() {
        val uhd = season(2, 1..1, Quality.Uhd).single()
        val hd = season(2, 1..1, Quality.Hd).single()

        assertThat(uhd.key).isEqualTo(hd.key)
        assertThat(uhd.bestSource?.remoteId).isNotEqualTo(hd.bestSource?.remoteId)
    }

    @Test
    fun `the better copy supplies the description and the worse one fills the gaps`() {
        val sparse = Episode(
            season = 1,
            number = 1,
            title = "Piloto",
            sources = listOf(EpisodeSource(1, Quality.Uhd, "mkv"))
        )
        val full = sparse.copy(
            plot = "Carmy vuelve a Chicago.",
            durationSeconds = 1800,
            sources = listOf(EpisodeSource(2, Quality.Hd, "mkv"))
        )

        val merged = mergeEpisodes(listOf(listOf(sparse), listOf(full))).single()

        assertThat(merged.plot).isEqualTo("Carmy vuelve a Chicago.")
        assertThat(merged.durationSeconds).isEqualTo(1800)
    }

    @Test
    fun `a series with a single copy comes back untouched`() {
        val only = season(1, 1..3, Quality.Hd)

        assertThat(mergeEpisodes(listOf(only))).isEqualTo(only)
    }

    private fun season(number: Int, episodes: IntRange, quality: Quality): List<Episode> =
        episodes.map { index ->
            Episode(
                season = number,
                number = index,
                title = "Episodio $index",
                sources = listOf(
                    // Distinct ids per copy, as the supplier issues them.
                    EpisodeSource(
                        remoteId = quality.ordinal * 100_000 + number * 100 + index,
                        quality = quality,
                        container = "mkv"
                    )
                )
            )
        }
}
