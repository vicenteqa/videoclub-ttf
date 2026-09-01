package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** How far along the catalogue download is, in categories. */
data class SyncProgress(val done: Int, val total: Int, val label: String) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * Filling the local catalogue from the supplier, one category at a time.
 *
 * ### Why category by category
 *
 * `get_vod_streams` with no filter answers with 60 MB and 177,653 rows, which no phone is going to
 * parse. The same endpoint with `category_id` answers in about a third of a second, and the largest
 * category in this account — 18,755 films — is 6.4 MB in 1.3 seconds. Nine hundred small requests
 * beat one enormous one on memory, on resumability, and on being able to show a progress bar that
 * means something.
 *
 * ### Why the home screen fills first
 *
 * Categories are synced in the supplier's own order, and that order is editorial: `PELÍCULA DE LA
 * SEMANA`, `ÚLTIMOS ESTRENOS`, `50 MÁS VISTAS` come before the eighteen-thousand-film dumps. The
 * first rows of the home screen are therefore populated within a few seconds of a fresh install,
 * while the long tail keeps arriving behind them.
 *
 * ### Why a failure does not empty the catalogue
 *
 * Withdrawn titles are removed by [SyncSession.sweep], which deletes everything not stamped by this
 * run. If any category failed to download, that sweep is skipped: a dropped Wi-Fi connection must
 * not be mistaken for the supplier having deleted its entire catalogue.
 */
class CatalogSync(
    private val client: VodClient,
    private val store: CatalogStore
) {

    suspend fun run(nowMillis: Long, onProgress: (SyncProgress) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val session = store.beginSync(nowMillis)
            var complete = true
            var done = 0

            try {
                val plan = Kind.entries.map { kind ->
                    kind to runCatching { client.categories(kind) }
                        .onFailure { complete = false }
                        .getOrDefault(emptyList())
                }
                val total = plan.sumOf { it.second.size }
                if (total == 0) return@withContext false

                for ((kind, categories) in plan) {
                    val ids = session.transaction {
                        categories.mapIndexed { index, category ->
                            category to session.putCategory(kind, category, index)
                        }
                    }

                    for (batch in ids.chunked(BATCH)) {
                        val fetched = coroutineScope {
                            batch.map { (category, id) ->
                                async { Triple(category, id, fetch(kind, category)) }
                            }.awaitAll()
                        }
                        if (fetched.any { it.third == null }) complete = false

                        session.transaction {
                            fetched.forEach { (_, id, listings) ->
                                listings.orEmpty().forEachIndexed { index, listing ->
                                    val titleId = session.putListing(kind, id, listing, index)
                                    // Series arrive with plot, cast and backdrop already attached.
                                    // Storing it now saves a request per series later on.
                                    listing.detail?.let { store.putListingDetail(titleId, it) }
                                }
                            }
                        }

                        done += batch.size
                        onProgress(SyncProgress(done, total, fetched.last().first.name))
                    }
                }

                if (complete) {
                    session.sweep()
                    store.markSynced(nowMillis)
                } else {
                    session.close()
                    Log.w(TAG, "Sync finished with gaps; keeping every existing row")
                }
                complete
            } catch (error: Throwable) {
                session.close()
                Log.w(TAG, "Sync aborted", error)
                throw error
            }
        }

    /** One category, with a single retry. Null means it never arrived. */
    private suspend fun fetch(kind: Kind, category: RemoteCategory): List<Listing>? {
        repeat(ATTEMPTS) { attempt ->
            runCatching { client.listings(kind, category.remoteId) }
                .onSuccess { return it }
                .onFailure { error ->
                    if (attempt == ATTEMPTS - 1) {
                        Log.w(TAG, "Category ${category.name} did not download: ${error.message}")
                    }
                }
        }
        return null
    }

    private companion object {
        const val TAG = "CatalogSync"

        /**
         * Concurrent category requests. Four is enough to hide the round trip without turning a
         * catalogue refresh into something the supplier would notice; the account allows a single
         * *stream*, and these are ordinary API calls, but there is no reason to be greedy.
         */
        const val BATCH = 4
        const val ATTEMPTS = 2
    }
}
