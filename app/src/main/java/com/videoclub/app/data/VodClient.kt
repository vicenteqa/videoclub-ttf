package com.videoclub.app.data

import android.util.Base64
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Xtream calls this app makes, and nothing else.
 *
 * The catalogue is fetched **one category at a time**, never whole. Asking for the entire film list
 * returns 60 MB and 177,653 rows; asking for one category returns 100 KB in a third of a second,
 * and the largest category in the account is 6.4 MB in 1.3 seconds. That single choice is why there
 * is no streaming JSON parser here and no risk of an out-of-memory on a television box.
 *
 * No failure message ever includes the request URL: it carries the account credentials.
 */
class VodClient(
    private val http: OkHttpClient,
    private val provider: () -> ProviderConfig
) {

    /**
     * Read again on every call, never held.
     *
     * The account is editable from the settings screen while the app is running, and everything
     * here — the API calls and the two URL builders the player is handed — has to be talking to
     * whatever was typed last rather than to whatever was true when this object was built.
     */
    private val config: ProviderConfig get() = provider()

    suspend fun categories(kind: Kind): List<RemoteCategory> = withContext(Dispatchers.IO) {
        val action = if (kind == Kind.Movie) "get_vod_categories" else "get_series_categories"
        CatalogJson.categories(get(apiUrl(action)))
    }

    /** Everything the supplier files under one category. */
    suspend fun listings(kind: Kind, categoryId: String): List<Listing> = withContext(Dispatchers.IO) {
        val action = if (kind == Kind.Movie) "get_vod_streams" else "get_series"
        val body = get(apiUrl(action) { it.addQueryParameter("category_id", categoryId) })
        CatalogJson.listings(kind, body)
    }

    /** Plot, cast, runtime and the codec report, none of which appear in a film listing. */
    suspend fun movieDetail(streamId: Int): TitleDetail? = withContext(Dispatchers.IO) {
        runCatching {
            CatalogJson.movieDetail(get(apiUrl("get_vod_info") {
                it.addQueryParameter("vod_id", streamId.toString())
            }))
        }.getOrNull()
    }

    /**
     * One copy of a series: its own metadata and the episodes *that copy* has.
     *
     * [quality] is carried in rather than read out of the response, because the response does not
     * contain it — see [CatalogJson.seriesDetail].
     */
    suspend fun seriesDetail(
        seriesId: Int,
        quality: Quality = Quality.Hd
    ): SeriesDetail? = withContext(Dispatchers.IO) {
        runCatching {
            CatalogJson.seriesDetail(
                body = get(apiUrl("get_series_info") {
                    it.addQueryParameter("series_id", seriesId.toString())
                }),
                quality = quality
            )
        }.getOrNull()
    }

    /**
     * Where a film plays from.
     *
     * The container is per title rather than a build-wide setting, because unlike a live channel it
     * is a property of the file sitting on the supplier's disk: 92% `mkv`, the rest `avi`, `mp4` and
     * a handful of `ts`. Guessing it wrong is a 404, not a fallback.
     */
    fun movieUrl(streamId: Int, container: String): String =
        "${config.baseUrl}/movie/${config.username}/${config.password}/$streamId.$container"

    fun episodeUrl(episodeId: Int, container: String): String =
        "${config.baseUrl}/series/${config.username}/${config.password}/$episodeId.$container"

    // ------------------------------------------------------------------------- live television

    /** The whole live lineup, in one request. See [CatalogJson.liveStreams]. */
    suspend fun liveStreams(): List<Feed> = withContext(Dispatchers.IO) {
        val body = get(apiUrl("get_live_streams"))
        CatalogJson.liveStreams(body).also {
            if (it.isEmpty()) throw IOException("El proveedor no devolvió ninguna lista de canales.")
        }
    }

    /**
     * The next few programmes on one channel.
     *
     * Only what is on screen is ever asked for. Pulling the whole XMLTV guide for an account with
     * 2000 channels is the single most expensive thing an IPTV app can do at startup, and an
     * information strip needs two rows.
     */
    suspend fun shortEpg(streamId: Int, limit: Int = SHORT_EPG_LIMIT): List<Programme> =
        withContext(Dispatchers.IO) {
            val body = get(apiUrl("get_short_epg") {
                it.addQueryParameter("stream_id", streamId.toString())
                it.addQueryParameter("limit", limit.toString())
            })
            CatalogJson.shortEpg(body) { encoded ->
                runCatching {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault("")
            }
        }

    /**
     * Where a channel plays from.
     *
     * Unlike a film, the container is not a property of a file on the supplier's disk — there is no
     * file. It is a choice about transport: `.ts` is one endless request with the lowest latency,
     * `.m3u8` is segmented and rides out a lossy network better. `.ts` is what the television in
     * this house has been watching for months, so it is what this starts from.
     */
    fun liveUrl(streamId: Int): String =
        "${config.baseUrl}/live/${config.username}/${config.password}/$streamId.$LIVE_CONTAINER"

    // ------------------------------------------------------------------------------------ plumbing

    /**
     * Si la cuenta ya tiene abiertas todas las conexiones que le caben.
     *
     * `true` sí, `false` no, y **null cuando no se sabe** — que es un tercer caso de verdad y no un
     * «no»: sin red, o con un proveedor que no publica el dato, la respuesta honesta es callarse en
     * vez de acusar a nadie de estar viendo la tele.
     *
     * Se pregunta al endpoint de login, no a un stream, así que preguntarlo no gasta una conexión —
     * que sería una forma tonta de que la comprobación causara el problema que investiga.
     */
    suspend fun accountIsFull(): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val base = "${config.baseUrl}/player_api.php".toHttpUrlOrNull() ?: return@runCatching null
            val url = base.newBuilder()
                .addQueryParameter("username", config.username)
                .addQueryParameter("password", config.password)
                .build()
                .toString()
            CatalogJson.accountIsFull(get(url))
        }.getOrNull()
    }

    private fun apiUrl(action: String, extra: (okhttp3.HttpUrl.Builder) -> Unit = {}): String {
        val base = "${config.baseUrl}/player_api.php".toHttpUrlOrNull()
            ?: throw IOException("La dirección del proveedor no es una URL válida.")
        return base.newBuilder()
            .addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password)
            .addQueryParameter("action", action)
            .also(extra)
            .build()
            .toString()
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", config.userAgent)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("El proveedor respondió con ${response.code}.")
            }
            return response.body?.string().orEmpty()
        }
    }

    private companion object {
        const val LIVE_CONTAINER = "ts"

        /** What is on now and what is on next. An information strip has room for nothing else. */
        const val SHORT_EPG_LIMIT = 2
    }
}
