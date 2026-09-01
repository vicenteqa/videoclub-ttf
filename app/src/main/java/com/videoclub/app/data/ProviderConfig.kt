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
     * Dónde decir qué se está viendo, y con qué credencial. Los dos vienen del documento alojado
     * y ninguno tiene valor por defecto: una build sin ellos no informa de nada, que es lo que
     * debe pasar mientras el panel no los ponga.
     */
    val reportUrl: String = "",
    val reportToken: String = "",
    /**
     * Si esta casa es sólo televisión en directo: sin videoclub, sin pestañas, sin selector de
     * personas. La pone el panel; su valor por defecto (`false`) es el videoclub completo de
     * siempre.
     */
    val simple: Boolean = false,
    /** Los canales que añade la casa por su cuenta, además de los del proveedor. */
    val extraChannels: List<ExtraChannel> = emptyList()
) {

    /** Informar es opcional, y sólo por HTTPS: esto dice qué ve una persona en su casa. */
    val reportsWhatIsOn: Boolean
        get() = reportUrl.startsWith("https://") && reportToken.isNotBlank()

    /**
     * Dónde se guarda el progreso de la casa. La ruta vecina de [reportUrl], en el mismo servidor.
     *
     * Se deduce en vez de ser un campo más del documento, y a propósito: un campo nuevo obligaría a
     * reescribir los documentos que ya existen para que los aparatos ya instalados sincronizaran, y
     * lo que hay aquí es una condición sobre lo que el panel escribe —siempre `…/informe`— que se
     * comprueba antes de usarla. Un documento que diga otra cosa deja el progreso sin sincronizar,
     * que es lo mismo que pasaba antes de que esto existiera.
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
        reportUrl = reportUrl.trim(),
        reportToken = reportToken.trim(),
        simple = simple,
        extraChannels = extraChannels
    )

    companion object {
        const val DEFAULT_USER_AGENT = "Videoclub/1.0"

        private const val REPORT_PATH = "/informe"
        private const val SYNC_PATH = "/sync"

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
