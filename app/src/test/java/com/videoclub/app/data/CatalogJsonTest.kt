package com.videoclub.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The payload shapes below are trimmed copies of what this account actually returns. The awkward
 * ones — a `video` block that is an array beginning with the embedded cover art, an `episodes`
 * object keyed by season number — are the reason this parser exists at all.
 */
class CatalogJsonTest {

    @Test
    fun `reads a film listing whose numbers arrive as strings`() {
        val listings = CatalogJson.listings(
            Kind.Movie,
            """
            [{
              "num": 1, "name": "4K - Gladiator (2000)", "stream_type": "movie",
              "stream_id": "4258835", "stream_icon": "https://image.tmdb.org/t/p/w600/g.jpg",
              "rating": "8.2", "added": "1712345678", "container_extension": "mkv",
              "category_id": "413"
            }]
            """
        )

        assertThat(listings).hasSize(1)
        with(listings.single()) {
            assertThat(remoteId).isEqualTo(4258835)
            assertThat(rawName).isEqualTo("4K - Gladiator (2000)")
            assertThat(rating).isEqualTo(8.2)
            assertThat(addedSeconds).isEqualTo(1712345678L)
            assertThat(container).isEqualTo("mkv")
        }
    }

    @Test
    fun `a rating of zero is no rating`() {
        val listing = CatalogJson.listings(
            Kind.Movie,
            """[{"name": "Sin nota (2001)", "stream_id": 1, "rating": "0"}]"""
        ).single()

        assertThat(listing.rating).isNull()
    }

    @Test
    fun `a listing with no container falls back to the one 92 percent of them use`() {
        val listing = CatalogJson.listings(
            Kind.Movie,
            """[{"name": "Sin extensión (2001)", "stream_id": 1}]"""
        ).single()

        assertThat(listing.container).isEqualTo("mkv")
    }

    @Test
    fun `a row with no stream id is dropped rather than taking the category down with it`() {
        val listings = CatalogJson.listings(
            Kind.Movie,
            """[{"name": "Rota"}, {"name": "Buena (2001)", "stream_id": 7}]"""
        )

        assertThat(listings.map(Listing::remoteId)).containsExactly(7)
    }

    @Test
    fun `a series listing brings its metadata along for free`() {
        val listing = CatalogJson.listings(
            Kind.Series,
            """
            [{
              "num": 1, "name": "Fargo (2014)", "series_id": 812,
              "cover": "https://image.tmdb.org/t/p/w600/f.jpg",
              "plot": "Un vendedor de seguros.", "cast": "Billy Bob Thornton",
              "director": "", "genre": "Drama, Crimen", "releaseDate": "2014-04-15",
              "last_modified": "1700000000", "rating": "8.3",
              "backdrop_path": ["https://image.tmdb.org/t/p/w1280/b.jpg"],
              "youtube_trailer": "abc123"
            }]
            """
        ).single()

        assertThat(listing.remoteId).isEqualTo(812)
        assertThat(listing.addedSeconds).isEqualTo(1700000000L)
        assertThat(listing.container).isEmpty()
        with(requireNotNull(listing.detail)) {
            assertThat(plot).isEqualTo("Un vendedor de seguros.")
            assertThat(genre).isEqualTo("Drama, Crimen")
            assertThat(backdropUrl).isEqualTo("https://image.tmdb.org/t/p/w1280/b.jpg")
            // The supplier writes an empty string where there is no director.
            assertThat(director).isNull()
        }
    }

    @Test
    fun `the video codec is the film, not the embedded cover art`() {
        val detail = requireNotNull(
            CatalogJson.movieDetail(
                """
                {"info": {
                  "plot": "Una secuela.", "genre": "Ciencia ficción",
                  "video": [
                    {"index": 0, "codec_name": "png", "codec_type": "video", "height": 900},
                    {"index": 1, "codec_name": "hevc", "codec_type": "video", "height": 2160}
                  ],
                  "audio": {"index": 2, "codec_name": "eac3"},
                  "bitrate": 26100000, "tmdb_id": "335984",
                  "backdrop_path": ["https://image.tmdb.org/t/p/w1280/b.jpg"]
                }}
                """
            )
        )

        assertThat(detail.videoCodec).isEqualTo("hevc")
        assertThat(detail.videoHeight).isEqualTo(2160)
        assertThat(detail.audioCodec).isEqualTo("eac3")
        assertThat(detail.techLine).isEqualTo("HEVC 2160p  ·  EAC3  ·  26 Mbps")
    }

    @Test
    fun `a detail the supplier never probed reports nothing rather than nonsense`() {
        val detail = requireNotNull(CatalogJson.movieDetail("""{"info": {"plot": "Algo."}}"""))

        assertThat(detail.techLine).isNull()
        assertThat(detail.bitrateBps).isNull()
    }

    @Test
    fun `reads episodes out of an object keyed by season`() {
        val series = requireNotNull(
            CatalogJson.seriesDetail(
                """
                {
                  "info": {"plot": "Sinopsis.", "genre": "Drama"},
                  "episodes": {
                    "2": [{"id": "22", "episode_num": "1", "season": "2", "title": "Vuelta",
                           "container_extension": "mkv",
                           "info": {"plot": "Empieza.", "duration_secs": 3300}}],
                    "1": [{"id": "11", "episode_num": 1, "season": 1, "title": "Piloto",
                           "container_extension": "mkv", "info": {"duration_secs": 3600}},
                          {"id": "12", "episode_num": 2, "season": 1, "title": "Segundo",
                           "container_extension": "mkv", "info": {}}]
                  }
                }
                """
            )
        )

        assertThat(series.detail.plot).isEqualTo("Sinopsis.")
        assertThat(series.episodes.map { "S${it.season}E${it.number}" })
            .containsExactly("S1E1", "S1E2", "S2E1")
            .inOrder()
        assertThat(series.episodes.first().durationSeconds).isEqualTo(3600)
    }

    @Test
    fun `reads episodes out of an array of seasons too`() {
        val series = requireNotNull(
            CatalogJson.seriesDetail(
                """{"info": {}, "episodes": [[{"id": 5, "episode_num": 1, "title": "Uno"}]]}"""
            )
        )

        val source = requireNotNull(series.episodes.single().bestSource)
        assertThat(source.remoteId).isEqualTo(5)
        assertThat(source.container).isEqualTo("mkv")
    }

    @Test
    fun `reads categories and skips the nameless ones`() {
        val categories = CatalogJson.categories(
            """
            [{"category_id": "413", "category_name": "ULTIMOS ESTRENOS HD", "parent_id": 0},
             {"category_id": "999", "category_name": "  "}]
            """
        )

        assertThat(categories).containsExactly(RemoteCategory("413", "ULTIMOS ESTRENOS HD"))
    }

    @Test
    fun `a body that is not JSON at all yields nothing instead of throwing`() {
        assertThat(CatalogJson.categories("<html>502 Bad Gateway</html>")).isEmpty()
        assertThat(CatalogJson.listings(Kind.Movie, "")).isEmpty()
        assertThat(CatalogJson.movieDetail("nope")).isNull()
    }
}
