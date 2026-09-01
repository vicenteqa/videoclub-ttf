package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a catalogue refresh is doing, so a viewer who asked for one gets an answer. */
sealed interface RefreshState {
    data object Idle : RefreshState
    data object Running : RefreshState
    data class Done(val channelCount: Int) : RefreshState
    /** The rules barely matched this lineup, so the saved list was left alone. */
    data object NotThisLineup : RefreshState
    data object Failed : RefreshState
}

/**
 * The curated list, and the only two ways it ever changes.
 *
 * The list is available **synchronously at construction** from [ChannelStore]: there is no loading
 * state to render on a normal launch, because the channels are already there before the first frame
 * is composed. A refresh happens afterwards, in the background, and only replaces the list when it
 * produced something the rules actually recognised.
 */
class ChannelRepository(
    private val store: ChannelStore,
    private val client: VodClient,
    private val scope: CoroutineScope,
    /**
     * Los canales que añade la casa, leídos en cada uso y no capturados una vez: el documento
     * alojado puede adoptarse a mitad de sesión y esto tiene que ir detrás.
     */
    private val extras: () -> List<ExtraChannel> = { emptyList() }
) {

    private val _channels = MutableStateFlow(withExtras(store.read()))
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    /** True on a fresh install: nothing can play until a refresh has succeeded. */
    val isEmpty: Boolean
        get() = _channels.value.isEmpty()

    private var job: Job? = null

    /**
     * Refreshes when the cache is missing or old. Called on every launch; on a warm start it
     * normally decides to do nothing at all.
     */
    fun refreshIfStale(nowMillis: Long) {
        val age = nowMillis - store.savedAtMillis
        if (!isEmpty && age < MAX_CACHE_AGE_MS) return
        refresh(nowMillis)
    }

    /**
     * Refreshes now. Concurrent calls collapse into the one already running.
     *
     * Returns the job doing the work — including when the call collapsed into one already in
     * flight — so that a caller which has just changed the account underneath this repository can
     * wait for the new lineup before deciding what to play. Callers with nothing to wait for
     * ignore it, which is most of them.
     */
    fun refresh(nowMillis: Long): Job {
        job?.let { existing -> if (existing.isActive) return existing }
        _refreshState.value = RefreshState.Running
        return scope.launch {
            _refreshState.value = runCatching { rebuild(nowMillis) }
                .onFailure { error -> Log.w(TAG, "Channel refresh failed", error) }
                .getOrDefault(RefreshState.Failed)
        }.also { job = it }
    }

    /**
     * The channel to open on: whoever was watching last, or the top of the list.
     *
     * By label rather than by stream id, because the ids behind a row change whenever the supplier
     * reshuffles its lineup and the label is what curation guarantees. A label that no longer exists
     * falls through to the first row rather than to nothing — the television section has to open on
     * a picture, always.
     */
    fun initialChannel(): Channel? {
        val list = _channels.value
        return list.firstOrNull { it.label == store.lastWatchedLabel } ?: list.firstOrNull()
    }

    /** Remembered across launches, so the section opens where it was left. */
    var lastWatchedLabel: String?
        get() = store.lastWatchedLabel
        set(value) {
            store.lastWatchedLabel = value
        }

    fun acknowledgeRefresh() {
        _refreshState.value = RefreshState.Idle
    }

    private suspend fun rebuild(nowMillis: Long): RefreshState {
        val lineup = client.liveStreams()
        // ~2000 streams through the matcher is tens of milliseconds on a desktop and more than a
        // frame's worth on a TV box, so it never runs on the thread drawing the picture.
        val curated = withContext(Dispatchers.Default) { LiveCuration.curate(lineup) }

        if (curated.size < LiveCuration.MIN_USABLE_CHANNELS) {
            Log.w(TAG, "Lineup of ${lineup.size} streams produced only ${curated.size} rows")
            return RefreshState.NotThisLineup
        }

        // Se guarda lo curado y sólo lo curado: los canales de la casa viven en el documento
        // alojado, y meterlos en esta caché sería una segunda copia que se queda vieja sola.
        store.write(curated, nowMillis)
        _channels.value = withExtras(curated)
        return RefreshState.Done(curated.size)
    }

    /**
     * Los del proveedor primero y los de la casa al final, nunca al revés.
     *
     * Ir delante los pondría de canal por defecto —[initialChannel] cae en el primero de la lista
     * cuando no hay ninguno recordado— y una casa que añade su televisión local no está pidiendo
     * que la app arranque siempre en ella.
     */
    private fun withExtras(curated: List<Channel>): List<Channel> {
        val added = extras()
        if (added.isEmpty()) return curated
        return curated + added.mapIndexed { position, canal -> canal.toChannel(position) }
    }

    private companion object {
        const val TAG = "ChannelRepository"

        /**
         * Suppliers reshuffle their lineup rarely, and a list that is a day old still plays. Daily
         * is often enough to pick up a rename, and rare enough that the check is invisible.
         */
        const val MAX_CACHE_AGE_MS = 24L * 60 * 60 * 1000
    }
}
