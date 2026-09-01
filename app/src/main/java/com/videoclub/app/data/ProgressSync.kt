package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps "seguir viendo" the same on every screen in the house.
 *
 * ## What travels, and what cannot
 *
 * Not `title_id`. That number is this machine's: the catalogue sync hands it out with an
 * `AUTOINCREMENT` as the supplier's listings arrive, so the 4711 here and the 4711 on the
 * television are different films, and a protocol built on it would scatter resume markers across
 * the shop. What travels is `merge_key` — the thing that folds sixty listings of one film into one
 * row, computed the same way on both — and the season-and-episode number, which is arithmetic on
 * two integers the supplier publishes.
 *
 * ## Local first, always
 *
 * Every write lands in SQLite before anything is sent, and the app reads from SQLite and nowhere
 * else. A house with no internet — which is the normal state of a set-top box for the first
 * minute of every evening — keeps working exactly as it did before any of this existed. This is a
 * background errand, not a step on the way to playing something.
 *
 * ## Whoever wrote last wins
 *
 * Not whoever got furthest, which is the tempting rule and the wrong one: it makes starting a
 * series again impossible, because last year's finale outranks tonight's pilot for ever. The clock
 * of the device that wrote the row decides, so "the last thing I did" is what counts. Both ends
 * apply the same rule, which is why a push that crosses a pull in flight cannot leave the two
 * disagreeing.
 *
 * ## Nothing is silently dropped
 *
 * A row whose `merge_key` this catalogue does not know yet — the normal state while a fresh install
 * is still downloading — is not applied and, crucially, does not advance the counter past itself.
 * The house's ledger is read strictly in order, so "not yet" never turns into "lost".
 */
class ProgressSync(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val store: CatalogStore,
    private val settings: ProviderSettings,
    private val onChanged: () -> Unit,
) {

    /** One run at a time. A second request while one is in flight is dropped, not queued. */
    private val running = Mutex()
    private var job: Job? = null

    /**
     * Asks for a sync and returns immediately.
     *
     * Called on the way in, on the way back to the foreground, and after something has been
     * watched. None of those callers can afford to wait, and none of them needs the answer.
     */
    fun request() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            if (!running.tryLock()) return@launch
            try {
                runCatching { exchange() }
                    .onFailure { error ->
                        // The class and not the message: the message carries the URL, and these get
                        // read out loud down a telephone.
                        Log.w(TAG, "Could not sync progress (${error.javaClass.simpleName})")
                    }
            } finally {
                running.unlock()
            }
        }
    }

    private fun exchange() {
        val config = settings.current
        if (!config.syncsProgress) return

        val pending = store.pendingProgress(PUSH_LIMIT)
        val pendingList = store.pendingWatchlist(PUSH_LIMIT)
        val since = store.syncCounter

        val body = JSONObject().apply {
            put("desde", since)
            put("progreso", JSONArray().apply { pending.forEach { put(it.toJson()) } })
            put("lista", JSONArray().apply { pendingList.forEach { put(it.toJson()) } })
        }.toString()

        val request = Request.Builder()
            .url(config.syncUrl)
            .header("Authorization", "Bearer ${config.reportToken}")
            .post(body.toRequestBody(JSON))
            .build()

        val answer = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "The server refused the sync (${response.code})")
                return
            }
            JSONObject(response.body?.string().orEmpty())
        }

        // Only once the server has said yes. A failure anywhere above leaves every row dirty, and
        // the next run sends them again — which is the whole reason the flag exists.
        store.markProgressSynced(pending)
        store.markWatchlistSynced(pendingList)

        val incoming = answer.optJSONArray("progreso").rows()
        val incomingList = answer.optJSONArray("lista").listRows()
        val unresolved = store.applyRemoteProgress(incoming).toSet()
        val unresolvedList = store.applyRemoteWatchlist(incomingList).toSet()

        // The counter stops just short of the first thing that could not be placed, so the next run
        // asks for it again. Advancing past it would mean an episode watched on the television
        // never reaching a phone whose catalogue had not finished downloading.
        //
        // Rows after that one are applied all the same — it costs nothing and they are idempotent —
        // but they are not counted as read.
        //
        // One counter for the two ledgers, so it can only be as far along as the *further behind*
        // of them. A cursor that ran ahead on the strength of the progress rows would step over a
        // saved film whose title had not arrived yet, and «Mi lista» would be missing it for ever.
        val offered = answer.optLong("contador", since)
        val readTo = minOf(
            limitOf(incoming.map { it.mergeKey to it.counter }, unresolved, offered, since),
            limitOf(incomingList.map { it.mergeKey to it.counter }, unresolvedList, offered, since)
        )
        store.syncCounter = maxOf(since, readTo)

        if (incoming.isNotEmpty() || pending.isNotEmpty() ||
            incomingList.isNotEmpty() || pendingList.isNotEmpty()
        ) {
            val stuck = unresolved.size + unresolvedList.size
            Log.i(
                TAG,
                "Sincronizado: ${pending.size}+${pendingList.size} enviadas, " +
                    "${incoming.size}+${incomingList.size} recibidas" +
                    (if (stuck > 0) ", $stuck sin catálogo todavía" else "")
            )
        }
        if (incoming.isNotEmpty() || incomingList.isNotEmpty()) onChanged()
    }

    /**
     * How far a ledger can be treated as read: the counter of the last row before the first one
     * that could not be placed, or everything the server offers if all of them were placed.
     */
    private fun limitOf(
        rows: List<Pair<String, Long>>,
        unresolved: Set<String>,
        offered: Long,
        since: Long
    ): Long {
        if (unresolved.isEmpty()) return offered
        val readInOrder = rows.takeWhile { (key, _) -> key !in unresolved }
        return readInOrder.lastOrNull()?.second ?: since
    }

    private fun PendingListEntry.toJson(): JSONObject = JSONObject().apply {
        put("perfil", profileId)
        put("obra", mergeKey)
        put("guardado_en", addedAtMillis)
        put("cambiado_en", updatedAtMillis)
        if (deleted) put("borrado", true)
    }

    /** Same as [rows], and by the same rule: an unreadable row drops, the batch does not. */
    private fun JSONArray?.listRows(): List<RemoteListEntry> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val key = row.optString("obra").trim()
            if (key.isEmpty()) return@mapNotNull null
            val changed = row.optLong("cambiado_en", 0)
            RemoteListEntry(
                profileId = row.optInt("perfil", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                mergeKey = key,
                updatedAtMillis = changed,
                // An entry with no saved-at date is ordered by when it was decided, which is the
                // closest thing there is. It never ends up at zero, which would send it to the
                // bottom of the list.
                addedAtMillis = row.optLong("guardado_en", 0).takeIf { it > 0 } ?: changed,
                deleted = row.optBoolean("borrado", false),
                counter = row.optLong("contador", 0)
            )
        }
    }

    private fun PendingProgress.toJson(): JSONObject = JSONObject().apply {
        put("perfil", profileId)
        put("obra", mergeKey)
        put("episodio", episodeId)
        put("posicion", positionMillis)
        put("duracion", durationMillis)
        put("visto_en", updatedAtMillis)
        if (deleted) put("borrado", true)
    }

    /**
     * The server's rows, with anything unreadable dropped rather than the whole answer refused.
     *
     * One malformed row must not be able to wedge the counter for ever, which is exactly what
     * refusing the batch would do.
     */
    private fun JSONArray?.rows(): List<RemoteProgress> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val key = row.optString("obra").trim()
            if (key.isEmpty()) return@mapNotNull null
            RemoteProgress(
                profileId = row.optInt("perfil", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                mergeKey = key,
                episodeId = row.optInt("episodio", 0),
                positionMillis = row.optLong("posicion", 0),
                durationMillis = row.optLong("duracion", 0),
                updatedAtMillis = row.optLong("visto_en", 0),
                deleted = row.optBoolean("borrado", false),
                counter = row.optLong("contador", 0)
            )
        }
    }

    private companion object {
        const val TAG = "ProgressSync"

        /** How many rows go up at once. An evening is two or three; this is for a first run. */
        const val PUSH_LIMIT = 500

        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
