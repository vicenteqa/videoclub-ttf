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
     * La dirección del stream, cuando no la construye el proveedor.
     *
     * Nula en todo lo que llega del catálogo Xtream, que es lo normal: ahí la dirección se arma con
     * [streamId] y las credenciales de la casa. La traen puesta los canales que salen del documento
     * alojado — ver [ExtraChannel]—, que son una URL y nada más.
     */
    val url: String? = null,
    /**
     * El `User-Agent` con el que pedir *este* stream, cuando el de la casa no sirve.
     *
     * El de la casa es el que exige el proveedor IPTV y no se puede cambiar sin romperlo. Pero una
     * televisión local sirve su directo desde su propia CDN, que a menudo rechaza cualquier
     * `User-Agent` que no le suene a navegador. Son dos servidores distintos con dos exigencias
     * incompatibles, así que la cabecera no puede ser una sola para toda la app.
     */
    val userAgent: String? = null
)

/**
 * «Pon este canal», dicho desde el panel.
 *
 * Existe para una situación muy concreta: a alguien de la casa se le pasa que juega su equipo, y
 * quien lleva el panel puede ponérselo desde fuera. No es configuración, es un recado — y por eso
 * lleva [issuedAt]: sin él, la caja volvería a saltar a ese canal cada vez que releyera el
 * documento, para siempre. Con él, una orden se obedece **una vez** y caduca sola.
 */
data class TuneOrder(val label: String, val issuedAt: Long)

/**
 * Un canal que no está en el proveedor y lo pone la casa: una televisión local, normalmente.
 *
 * Vive en el documento alojado y no compilado en el APK, por lo mismo que el resto: se añade uno
 * desde el panel sin recompilar ni volver a la casa. Es deliberadamente pobre —un nombre, una URL y
 * dos adornos— porque no pasa por la curación: lo que se escribe es exactamente lo que se ve.
 *
 * No trae guía: `epgChannelId` va nulo y la barra de información se queda sin programa, que es
 * mejor que inventarse uno.
 */
data class ExtraChannel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val userAgent: String? = null
) {
    /**
     * [position] sólo sirve para darle un `streamId` que no choque con los del proveedor, que son
     * siempre positivos. Nada lo usa para pedir nada — estos canales se piden por [Feed.url].
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
