package com.videoclub.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One catalogue row exactly as the supplier published it, before duplicates are folded together. */
data class Listing(
    val remoteId: Int,
    val rawName: String,
    val posterUrl: String?,
    val rating: Double?,
    /** Epoch seconds. `added` for films, `last_modified` for series. */
    val addedSeconds: Long,
    /** Empty for series: an episode carries its own container, the series does not have one. */
    val container: String,
    /**
     * Series listings arrive with plot, cast, genre and backdrops already filled in; film listings
     * carry none of that and need a second call per title. Free metadata, taken where it is offered.
     */
    val detail: TitleDetail? = null
)

/** A supplier category, before it is given a local row id. */
data class RemoteCategory(val remoteId: String, val name: String)

/** A series' own metadata plus its episodes, from a single `get_series_info` call. */
data class SeriesDetail(val detail: TitleDetail, val episodes: List<Episode>)

/**
 * Reading the supplier's JSON, and nothing else.
 *
 * Split out from the HTTP client so that every one of these can be tested against a captured
 * payload on the JVM. The catalogue is the part of this app most likely to be malformed in some new
 * way next month, so it is the part that has to be cheap to write a test for.
 */
object CatalogJson {

    fun categories(body: String): List<RemoteCategory> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.textOrNull("category_id") ?: continue
                val name = entry.optString("category_name").trim()
                if (name.isEmpty()) continue
                add(RemoteCategory(id, name))
            }
        }
    }

    fun listings(kind: Kind, body: String): List<Listing> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                add(
                    when (kind) {
                        Kind.Movie -> movieListing(entry)
                        Kind.Series -> seriesListing(entry)
                    } ?: continue
                )
            }
        }
    }

    fun movieDetail(body: String): TitleDetail? {
        val info = runCatching { JSONObject(body).optJSONObject("info") }.getOrNull() ?: return null
        val video = info.mediaStream("video")
        val audio = info.mediaStream("audio")
        return TitleDetail(
            plot = info.textOrNull("plot") ?: info.textOrNull("description"),
            genre = info.textOrNull("genre"),
            cast = info.textOrNull("cast") ?: info.textOrNull("actors"),
            director = info.textOrNull("director"),
            releaseDate = info.textOrNull("releasedate"),
            durationSeconds = info.intOrNull("duration_secs")?.takeIf { it > 0 },
            tmdbId = info.textOrNull("tmdb_id"),
            backdropUrl = info.firstUrlOrNull("backdrop_path"),
            trailer = info.textOrNull("youtube_trailer"),
            videoCodec = video?.textOrNull("codec_name"),
            videoHeight = video?.intOrNull("height")?.takeIf { it > 0 },
            audioCodec = audio?.textOrNull("codec_name"),
            bitrateBps = info.longOrNull("bitrate")?.takeIf { it > 0 }
        )
    }

    /**
     * @param quality which copy of the series this response came from. `get_series_info` does not
     *   say — the encode is a property of the listing, and the caller is the only one that knows it.
     */
    fun seriesDetail(body: String, quality: Quality = Quality.Hd): SeriesDetail? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val info = root.optJSONObject("info") ?: JSONObject()
        val detail = TitleDetail(
            plot = info.textOrNull("plot"),
            genre = info.textOrNull("genre"),
            cast = info.textOrNull("cast"),
            director = info.textOrNull("director"),
            releaseDate = info.textOrNull("releaseDate") ?: info.textOrNull("releasedate"),
            durationSeconds = info.intOrNull("episode_run_time")?.takeIf { it > 0 }?.times(60),
            tmdbId = info.textOrNull("tmdb_id"),
            backdropUrl = info.firstUrlOrNull("backdrop_path"),
            trailer = info.textOrNull("youtube_trailer")
        )
        return SeriesDetail(detail, episodes(root.opt("episodes"), quality))
    }

    // -------------------------------------------------------------------------------------- rows

    private fun movieListing(entry: JSONObject): Listing? {
        val id = entry.intOrNull("stream_id") ?: return null
        val name = entry.optString("name").trim().ifEmpty { return null }
        return Listing(
            remoteId = id,
            rawName = name,
            posterUrl = entry.textOrNull("stream_icon"),
            rating = entry.doubleOrNull("rating")?.takeIf { it > 0 },
            addedSeconds = entry.longOrNull("added") ?: 0L,
            container = entry.textOrNull("container_extension") ?: DEFAULT_CONTAINER
        )
    }

    private fun seriesListing(entry: JSONObject): Listing? {
        val id = entry.intOrNull("series_id") ?: return null
        val name = entry.optString("name").trim().ifEmpty { return null }
        return Listing(
            remoteId = id,
            rawName = name,
            posterUrl = entry.textOrNull("cover"),
            rating = entry.doubleOrNull("rating")?.takeIf { it > 0 },
            addedSeconds = entry.longOrNull("last_modified") ?: 0L,
            container = "",
            detail = TitleDetail(
                plot = entry.textOrNull("plot"),
                genre = entry.textOrNull("genre"),
                cast = entry.textOrNull("cast"),
                director = entry.textOrNull("director"),
                releaseDate = entry.textOrNull("releaseDate"),
                backdropUrl = entry.firstUrlOrNull("backdrop_path"),
                trailer = entry.textOrNull("youtube_trailer")
            )
        )
    }

    /**
     * `episodes` is an object keyed by season number — `{"1": [...], "2": [...]}` — except on the
     * panels where it is a plain array of season arrays. Both shapes appear in the wild.
     */
    private fun episodes(raw: Any?, quality: Quality): List<Episode> = buildList {
        when (raw) {
            is JSONObject -> raw.keys().forEach { key ->
                addAll(seasonEpisodes(raw.optJSONArray(key), key.toIntOrNull() ?: 0, quality))
            }
            is JSONArray -> for (index in 0 until raw.length()) {
                addAll(seasonEpisodes(raw.optJSONArray(index), index, quality))
            }
        }
    }.sortedWith(compareBy(Episode::season, Episode::number))

    private fun seasonEpisodes(
        array: JSONArray?,
        fallbackSeason: Int,
        quality: Quality
    ): List<Episode> {
        if (array == null) return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.intOrNull("id") ?: continue
                val info = entry.optJSONObject("info") ?: JSONObject()
                val number = entry.intOrNull("episode_num") ?: (index + 1)
                add(
                    Episode(
                        season = entry.intOrNull("season") ?: fallbackSeason,
                        number = number,
                        title = entry.textOrNull("title") ?: "Episodio $number",
                        plot = info.textOrNull("plot"),
                        stillUrl = info.textOrNull("movie_image"),
                        durationSeconds = info.intOrNull("duration_secs")?.takeIf { it > 0 },
                        sources = listOf(
                            EpisodeSource(
                                remoteId = id,
                                quality = quality,
                                container = entry.textOrNull("container_extension")
                                    ?: DEFAULT_CONTAINER
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * Whether the account already has all of its connections in use, according to itself.
     *
     * Returns null when the document does not answer the question: a supplier that does not publish
     * `max_connections`, or publishes it as zero — which on some means "no limit" — is not saying
     * the account is full, it is saying we do not know.
     *
     * Both numbers arrive **as strings** from this supplier (`"active_cons": "0"`), so they are read
     * with `optString` and converted by hand: `optInt` on a missing field would give 0, which here
     * would be mistaken for "no connections open".
     */
    fun accountIsFull(body: String): Boolean? {
        val info = runCatching { JSONObject(body).optJSONObject("user_info") }.getOrNull() ?: return null
        val active = info.optString("active_cons").toIntOrNull() ?: return null
        val allowed = info.optString("max_connections").toIntOrNull() ?: return null
        if (allowed <= 0) return null
        return active >= allowed
    }

    // ------------------------------------------------------------------------- live television

    /**
     * The whole live lineup, reduced to what [LiveCuration] reads.
     *
     * ~2000 streams on this account, against 33,000 catalogue titles fetched a category at a time.
     * The lineup is small enough to ask for whole, which is why there is nothing here resembling the
     * paging the videoclub needs.
     */
    fun liveStreams(body: String): List<Feed> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val streamId = entry.intOrNull("stream_id") ?: continue
                val name = entry.optString("name").trim()
                if (name.isEmpty() || isCategoryHeader(name)) continue

                add(
                    FeedNaming.describe(
                        streamId = streamId,
                        rawName = name,
                        epgChannelId = entry.textOrNull("epg_channel_id"),
                        logoUrl = entry.textOrNull("stream_icon")
                    )
                )
            }
        }
    }

    /**
     * The next few programmes on one channel, sorted.
     *
     * [decode] is base64, handed in rather than called: the platform's `android.util.Base64` is a
     * stub that throws in a JVM unit test, and this is the one piece of supplier JSON whose fields
     * are encoded. Production passes the platform decoder; the test passes the JVM one and gets to
     * assert on real captured listings.
     */
    fun shortEpg(body: String, decode: (String) -> String): List<Programme> {
        val listings = runCatching { JSONObject(body).optJSONArray("epg_listings") }.getOrNull()
            ?: return emptyList()

        return buildList(listings.length()) {
            for (index in 0 until listings.length()) {
                val entry = listings.optJSONObject(index) ?: continue
                val start = entry.longOrNull("start_timestamp") ?: continue
                val end = entry.longOrNull("stop_timestamp") ?: continue
                val title = decode(entry.optString("title")).trim()
                if (title.isEmpty()) continue
                add(Programme(title, start * 1000L, end * 1000L))
            }
        }.sortedBy(Programme::startMillis)
    }

    /**
     * Some panels inject category separators into the stream list, e.g. `#### DEPORTES ####`.
     * They are not playable.
     */
    private fun isCategoryHeader(name: String): Boolean =
        name.startsWith("##") && name.endsWith("##")

    /** What 92% of the catalogue is, and the only sane guess when the field is missing. */
    private const val DEFAULT_CONTAINER = "mkv"
}
