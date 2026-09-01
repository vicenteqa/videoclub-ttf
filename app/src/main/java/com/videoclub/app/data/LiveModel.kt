package com.videoclub.app.data

/**
 * One playable stream exactly as the supplier lists it, plus the few facts curation needs.
 *
 * A supplier carries the same channel many times over — SD/HD/FHD, "Opc2", "Multiaudio" — so a feed
 * is never what the viewer sees. It is a candidate for a [Channel] row.
 */
data class Feed(
    val streamId: Int,
    val originalName: String,
    /** [originalName] with the quality, codec and region noise stripped off. */
    val canonicalName: String,
    /**
     * The country the supplier tagged this feed with — `ES`, `PT`, `DE` — or null when it tagged
     * none, which on this account is the overwhelming majority.
     *
     * It says nothing about which channel a feed is, which is why [canonicalName] drops it. It says
     * everything about which of two otherwise identical feeds to play, which is why it is kept.
     */
    val region: String?,
    /** Declared vertical resolution, when the supplier tagged one. */
    val height: Int?,
    val isHdr: Boolean,
    val epgChannelId: String?,
    val logoUrl: String?,
    /**
     * The stream's address, when the supplier is not the one building it.
     *
     * Null for everything arriving from the Xtream catalogue, which is the normal case: there the
     * address is assembled from [streamId] and the household's credentials. Channels coming from the
     * hosted document carry it already set — see [ExtraChannel] — being a URL and nothing else.
     */
    val url: String? = null,
    /**
     * The `User-Agent` to ask for *this* stream with, when the household's own will not do.
     *
     * The household's is the one the IPTV supplier demands and cannot be changed without breaking
     * it. But a local television station serves its live feed from its own CDN, which often rejects
     * any `User-Agent` that does not look like a browser. Two different servers with two
     * incompatible demands, so the header cannot be a single one for the whole app.
     */
    val userAgent: String? = null
)

/**
 * "Tune to this channel", said from the panel.
 *
 * It exists for one very specific situation: somebody in the household forgets their team is
 * playing, and whoever runs the panel can put it on for them from outside. It is not configuration,
 * it is an errand — hence [issuedAt]: without it the box would jump back to that channel every time
 * it re-read the document, forever. With it, an order is obeyed **once** and expires on its own.
 */
data class TuneOrder(val label: String, val issuedAt: Long)

/**
 * A channel the supplier does not carry, added by the household: usually a local station.
 *
 * It lives in the hosted document rather than compiled into the APK, for the same reason as
 * everything else: one can be added from the panel without rebuilding or going back to the house.
 * It is deliberately poor — a name, a URL and two trimmings — because it does not go through
 * curation: what is written is exactly what is seen.
 *
 * It carries no guide: `epgChannelId` is null and the information bar is left without a programme,
 * which beats inventing one.
 */
data class ExtraChannel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val userAgent: String? = null
) {
    /**
     * [position] serves only to give it a `streamId` that will not collide with the supplier's,
     * which are always positive. Nothing uses it to request anything — these channels are requested
     * through [Feed.url].
     */
    fun toChannel(position: Int): Channel = Channel(
        label = name,
        logoUrl = logoUrl,
        epgChannelId = null,
        feeds = listOf(
            Feed(
                streamId = -(position + 1),
                originalName = name,
                canonicalName = name,
                region = null,
                height = null,
                isHdr = false,
                epgChannelId = null,
                logoUrl = logoUrl,
                url = url,
                userAgent = userAgent
            )
        )
    )
}

/**
 * One row on screen: a channel, with every other feed of the same channel kept behind it.
 *
 * The viewer never sees a quality menu. [feeds] is a fallback chain the player walks by itself when
 * a feed stops responding, and `feeds.first()` is what plays.
 */
data class Channel(
    /** Stable identity, and what is shown. Survives the supplier renaming its own streams. */
    val label: String,
    val logoUrl: String?,
    val epgChannelId: String?,
    val feeds: List<Feed>
)

/** One entry of the short guide: only what fits on a single line of an info bar. */
data class Programme(
    val title: String,
    val startMillis: Long,
    val endMillis: Long
)
