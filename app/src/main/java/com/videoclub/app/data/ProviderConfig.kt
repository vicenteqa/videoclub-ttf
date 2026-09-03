package com.videoclub.app.data

/**
 * The account the catalogue is fetched from.
 *
 * Nothing about it is compiled in. It arrives from the hosted config and from nowhere else, which
 * is what makes a rotated password a one-line edit on a server rather than a trip to whichever
 * house the television is in. [VodClient] reads it afresh on every call, so a document adopted
 * mid-session takes effect on the next request.
 *
 * The cost of that is a real state this app did not use to have — no account at all — which the UI
 * has to say out loud rather than paper over with an empty catalogue.
 */
data class ProviderConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    val userAgent: String,
    /**
     * Where to say what is being watched, and with which credential. Both come from the hosted
     * document and neither has a default: a build without them reports nothing, which is what should
     * happen until the panel sets them.
     */
    val reportUrl: String = "",
    val reportToken: String = "",
    /**
     * What the panel calls this household, for showing to a person — see [ChannelList]'s
     * `AccountFooter`. Distinct from `BuildConfig.FLAVOR`, which is a fixed identifier a build was
     * made with and never what to show anybody: this arrives from the hosted document, exactly like
     * everything else here, so renaming a household in the panel needs no rebuild to take effect.
     * Blank until a document carrying it has been read.
     */
    val houseName: String = "",
    /**
     * Whether this household is live television only: no video shop, no tabs, no profile picker.
     * The panel sets it; its default (`false`) is the full video shop as always.
     */
    val simple: Boolean = false,
    /** The channels the household adds on its own, on top of the supplier's. */
    val extraChannels: List<ExtraChannel> = emptyList()
) {

    /** Reporting is optional, and HTTPS only: this says what a person watches in their own home. */
    val reportsWhatIsOn: Boolean
        get() = reportUrl.startsWith("https://") && reportToken.isNotBlank()

    /**
     * Where the household's progress is kept: [reportUrl]'s neighbouring path, on the same server.
     *
     * Derived rather than being one more field in the document, and deliberately so: a new field
     * would force every existing document to be rewritten before already-installed devices could
     * sync, whereas what is here is an assumption about what the panel writes — always `…/informe` —
     * that is checked before being used. A document saying anything else leaves progress
     * unsynchronised, which is exactly what happened before this existed.
     */
    val syncUrl: String
        get() = if (reportUrl.endsWith(REPORT_PATH)) {
            reportUrl.removeSuffix(REPORT_PATH) + SYNC_PATH
        } else {
            ""
        }

    /** Si esta casa comparte el progreso entre sus aparatos. */
    val syncsProgress: Boolean
        get() = syncUrl.startsWith("https://") && reportToken.isNotBlank()

    /**
     * The shared catalogue mirror one household's account feeds every two hours, so the other five
     * do not each have to ask the supplier for the same ~900 requests' worth of categories — see
     * `catalogo-maestro.py`. Derived the same way as [syncUrl] and for the same reason: it is the
     * same URL for every household regardless of which one is the source, so nothing here needs to
     * be written into any document — an already-installed device gets it for free the moment the VPS
     * starts serving it.
     *
     * [VodClient.catalogMirror] treats a blank result exactly like a network failure: fall back to
     * asking the supplier directly, which is what every household already did before this existed.
     */
    val catalogMirrorUrl: String
        get() = if (reportUrl.endsWith(REPORT_PATH)) {
            reportUrl.removeSuffix(REPORT_PATH) + CATALOG_MIRROR_PATH
        } else {
            ""
        }
    /** Enough to ask the supplier something. The `User-Agent` has a default, so it is not here. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** What the settings screen demands before it will let the save button do anything. */
    val isComplete: Boolean get() = isConfigured && userAgent.isNotBlank()

    /** The form of it that gets stored, and therefore the only fair thing to compare against. */
    fun trimmed(): ProviderConfig = ProviderConfig(
        baseUrl = baseUrl.trim().trimEnd('/'),
        username = username.trim(),
        password = password.trim(),
        userAgent = userAgent.trim(),
        houseName = houseName.trim(),
        reportUrl = reportUrl.trim(),
        reportToken = reportToken.trim(),
        simple = simple,
        extraChannels = extraChannels
    )

    companion object {
        const val DEFAULT_USER_AGENT = "Videoclub/1.0"

        private const val REPORT_PATH = "/informe"
        private const val SYNC_PATH = "/sync"
        private const val CATALOG_MIRROR_PATH = "/videoclub/_catalogo/vod.json"

        /**
         * No account at all.
         *
         * Everything the hosted config says is laid over this, so an empty hosted config leaves an
         * app that says «Error de credenciales» — the honest answer, and a better one than showing
         * an empty shop as though the supplier had nothing to rent.
         */
        fun empty(): ProviderConfig = ProviderConfig(
            baseUrl = "",
            username = "",
            password = "",
            userAgent = DEFAULT_USER_AGENT,
            reportUrl = "",
            reportToken = ""
        )
    }
}
