package com.videoclub.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The curated list on disk, and which channel was on last.
 *
 * After curation a lineup is ~60 rows — a few kilobytes. A file rather than a table in
 * [CatalogDatabase], deliberately: the television section shares nothing with the videoclub but the
 * account, and this way it is read whole, synchronously, the instant the section opens, with no
 * query, no migration and no coroutine between the viewer and a picture. The 33,000-title catalogue
 * next door earns its database; sixty channels do not.
 *
 * It also means the two can never corrupt each other. A schema change on the videoclub side cannot
 * take the television down with it, and vice versa.
 *
 * Nothing here ever throws: a corrupt or half-written cache reads back as "no channels yet", which
 * the app already knows how to recover from by refreshing.
 */
class ChannelStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The channel that was playing when the app was last closed. */
    var lastWatchedLabel: String?
        get() = prefs.getString(KEY_LAST_WATCHED, null)
        set(value) = prefs.edit().putString(KEY_LAST_WATCHED, value).apply()

    /** When the cache was last written, as epoch millis, or 0 when there is no cache. */
    val savedAtMillis: Long
        get() = prefs.getLong(KEY_SAVED_AT, 0L)

    /**
     * The timestamp of the last "tune to this channel" that was obeyed, in seconds.
     *
     * It lives on disk rather than in memory on purpose: if the app restarted — or the power went —
     * with a recent order still in the document, without this it would jump to that channel again on
     * startup, which is exactly the sort of thing that makes somebody distrust the device.
     */
    var obeyedTuneAt: Long
        get() = prefs.getLong(KEY_TUNE_OBEYED, 0L)
        set(value) = prefs.edit().putLong(KEY_TUNE_OBEYED, value).apply()

    fun read(): List<Channel> {
        if (!file.exists()) return emptyList()
        return runCatching { ChannelJson.decode(file.readText()) }
            .onFailure { error -> Log.w(TAG, "Discarding an unreadable channel cache", error) }
            .getOrDefault(emptyList())
    }

    fun write(channels: List<Channel>, nowMillis: Long) {
        runCatching {
            // Write beside the real file and rename: a power cut mid-write leaves the previous
            // list intact rather than a truncated one.
            val scratch = File(file.parentFile, "$FILE_NAME.tmp")
            scratch.writeText(ChannelJson.encode(channels))
            if (!scratch.renameTo(file)) {
                file.writeText(scratch.readText())
                scratch.delete()
            }
            prefs.edit().putLong(KEY_SAVED_AT, nowMillis).apply()
        }.onFailure { error -> Log.w(TAG, "Could not save the channel cache", error) }
    }

    private companion object {
        const val TAG = "ChannelStore"
        const val FILE_NAME = "live-channels.json"
        const val PREFS_NAME = "videoclub-live"
        const val KEY_LAST_WATCHED = "last_watched_label"
        const val KEY_SAVED_AT = "saved_at"
        const val KEY_TUNE_OBEYED = "tune_obeyed_at"
    }
}
