package com.videoclub.app

import android.content.Context
import com.videoclub.app.data.CatalogRepository
import com.videoclub.app.data.CatalogStore
import com.videoclub.app.data.CatalogSync
import com.videoclub.app.data.ChannelRepository
import com.videoclub.app.data.ChannelStore
import com.videoclub.app.data.EpgRepository
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.ProviderConfig
import com.videoclub.app.data.ProgressSync
import com.videoclub.app.data.ProviderSettings
import com.videoclub.app.data.RemoteConfigClient
import com.videoclub.app.data.VodClient
import com.videoclub.app.data.WatchReporter
import com.videoclub.app.data.detectDeviceProfile
import com.videoclub.app.data.wipeIfHouseholdChanged
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Every object the app owns, wired by hand.
 *
 * No dependency-injection framework, and that is a saving rather than an omission: the graph is
 * seven objects with no variants and no scopes, so a container that reads top to bottom in one
 * screen replaces an annotation processor and the build time it costs.
 */
/** What the app is allowed to draw, before and after it knows which household it belongs to. */
enum class Startup { Checking, Ready, NoCredentials }

class Container(context: Context) {

    private val appContext = context.applicationContext

    init {
        // Si a este aparato le han instalado el APK de otra casa encima, lo que hay en disco es de
        // la casa anterior. Va aquí arriba, y no en un `init` al final, porque tiene que ocurrir
        // antes de que `store` y `settings` abran esos mismos ficheros.
        wipeIfHouseholdChanged(appContext, BuildConfig.REMOTE_CONFIG_URL.trim())
    }

    /** Lives as long as the process: a catalogue sync must outlast any one screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * One HTTP client for the API, for playback and for posters.
     *
     * Shared on purpose: a single connection pool means the nine hundred catalogue requests reuse
     * sockets instead of opening nine hundred, which is most of why a full sync takes minutes
     * rather than tens of minutes.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Read once. Decides whether the UI is built for a D-pad or for a finger. */
    val deviceProfile: DeviceProfile = detectDeviceProfile(appContext)

    val store = CatalogStore(appContext)

    /**
     * The household this install belongs to: the account, and the people.
     *
     * One object, read by the client, by the player and by the startup gate, so there is no chance
     * of two of them disagreeing about which account is current. Nothing writes to it except the
     * hosted document.
     */
    val settings = ProviderSettings(appContext)

    val provider: ProviderConfig get() = settings.current

    private val remoteConfig = RemoteConfigClient(http, BuildConfig.REMOTE_CONFIG_URL.trim())

    /** Read afresh on every call, so a document adopted mid-session lands on the next request. */
    val client = VodClient(http) { settings.current }
    val catalog = CatalogRepository(store, client, CatalogSync(client, store), scope)

    /**
     * Le cuenta al panel qué se está viendo. Calla del todo si el documento no trae a dónde.
     *
     * Quién decide que algo «se está viendo» no es esto: es [ui.MainViewModel], que es el único que
     * sabe cuánto lleva puesto y si es película o serie.
     */
    val reporter = WatchReporter(http, scope, settings)

    /**
     * El «Seguir viendo» de la casa, igual en todos sus aparatos.
     *
     * Se le pide una vuelta al arrancar, al volver del fondo y cada vez que alguien ve algo. Nada
     * espera por él: lo que dibuja la pantalla sale de SQLite, y esto es un recado de fondo que
     * pone esa base al día.
     */
    val progressSync = ProgressSync(http, scope, store, settings) { catalog.reload() }

    // ------------------------------------------------------------------------- live television

    /**
     * The television section, built the first time somebody opens it and not before.
     *
     * `by lazy` is the whole point: a household that never presses the TV icon never reads a channel
     * cache off the disk, never runs two thousand stream names through the matcher and never opens a
     * socket to the guide. What it does share with the videoclub is the account and the HTTP client —
     * one connection pool, one set of credentials, one place to change them.
     *
     * The player is deliberately not here. A channel holds a hardware decoder and a socket against
     * an account that allows one connection, so it belongs to the screen that is showing it and is
     * released the moment that screen goes away. Everything else here is cheap enough to keep.
     */
    val liveStore: ChannelStore by lazy { ChannelStore(appContext) }

    val liveChannels: ChannelRepository by lazy {
        ChannelRepository(liveStore, client, scope) { settings.current.extraChannels }
    }

    val epg: EpgRepository by lazy { EpgRepository(client, scope) }

    private val _startup = MutableStateFlow(Startup.Checking)

    /** What the UI draws before it knows whose television this is. */
    val startup: StateFlow<Startup> = _startup.asStateFlow()

    /** One hosted-config check at a time; see [adoptHostedConfig]. */
    private var checkInFlight = false

    private val _screenOn = MutableStateFlow(true)

    /**
     * Si hay una tele encendida delante del aparato, según [ScreenWatch].
     *
     * Empieza en `true` porque no hay evidencia todavía en absoluto de lo contrario, y lo que
     * cuesta arrancar creyendo que hay pantalla es nada: [ScreenWatch] informa del estado real en
     * cuanto arranca, incluso si es el mismo `true`.
     */
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    /** [ScreenWatch]. */
    fun onScreen(on: Boolean) {
        _screenOn.value = on
    }

    private val _tuneTo = MutableStateFlow<String?>(null)

    /**
     * El canal que el panel ha mandado poner, hasta que alguien lo atienda.
     *
     * Lo consume la pantalla de televisión, que es la única que sabe sintonizar; aquí sólo se decide
     * **si una orden cuenta**, que es lo que no puede vivir en la interfaz.
     */
    val tuneTo: StateFlow<String?> = _tuneTo.asStateFlow()

    /** Llamado por quien la haya atendido, para que no vuelva a sonar. */
    fun tuneHandled() {
        _tuneTo.value = null
    }

    /**
     * Decide si el recado que trae el documento hay que obedecerlo.
     *
     * Dos filtros, y los dos hacen falta. **Que no se haya obedecido ya**: el documento sigue
     * trayendo la orden después de cumplirla, así que sin la marca en disco la caja saltaría al
     * canal en cada consulta. Y **que sea de ahora**: una orden de anoche no debe cumplirse cuando
     * alguien encienda la tele por la mañana — quien la mandó quería que se viera un partido que
     * hace horas que acabó.
     */
    private fun considerTuneOrder(nowMillis: Long) {
        val order = settings.tuneOrder ?: return
        // `liveStore` y no `store`: esto es estado de la televisión, y se toca sólo cuando hay una
        // orden de verdad, así que no adelanta la construcción perezosa de la sección por nada.
        if (order.issuedAt <= liveStore.obeyedTuneAt) return
        val ageSeconds = nowMillis / 1000 - order.issuedAt
        if (ageSeconds > TUNE_ORDER_MAX_AGE_SECONDS) return
        liveStore.obeyedTuneAt = order.issuedAt
        _tuneTo.value = order.label
    }

    /**
     * Finds out which household this box belongs to, and only then lets the app draw itself.
     *
     * The wait is paid deliberately at the front. With nothing compiled into the APK, a box whose
     * cache is empty has no account to show a catalogue *from*; and when the account has in fact
     * changed, drawing first would mean a shop full of somebody else's films for the second before
     * the sync throws them away. A spinner at startup reads as an app starting up. The same thing
     * halfway through reads as an app breaking.
     */
    fun start(nowMillis: Long) {
        scope.launch {
            val moved = fetchAndApply()
            catalog.adoptProfiles(settings.profiles)

            if (!provider.isConfigured) {
                _startup.value = Startup.NoCredentials
                retryUntilConfigured(nowMillis)
                return@launch
            }

            _startup.value = Startup.Ready
            // Una casa «simple» nunca enseña el videoclub: ni el catálogo —novecientas peticiones y
            // un par de minutos de CPU— ni el «Seguir viendo», que no tiene pantalla donde salir ni
            // nada que lo escriba. Lo que sí sigue hablando con el panel es [reporter], que es cómo
            // se sabe qué canal hay puesto.
            if (!provider.simple) {
                progressSync.request()
                // A catalogue belongs to the account it was fetched with. When that account moves,
                // the rows on disk are a different shop's stock and the ids in them point at nothing.
                if (moved && store.hasCatalogue) catalog.refresh(nowMillis) else catalog.refreshIfStale(nowMillis)
            }
        }
    }

    private var pollJob: Job? = null

    /**
     * Relee el documento cada pocos minutos mientras alguien tiene la app delante.
     *
     * Lo arranca y lo para la actividad, con su ciclo de vida: preguntar con la pantalla apagada
     * sería gastar batería para enterarse de recados que nadie va a ver. Es un `while` y no un
     * planificador del sistema porque no tiene que sobrevivir a la app — cuando la app no está,
     * esto no sirve para nada.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(FOREGROUND_POLL_MS)
                adoptHostedConfig(System.currentTimeMillis())
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * The same question on the way back to the foreground, without the spinner.
     *
     * Asked on every return, with no interval: the document is a few hundred bytes over a
     * connection the app keeps open anyway, and [checkInFlight] is what stops flicking in and out
     * of the app becoming a request per flick.
     */
    fun adoptHostedConfig(nowMillis: Long) {
        if (!remoteConfig.isEnabled) return
        if (_startup.value == Startup.Checking) return
        if (checkInFlight) return
        checkInFlight = true

        scope.launch {
            try {
                val moved = fetchAndApply()
                catalog.adoptProfiles(settings.profiles)
                // Lo primero que se mira del documento recién traído: es a lo que se le pide que
                // llegue pronto, y no depende de que nada más haya cambiado.
                considerTuneOrder(nowMillis)
                // De vuelta al primer plano: puede haberse visto algo en otro aparato mientras
                // tanto, y eso vale igual aunque el documento de la casa no se haya movido. En una
                // casa simple no, que ahí no hay «Seguir viendo» que poner al día.
                if (!provider.simple) progressSync.request()
                if (!moved) return@launch
                // El documento ha cambiado y puede ser otra casa: lo informado antes no describe a
                // ésta, así que lo siguiente que se vea se manda aunque se repita.
                reporter.forget()
                if (!provider.isConfigured) {
                    _startup.value = Startup.NoCredentials
                    retryUntilConfigured(nowMillis)
                    return@launch
                }
                if (!provider.simple) catalog.refresh(nowMillis)
            } finally {
                checkInFlight = false
            }
        }
    }

    /**
     * Keeps asking, quietly, for as long as there is no account.
     *
     * A set-top box routinely finishes booting before its network does, and the first fetch of the
     * day can easily land before there is a route out of the house. Settling permanently on an
     * error screen because of that would make the box need a human, which is the one thing this
     * whole arrangement exists to avoid.
     */
    private suspend fun retryUntilConfigured(nowMillis: Long) {
        while (!provider.isConfigured) {
            delay(NO_CREDENTIALS_RETRY_MS)
            fetchAndApply()
        }
        catalog.adoptProfiles(settings.profiles)
        _startup.value = Startup.Ready
        if (!provider.simple) catalog.refresh(nowMillis)
    }

    /** Returns whether the effective household actually moved. */
    private suspend fun fetchAndApply(): Boolean {
        if (!remoteConfig.isEnabled) return false
        val fetched = remoteConfig.fetch() ?: return false
        return settings.apply(fetched)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L

        /** Often enough to catch a network that arrives late, rare enough to be free. */
        const val NO_CREDENTIALS_RETRY_MS = 10_000L

        /**
         * Cuánto vale un «pon este canal» antes de caducar.
         *
         * Diez minutos es más que suficiente para que la caja se entere —se consulta cada dos— y lo
         * bastante poco como para que nada de anoche se cumpla esta mañana.
         */
        const val TUNE_ORDER_MAX_AGE_SECONDS = 10 * 60L

        /**
         * Cada cuánto se relee el documento mientras la app está delante.
         *
         * Sin esto, un aparato encendido y reproduciendo no vuelve a mirar el documento nunca —sólo
         * lo hace al arrancar y al encenderse la tele— que es justo el momento en el que a alguien
         * le interesa mandarle un canal. Son unos cientos de bytes: al lado de un stream, nada.
         */
        const val FOREGROUND_POLL_MS = 2 * 60 * 1000L
    }
}
