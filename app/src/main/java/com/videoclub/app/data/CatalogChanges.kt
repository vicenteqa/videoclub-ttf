package com.videoclub.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The mirror's index of changes, `_catalogo/delta/index.json`, as `catalogo-maestro.py` writes it.
 *
 * [version] is the mirror as it is now. Changes files exist, unbroken, from [oldest] up to it — so a
 * catalogue holding version `v` catches up with `changes/v+1 … changes/version` when `v + 1 >= oldest`,
 * and needs the whole mirror again otherwise.
 */
data class CatalogIndex(val version: Long, val oldest: Long) {
    fun canCatchUpFrom(local: Long): Boolean = local in 1 until version && local + 1 >= oldest
}

/** One listing that changed: its whole order as supplier ids, and in full only the rows that moved. */
data class ListingChange(
    val kind: Kind,
    val categoryId: String,
    val order: List<Int>,
    val items: List<Listing>
)

/**
 * What took the mirror from [previous] to [version]. See `diff_mirrors` in `catalogo-maestro.py`.
 *
 * Everything in it says what something *is* now rather than how it moved, so applying it twice, or on
 * top of a catalogue that already had part of it, lands on the same place.
 */
data class CatalogChanges(
    val version: Long,
    val previous: Long,
    /** A kind's whole category list, present only for the kinds whose list changed. */
    val categories: Map<Kind, List<RemoteCategory>>,
    val listings: List<ListingChange>,
    val removedListings: List<Pair<Kind, String>>
)

/**
 * Reading the index and the changes files.
 *
 * Stricter than [CatalogJson], on purpose. A listing the supplier sends malformed is skipped there and
 * nothing is lost: the next full download reads it again. A changes file read halfway would leave the
 * catalogue quietly different from the mirror with nothing ever correcting it, so anything that does
 * not read as a whole makes [changes] answer null, and the caller downloads the mirror instead.
 *
 * The rows themselves still go through [CatalogJson], so a film arrives exactly as the full download
 * would have filed it — including being left out when it has no name, which is why such a row's id
 * is taken out of the order as well.
 */
object CatalogChangesJson {

    fun index(body: String): CatalogIndex? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = json.optLong("version", 0L)
        val oldest = json.optLong("oldest", 0L)
        if (version <= 0L || oldest <= 0L) return null
        return CatalogIndex(version, oldest)
    }

    fun changes(body: String): CatalogChanges? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = json.optLong("version", 0L)
        val previous = json.optLong("previous", 0L)
        if (version <= 0L || previous != version - 1) return null
        return runCatching {
            CatalogChanges(
                version = version,
                previous = previous,
                categories = categories(json.optJSONObject("categories")),
                listings = listings(json.optJSONArray("listings")),
                removedListings = removed(json.optJSONArray("removed_listings"))
            )
        }.onFailure { error ->
            Log.w(TAG, "Changes file $version is not readable as a whole: ${error.message}")
        }.getOrNull()
    }

    private fun categories(json: JSONObject?): Map<Kind, List<RemoteCategory>> {
        if (json == null) return emptyMap()
        return buildMap {
            for (raw in json.keys()) {
                val kind = mirrorKind(raw) ?: continue
                val rows = json.optJSONArray(raw) ?: error("categories of $raw is not a list")
                put(kind, CatalogJson.categories(rows.toString()))
            }
        }
    }

    private fun listings(array: JSONArray?): List<ListingChange> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: error("listing $index is not an object")
            val kind = mirrorKind(row.optString("kind")) ?: return@mapNotNull null
            val categoryId = row.opt("category_id")?.takeUnless { it == JSONObject.NULL }?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?: error("listing $index has no category")
            val order = row.optJSONArray("order") ?: error("listing $index has no order")

            val rows = row.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Listing>(rows.length())
            val unfiled = HashSet<Int>()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: error("listing $index, row $i is not an object")
                val parsed = CatalogJson.listings(kind, JSONArray().put(item).toString()).singleOrNull()
                if (parsed != null) {
                    items += parsed
                } else {
                    item.opt(idField(kind))?.toString()?.toIntOrNull()?.let(unfiled::add)
                }
            }

            ListingChange(
                kind = kind,
                categoryId = categoryId,
                order = (0 until order.length())
                    .mapNotNull { i -> order.opt(i)?.toString()?.toIntOrNull() }
                    .filter { it !in unfiled },
                items = items
            )
        }
    }

    private fun removed(array: JSONArray?): List<Pair<Kind, String>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: error("removed listing $index is not an object")
            val kind = mirrorKind(row.optString("kind")) ?: return@mapNotNull null
            val categoryId = row.opt("category_id")?.takeUnless { it == JSONObject.NULL }?.toString()
                ?: error("removed listing $index has no category")
            kind to categoryId
        }
    }

    private fun idField(kind: Kind): String = if (kind == Kind.Movie) "stream_id" else "series_id"

    private const val TAG = "CatalogChangesJson"
}
