package com.videoclub.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Coercing readers for a supplier that is casual about types.
 *
 * `stream_id` arrives as a number from one panel and as a quoted string from another; `rating` is
 * `"6.823"`, `6.823`, `"0"` or absent; `added` is a quoted epoch. A strict deserialiser would throw
 * away a whole category over one field, which is exactly the wrong trade when the payload is a
 * catalogue.
 */
internal fun JSONObject.intOrNull(key: String): Int? {
    if (isNull(key)) return null
    optInt(key, Int.MIN_VALUE).let { if (it != Int.MIN_VALUE) return it }
    return optString(key).trim().toIntOrNull()
}

internal fun JSONObject.longOrNull(key: String): Long? {
    if (isNull(key)) return null
    optLong(key, Long.MIN_VALUE).let { if (it != Long.MIN_VALUE) return it }
    return optString(key).trim().toLongOrNull()
}

internal fun JSONObject.doubleOrNull(key: String): Double? {
    if (isNull(key)) return null
    optDouble(key, Double.NaN).let { if (!it.isNaN()) return it }
    return optString(key).trim().toDoubleOrNull()
}

/** A text field, with the supplier's several ways of spelling "nothing" all mapped to null. */
internal fun JSONObject.textOrNull(key: String): String? {
    if (isNull(key)) return null
    val value = optString(key).trim()
    return value.takeUnless { it.isEmpty() || it == "null" || it == "0" || it == "0000-00-00" }
}

/**
 * `backdrop_path` is an array of TMDB URLs, but a few rows carry a bare string instead. Only the
 * first is ever drawn.
 */
internal fun JSONObject.firstUrlOrNull(key: String): String? {
    if (isNull(key)) return null
    optJSONArray(key)?.let { array ->
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }
    return textOrNull(key)?.takeIf { it.startsWith("http") }
}

/**
 * The video and audio blocks of `get_vod_info` are usually objects and sometimes arrays of streams.
 * When it is an array, the first entry is frequently the embedded cover art (`png`, `mjpeg`), which
 * is not what "the video codec" means to anybody.
 */
internal fun JSONObject.mediaStream(key: String): JSONObject? {
    if (isNull(key)) return null
    optJSONObject(key)?.let { return it }
    val array: JSONArray = optJSONArray(key) ?: return null
    for (index in 0 until array.length()) {
        val entry = array.optJSONObject(index) ?: continue
        if (entry.optString("codec_name") !in COVER_ART_CODECS) return entry
    }
    return array.optJSONObject(0)
}

private val COVER_ART_CODECS = setOf("png", "mjpeg", "bmp", "gif")
