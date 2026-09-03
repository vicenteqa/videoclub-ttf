package com.videoclub.app.data

import android.util.Base64
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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

    /**
     * The shared catalogue mirror one household's account feeds every two hours — see
     * [ProviderConfig.catalogMirrorUrl] — or null when it cannot be used for any reason: no URL yet,
     * the request failed, the body will not parse, or it is more than a day old. That last one
     * matters as much as the others: a stuck VPS job must not quietly freeze the catalogue for every
     * household reading it, so past a day this is treated exactly like the mirror not existing —
     * [CatalogSync] falls back to asking the supplier directly, same as it always did.
     */
    suspend fun catalogMirror(): CatalogMirror? = withContext(Dispatchers.IO) {
        val url = config.catalogMirrorUrl
        if (url.isEmpty()) return@withContext null
        runCatching {
            val root = JSONObject(get(url))
            val generatedAtSeconds = root.optLong("generado_en", 0L)
            val ageSeconds = System.currentTimeMillis() / 1000 - generatedAtSeconds
            if (generatedAtSeconds <= 0L || ageSeconds > MIRROR_MAX_AGE_SECONDS) null
            else CatalogMirror(root)
        }.getOrNull()
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
     * Whether the account already has every connection it is allowed open.
     *
     * `true` yes, `false` no, and **null when it is not known** — a genuine third case rather than a
     * "no": with no network, or with a supplier that does not publish the figure, the honest answer
     * is to say nothing instead of accusing somebody of watching television.
     *
     * It asks the login endpoint, not a stream, so asking costs no connection — which would be a
     * silly way for the check to cause the very problem it is investigating.
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

        /** See [catalogMirror] — past this, a stale mirror is treated as no mirror at all. */
        const val MIRROR_MAX_AGE_SECONDS = 24L * 60 * 60
    }
}

/**
 * The categories and listings from `_catalogo/vod.json`, read back exactly as
 * `catalogo-maestro.py` wrote them — one call to the supplier's API, not nine hundred.
 *
 * Holds the parsed document rather than [Listing]/[RemoteCategory] rows: the categories array and
 * each category's listings array are handed to [CatalogJson] precisely as they arrived, the same
 * function that already reads them straight from the supplier, so nothing about how a listing is
 * parsed needs to know or care where the bytes came from.
 */
class CatalogMirror internal constructor(private val root: JSONObject) {

    fun categories(kind: Kind): List<RemoteCategory> =
        CatalogJson.categories(block(kind)?.optJSONArray("categorias")?.toString() ?: "[]")

    /** Null when this category never made it into the mirror — a household falls back for it alone. */
    fun listings(kind: Kind, categoryId: String): List<Listing>? {
        val array = block(kind)?.optJSONObject("streams")?.optJSONArray(categoryId) ?: return null
        return CatalogJson.listings(kind, array.toString())
    }

    private fun block(kind: Kind): JSONObject? =
        root.optJSONObject(if (kind == Kind.Movie) "vod" else "series")
}
