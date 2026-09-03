package com.videoclub.app.data

import android.util.Base64
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
     * [ProviderConfig.catalogMirrorUrl] — asked conditionally so an hourly check that finds nothing
     * new costs one small exchange of headers, not a 20-odd MB download parsed for nothing.
     *
     * [etag] is whatever [MirrorFetch.Updated.etag] answered last time, or null the first time this
     * is ever asked. Sent as `If-None-Match`; a server that still has the same file answers `304` and
     * no body at all, which is [MirrorFetch.Unchanged].
     */
    suspend fun catalogMirror(etag: String?): MirrorFetch = withContext(Dispatchers.IO) {
        val url = config.catalogMirrorUrl
        if (url.isEmpty()) {
            Log.i(TAG, "No catalogue mirror URL yet")
            return@withContext MirrorFetch.Unavailable
        }
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", config.userAgent).apply {
                if (etag != null) header("If-None-Match", etag)
            }.build()
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> MirrorFetch.Unchanged
                    !response.isSuccessful -> {
                        Log.w(TAG, "Mirror request refused (${response.code})")
                        MirrorFetch.Unavailable
                    }
                    else -> parseMirror(response)
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Mirror request failed (${error.javaClass.simpleName}: ${error.message})")
        }.getOrDefault(MirrorFetch.Unavailable)
    }

    /**
     * Reads the mirror one line at a time — see `catalogo-maestro.py`, which writes one small JSON
     * object per line rather than one hundred-odd MB document.
     *
     * The first version wrote a single document, and `response.body().string()` here tried to
     * allocate that whole thing as one Java string — UTF-16, so roughly twice the file's own size —
     * in one go. Measured directly on a phone: `OutOfMemoryError`, every time, silently swallowed by
     * the `runCatching` this sits inside, so the mirror was never actually being used at all. No
     * line here is bigger than one category's worth of listings — a few MB at most, the same size
     * [CatalogJson] already parses one at a time from the supplier directly — so nothing in this
     * function ever holds more than that in memory beyond the two maps it is filling in.
     */
    private fun parseMirror(response: Response): MirrorFetch {
        val body = response.body ?: return MirrorFetch.Unavailable
        var generatedAtSeconds = 0L
        val categoriesByKind = mutableMapOf<Kind, MutableList<RemoteCategory>>()
        val listingsByKey = mutableMapOf<String, String>()

        body.charStream().forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val row = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            when (row.optString("tipo")) {
                "meta" -> generatedAtSeconds = row.optLong("generado_en", 0L)
                "categorias" -> {
                    val kind = mirrorKind(row.optString("kind")) ?: return@forEachLine
                    val items = row.optJSONArray("items")?.toString() ?: "[]"
                    categoriesByKind.getOrPut(kind) { mutableListOf() } += CatalogJson.categories(items)
                }
                "listado" -> {
                    val kind = mirrorKind(row.optString("kind")) ?: return@forEachLine
                    val categoryId = row.optString("category_id")
                    val items = row.optJSONArray("items")?.toString() ?: return@forEachLine
                    listingsByKey[mirrorKey(kind, categoryId)] = items
                }
            }
        }

        val ageSeconds = System.currentTimeMillis() / 1000 - generatedAtSeconds
        // A stuck VPS job must not quietly freeze the catalogue for every household reading it —
        // past a day this is treated exactly like the mirror not existing, and the caller decides
        // from there whether that is fine (an hourly top-up just skips) or worth falling back to
        // the supplier for (the once-a-day refresh CatalogSync always did).
        if (generatedAtSeconds <= 0L || ageSeconds > MIRROR_MAX_AGE_SECONDS) {
            Log.w(TAG, "Mirror is too old or unreadable (generated_at=$generatedAtSeconds)")
            return MirrorFetch.Unavailable
        }
        Log.i(TAG, "Mirror fresh, ${ageSeconds}s old, ${listingsByKey.size} categories")
        return MirrorFetch.Updated(CatalogMirror(categoriesByKind, listingsByKey), response.header("ETag"))
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
        const val TAG = "VodClient"
        const val LIVE_CONTAINER = "ts"

        /** What is on now and what is on next. An information strip has room for nothing else. */
        const val SHORT_EPG_LIMIT = 2

        /** See [catalogMirror] — past this, a stale mirror is treated as no mirror at all. */
        const val MIRROR_MAX_AGE_SECONDS = 24L * 60 * 60
    }
}

/**
 * What asking the mirror came back with — see [VodClient.catalogMirror].
 *
 * Three answers rather than a nullable [CatalogMirror], because "nothing changed" and "could not
 * be used" call for opposite responses from whoever asked: an hourly top-up treats [Unchanged] as
 * success (there was nothing to do) and [Unavailable] as "try again next time, quietly", while the
 * once-a-day refresh treats [Unavailable] as its cue to fall back to the supplier directly.
 */
sealed class MirrorFetch {
    /** A fresh copy, and the marker to send back next time so the server can say "still this one". */
    data class Updated(val mirror: CatalogMirror, val etag: String?) : MirrorFetch()

    /** The server confirmed the [CatalogMirror] a caller already has is still the current one. */
    data object Unchanged : MirrorFetch()

    /** No URL yet, the request failed, the body would not parse, or it is more than a day old. */
    data object Unavailable : MirrorFetch()
}

/** `"vod"`/`"series"` as `catalogo-maestro.py` writes them, or null for anything else. */
private fun mirrorKind(raw: String): Kind? = when (raw) {
    "vod" -> Kind.Movie
    "series" -> Kind.Series
    else -> null
}

private fun mirrorKey(kind: Kind, categoryId: String): String =
    "${if (kind == Kind.Movie) "vod" else "series"}:$categoryId"

/**
 * The categories and listings from `_catalogo/vod.json`, read back exactly as
 * `catalogo-maestro.py` wrote them — one call to the supplier's API, not nine hundred.
 *
 * Holds a [RemoteCategory] list per kind and one raw JSON array of listings per category — already
 * parsed enough to know what is there, but each category's own listings stay text until
 * [listings] is actually asked for that one, and [CatalogJson] reads it exactly as it would read
 * the supplier's own response to `get_vod_streams?category_id=…`. Never the whole mirror as one
 * parsed tree: see [VodClient.parseMirror] for why that is the one thing this must not do.
 */
class CatalogMirror internal constructor(
    private val categoriesByKind: Map<Kind, List<RemoteCategory>>,
    private val listingsByKey: Map<String, String>
) {

    fun categories(kind: Kind): List<RemoteCategory> = categoriesByKind[kind].orEmpty()

    /** Null when this category never made it into the mirror — a household falls back for it alone. */
    fun listings(kind: Kind, categoryId: String): List<Listing>? =
        listingsByKey[mirrorKey(kind, categoryId)]?.let { CatalogJson.listings(kind, it) }
}
