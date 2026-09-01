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
        // If this device has had another household's APK installed over the top, what is on disk
        // belongs to the previous household. This goes up here rather than in an `init` at the end
        // because it has to happen before `store` and `settings` open those same files.
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
     * Tells the panel what is being watched. Says nothing at all if the document carries no address.
     *
     * What counts as "being watched" is not decided here: that is [ui.MainViewModel], the only thing
     * that knows how long something has been on and whether it is a film or an episode.
     */
    val reporter = WatchReporter(http, scope, settings)

    /**
     * The household's "Continue watching", the same on every one of its devices.
     *
     * A round is asked for at launch, on returning to the foreground, and whenever somebody watches
     * something. Nothing waits on it: what the screen draws comes out of SQLite, and this is a
     * background errand that brings that database up to date.
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
     * Whether there is a television switched on in front of this box, as far as [ScreenWatch] knows.
     *
     * It starts at `true` because there is no evidence whatsoever to the contrary yet, and starting
     * out believing there is a screen costs nothing: [ScreenWatch] reports the real state the moment
     * it starts, even when that state is the same `true`.
     */
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    /** [ScreenWatch]. */
    fun onScreen(on: Boolean) {
        _screenOn.value = on
    }

    private val _tuneTo = MutableStateFlow<String?>(null)

    /**
     * The channel the panel has asked for, until somebody attends to it.
     *
     * The television screen consumes it, being the only thing that knows how to tune; all that is
     * decided here is **whether an order counts**, which is what cannot live in the interface.
     */
    val tuneTo: StateFlow<String?> = _tuneTo.asStateFlow()

    /** Called by whoever attended to it, so that it does not ring again. */
    fun tuneHandled() {
        _tuneTo.value = null
    }

    /**
     * Decides whether the errand the document carries should be obeyed.
     *
     * Two filters, and both are needed. **That it has not been obeyed already**: the document goes
     * on carrying the order after it has been carried out, so without the mark on disk the box would
     * jump to that channel on every check. And **that it is from now**: last night's order must not
     * be carried out when somebody switches the television on in the morning — whoever sent it
     * wanted a match watched that finished hours ago.
     */
    private fun considerTuneOrder(nowMillis: Long) {
        val order = settings.tuneOrder ?: return
        // `liveStore` rather than `store`: this is television state, and it is touched only when
        // there is a real order, so it never brings the section's lazy construction forward for
        // nothing.
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
            // A "simple" household never shows the video shop: not the catalogue — nine hundred
            // requests and a couple of minutes of CPU — and not "Continue watching" either, which
            // has no screen to appear on and nothing writing to it. What does go on talking to the
            // panel is [reporter], which is how anyone knows which channel is on.
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
     * The activity starts and stops it, with its lifecycle: asking with the screen off would spend
     * battery learning about errands nobody is going to see. It is a `while` rather than a system
     * scheduler because it does not have to outlive the app — when the app is gone, this is good
     * for nothing.
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
                // The first thing looked at in the freshly fetched document: it is the part that is
                // asked to arrive quickly, and it does not depend on anything else having changed.
                considerTuneOrder(nowMillis)
                // De vuelta al primer plano: puede haberse visto algo en otro aparato mientras
                // meanwhile, and that counts even if the household's document has not moved. Not in
                // a simple household: there is no "Continue watching" there to bring up to date.
                if (!provider.simple) progressSync.request()
                if (!moved) return@launch
                // The document changed and it may be another household: what was reported before
                // does not describe this one, so whatever is watched next is sent even if it
                // repeats.
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
         * How long a "tune to this channel" is good for before it expires.
         *
         * Ten minutes is more than enough for the box to find out — it checks every two — and little
         * enough that nothing from last night is carried out this morning.
         */
        const val TUNE_ORDER_MAX_AGE_SECONDS = 10 * 60L

        /**
         * How often the document is re-read while the app is in the foreground.
         *
         * Without this, a device that is on and playing never looks at the document again — it only
         * does so at launch and when the television is switched on — which is precisely the moment
         * somebody would want to send it a channel. It is a few hundred bytes: next to a stream,
         * nothing.
         */
        const val FOREGROUND_POLL_MS = 2 * 60 * 1000L
    }
}
