package com.videoclub.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-disk shape of the curated list.
 *
 * Kept apart from [ChannelStore] purely so the encoding can be tested without an Android device:
 * everything here is plain `org.json`, no `Context`, no file system.
 *
 * The keys are short on purpose. The file is read synchronously on the startup path, and there is no
 * reason to parse `"canonicalName"` sixty times when `"canon"` says the same thing.
 */
internal object ChannelJson {

    /**
     * Bumped to 2 when a feed gained its region. A cache written by an older build reads back as no
     * channels, which the repository already answers by fetching the lineup again — one request,
     * once, on the first launch after an update.
     */
    const val FORMAT_VERSION = 2

    fun encode(channels: List<Channel>): String {
        val array = JSONArray()
        channels.forEach { channel ->
            val feeds = JSONArray()
            channel.feeds.forEach { feed ->
                feeds.put(
                    JSONObject()
                        .put(FEED_ID, feed.streamId)
                        .put(FEED_NAME, feed.originalName)
                        .put(FEED_CANONICAL, feed.canonicalName)
                        .put(FEED_REGION, feed.region ?: JSONObject.NULL)
                        .put(FEED_HEIGHT, feed.height ?: JSONObject.NULL)
                        .put(FEED_HDR, feed.isHdr)
                        .put(FEED_EPG, feed.epgChannelId ?: JSONObject.NULL)
                        .put(FEED_LOGO, feed.logoUrl ?: JSONObject.NULL)
                )
            }
            array.put(
                JSONObject()
                    .put(LABEL, channel.label)
                    .put(LOGO, channel.logoUrl ?: JSONObject.NULL)
                    .put(EPG, channel.epgChannelId ?: JSONObject.NULL)
                    .put(FEEDS, feeds)
            )
        }
        return JSONObject().put(VERSION, FORMAT_VERSION).put(CHANNELS, array).toString()
    }

    /**
     * The channels in [text], or an empty list if it is not a cache this build understands.
     *
     * A row missing its label or its feeds is skipped rather than fatal: half a usable list beats no
     * list at all on a TV whose only recovery gesture is a hidden key combination.
     */
    fun decode(text: String): List<Channel> {
        val root = JSONObject(text)
        if (root.optInt(VERSION) != FORMAT_VERSION) return emptyList()
        val array = root.optJSONArray(CHANNELS) ?: return emptyList()

        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val label = entry.optString(LABEL).takeIf(String::isNotBlank) ?: continue
                val feedArray = entry.optJSONArray(FEEDS) ?: continue

                val feeds = buildList(feedArray.length()) {
                    for (feedIndex in 0 until feedArray.length()) {
                        val feed = feedArray.optJSONObject(feedIndex) ?: continue
                        add(
                            Feed(
                                streamId = feed.optInt(FEED_ID),
                                originalName = feed.optString(FEED_NAME),
                                canonicalName = feed.optString(FEED_CANONICAL),
                                region = feed.optStringOrNull(FEED_REGION),
                                height = feed.optIntOrNull(FEED_HEIGHT),
                                isHdr = feed.optBoolean(FEED_HDR),
                                epgChannelId = feed.optStringOrNull(FEED_EPG),
                                logoUrl = feed.optStringOrNull(FEED_LOGO)
                            )
                        )
                    }
                }
                if (feeds.isEmpty()) continue

                add(
                    Channel(
                        label = label,
                        logoUrl = entry.optStringOrNull(LOGO),
                        epgChannelId = entry.optStringOrNull(EPG),
                        feeds = feeds
                    )
                )
            }
        }
    }

    private const val VERSION = "v"
    private const val CHANNELS = "channels"
    private const val LABEL = "label"
    private const val LOGO = "logo"
    private const val EPG = "epg"
    private const val FEEDS = "feeds"
    private const val FEED_ID = "id"
    private const val FEED_NAME = "name"
    private const val FEED_CANONICAL = "canon"
    private const val FEED_REGION = "cc"
    private const val FEED_HEIGHT = "h"
    private const val FEED_HDR = "hdr"
    private const val FEED_EPG = "epg"
    private const val FEED_LOGO = "logo"

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    /** A height of zero is not a height, so the sentinel and the absent value coincide harmlessly. */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key).takeIf { it != 0 }
}
