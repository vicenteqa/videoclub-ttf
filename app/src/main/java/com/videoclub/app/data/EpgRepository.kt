package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The guide, fetched one channel at a time and only for what is on screen.
 *
 * This is the deliberate opposite of pulling a full XMLTV file. An info bar needs the programme on
 * now and the one after it; asking the supplier for exactly that costs one small request, arrives in
 * well under a second, and — crucially — **nothing waits on it**. The picture is already playing.
 *
 * Nothing is persisted. A guide entry is stale within the hour, so a cache that survives a restart
 * would mostly serve wrong answers.
 */
class EpgRepository(
    private val client: VodClient,
    private val scope: CoroutineScope
) {

    private val _guide = MutableStateFlow<Map<Int, List<Programme>>>(emptyMap())
    val guide: StateFlow<Map<Int, List<Programme>>> = _guide.asStateFlow()

    private val fetchedAt = mutableMapOf<Int, Long>()
    private val inFlight = mutableSetOf<Int>()

    /**
     * Asks for one channel's guide, unless a recent answer is already held or a request for it is
     * still open. Safe to call on every frame of a scrolling list.
     */
    fun request(streamId: Int, nowMillis: Long) {
        if (streamId in inFlight) return
        val age = nowMillis - (fetchedAt[streamId] ?: 0L)
        if (age < REFRESH_INTERVAL_MS && _guide.value.containsKey(streamId)) return

        inFlight += streamId
        scope.launch {
            runCatching { client.shortEpg(streamId) }
                .onSuccess { programmes ->
                    fetchedAt[streamId] = nowMillis
                    _guide.value = _guide.value + (streamId to programmes)
                }
                .onFailure { error -> Log.d(TAG, "No guide for stream $streamId", error) }
            inFlight -= streamId
        }
    }

    private companion object {
        const val TAG = "EpgRepository"

        /** Short enough that a programme change shows up, long enough not to hammer the panel. */
        const val REFRESH_INTERVAL_MS = 10L * 60 * 1000
    }
}

/** The programme on air at [nowMillis], and the one after it. */
fun List<Programme>.nowAndNext(nowMillis: Long): Pair<Programme?, Programme?> {
    val now = firstOrNull { nowMillis in it.startMillis until it.endMillis }
    val next = firstOrNull { it.startMillis > nowMillis }
    return now to next
}
