package com.videoclub.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.videoclub.app.data.Channel
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.Feed
import com.videoclub.app.data.VodClient
import com.videoclub.app.data.feedsFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What the picture is doing, in the only four states worth telling the viewer apart. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    /** Tuning in: the surface is still black. */
    data class Opening(val channel: Channel) : PlaybackState
    data class Playing(val channel: Channel) : PlaybackState
    /** The feed died and the next one in the chain is being tried. */
    data class Recovering(val channel: Channel) : PlaybackState
    /**
     * Every feed of the channel failed. A retry is already scheduled.
     *
     * [accountBusy] separates this household's most likely failure — the account allows one
     * connection and another device is using it — from all the others. It starts false and turns
     * true if the supplier confirms it, so the screen says the generic thing first and corrects
     * itself to the specific one: the opposite of accusing and then taking it back.
     */
    data class Failed(val channel: Channel, val accountBusy: Boolean = false) : PlaybackState
}

/**
 * The picture as the decoder reports it, which is not always what the feed's name promised.
 *
 * Worth showing precisely because this app changes feed behind the viewer's back: a row that opened
 * in 1080p and quietly fell back to its 720p variant says so here, with no explaining to do.
 */
data class VideoQuality(val width: Int, val height: Int) {

    /**
     * The name a viewer already knows, not the number.
     *
     * Thresholds rather than exact matches, because encoders round: 1088 and 1082 are both Full HD
     * and neither is anything else. These four terms are the same in every language this app will
     * ever be read in, so they are not string resources — and the player has no access to them.
     */
    val label: String
        get() = when {
            height >= 2160 -> "4K"
            height >= 1080 -> "Full HD"
            height >= 720 -> "HD"
            else -> "SD"
        }
}

/** The channel this state is about, or null when nothing has been tuned yet. */
val PlaybackState.currentChannel: Channel?
    get() = when (this) {
        is PlaybackState.Opening -> channel
        is PlaybackState.Playing -> channel
        is PlaybackState.Recovering -> channel
        is PlaybackState.Failed -> channel
        PlaybackState.Idle -> null
    }

/**
 * One ExoPlayer for the life of the app, plus the fallback chain that keeps a picture on screen.
 *
 * ## Why the instance is never recreated
 *
 * Releasing a player and building another one for every zap tears down the codec and the output
 * surface with it. On a cheap box that is exactly where the stutter and the black flash come from.
 * Changing channel here is [ExoPlayer.setMediaItem] followed by [ExoPlayer.prepare] — the surface
 * and the decoder survive.
 *
 * ## Why failures walk a chain instead of showing an error
 *
 * There is nobody in front of this TV who will pick a different quality when a feed goes down, so a
 * dead feed silently advances to the next one curation kept behind it. Only when all of them fail
 * does anything appear on screen, and even then a retry is already scheduled — IPTV feeds come back.
 */
class LivePlayer(
    context: Context,
    httpClient: OkHttpClient,
    userAgent: String,
    private val client: VodClient,
    private val scope: CoroutineScope,
    private val profile: DeviceProfile,
    /**
     * Fires when a channel has been on long enough to count as what somebody is watching.
     *
     * The video shop already reported films and episodes from the position save, but television does
     * not go through that — there is no position to save in a live stream — so watching television
     * left the panel blank. This is the same thing SimpleTV did with its channels.
     */
    private val onSettled: ((Channel) -> Unit)? = null
) {

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Null until the decoder has something to report, which is roughly when the first frame lands. */
    private val _videoQuality = MutableStateFlow<VideoQuality?>(null)
    val videoQuality: StateFlow<VideoQuality?> = _videoQuality.asStateFlow()

    private var channel: Channel? = null
    private var feedIndex = 0

    /**
     * The `User-Agent` of the channel currently playing, or null to use the household's own.
     *
     * `@Volatile` because the thread that changes channel writes it and the one opening each HTTP
     * request reads it, and those are not the same thread.
     */
    @Volatile
    private var feedUserAgent: String? = null

    /**
     * How many times the feed at [feedIndex] has been re-opened since it last played.
     *
     * Most failures on this supplier are the connection being refused for a second, not the feed
     * being down — typically right after a zap, while the socket the previous channel was using is
     * still counted against the account. Walking the chain on the first error turns a hiccup into a
     * downgrade to a worse feed *and* an alarming message, so the first error buys a second attempt
     * at the same URL instead.
     */
    private var retriesOnFeed = 0

    private var recoveryJob: Job? = null
    private var stallJob: Job? = null

    private var settleJob: Job? = null

    /** Which channel [settleJob] is counting for, so that a rebuffer does not restart its clock. */
    private var settledLabel: String? = null
    private var pictureJob: Job? = null

    /** Set by [pause], so that [resume] knows the difference between coming back and starting up. */
    private var stoppedInBackground = false

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                // Software fallback matters more than purity here: a box whose hardware decoder
                // refuses one channel's profile should degrade to a picture, not to a black screen.
                // `ON` rather than `PREFER`: the device's own decoders are tried first and the
                // bundled FFmpeg one only picks up what they cannot do, which on a phone or a
                // tablet is every channel carrying AC3.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                // The same shared client and the same header the catalogue is fetched with. This
                // supplier refuses a request whose `User-Agent` it does not recognise, and the one
                // it recognises is whatever was typed into `Configuración`, so it is carried in
                // rather than hardcoded. The player is rebuilt when the section is opened, which is
                // after any edit to it.
                OkHttpDataSource.Factory { request ->
                    // A household's own channel — a local station — is served by a different CDN,
                    // which usually rejects any `User-Agent` that does not look like a browser. It is
                    // swapped here, on the request, and not on the factory: the factory is built once
                    // and the channel changes many times, and the header has to be the one belonging
                    // to the channel playing right now.
                    val perChannel = feedUserAgent
                    val outgoing = if (perChannel == null) request
                    else request.newBuilder().header("User-Agent", perChannel).build()
                    httpClient.newCall(outgoing)
                }
                    .setUserAgent(userAgent)
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                // A live stream has no length, so a byte target is meaningless — time is the only
                // threshold that means anything.
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(0, false)
                .build()
        )
        .build()
        .apply {
            playWhenReady = true
            setWakeMode(C.WAKE_MODE_NETWORK)
            setHandleAudioBecomingNoisy(true)
            // Asking for audio focus is what makes this behave on a phone: an incoming call or a
            // navigation prompt pauses the channel and hands the sound back afterwards, instead of
            // two apps talking over each other. On a television nothing else ever competes.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
        }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onFeedFailed(error.errorCodeName)
            }

            /** The only proof that there is a picture. Everything else is the player's opinion. */
            override fun onRenderedFirstFrame() {
                cancelPictureWatchdog()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Zero is what a reset reports, not a picture that is zero pixels tall.
                _videoQuality.value = videoSize
                    .takeIf { it.width > 0 && it.height > 0 }
                    ?.let { VideoQuality(it.width, it.height) }
                // The screen shows "Full HD"; the exact figure stays here, where diagnosing a feed
                // that is not what its name claims needs it.
                _videoQuality.value?.let {
                    Log.i(TAG, "Feed $feedIndex renders ${it.width}x${it.height} (${it.label})")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val current = channel ?: return
                when (playbackState) {
                    Player.STATE_READY -> {
                        cancelStallWatchdog()
                        // It played, so whatever went wrong before it did is forgiven: a feed that
                        // drops an hour from now gets its own second chance rather than inheriting
                        // an exhausted budget from the hiccup at tune-in.
                        retriesOnFeed = 0
                        _state.value = PlaybackState.Playing(current)
                        startSettleTimer(current)
                    }

                    Player.STATE_BUFFERING -> startStallWatchdog()

                    // A live feed that ends has not finished, it has dropped. Treat it as a failure.
                    Player.STATE_ENDED -> onFeedFailed("stream ended")

                    Player.STATE_IDLE -> Unit
                }
            }
        })
    }

    /**
     * Tunes to [target], starting from the best feed curation found for it.
     *
     * Calling this with the channel that is already playing is a no-op, so holding the D-pad down
     * over a list cannot restart the same stream a dozen times.
     */
    /**
     * @param force re-opens even when this row is already playing. Set when the account changed
     *   underneath: the label is the same, the stream ids behind it are not.
     */
    fun play(target: Channel, force: Boolean = false) {
        if (!force && channel?.label == target.label && _state.value is PlaybackState.Playing) return
        recoveryJob?.cancel()
        cancelStallWatchdog()
        channel = target
        feedIndex = 0
        retriesOnFeed = 0
        openCurrentFeed()
    }

    /** Re-tunes the current channel from the top of its chain. */
    fun retry() {
        val current = channel ?: return
        recoveryJob?.cancel()
        feedIndex = 0
        retriesOnFeed = 0
        channel = current
        openCurrentFeed()
    }

    /**
     * Gives up the decoder while the app is off screen.
     *
     * `stop` rather than `playWhenReady = false`: a paused live stream keeps a codec and an open
     * socket busy serving a buffer that is going stale by the second. On a box with one hardware
     * decoder, that is a decoder whatever the viewer switched to cannot have.
     */
    fun pause() {
        if (channel == null) return
        stoppedInBackground = true
        recoveryJob?.cancel()
        cancelStallWatchdog()
        cancelPictureWatchdog()
        cancelSettleTimer()
        exoPlayer.stop()
    }

    /**
     * Comes back on the live edge rather than where it left off.
     *
     * Live television has no "where you left off" — whatever was buffered is old news, and resuming
     * into it stalls far more often than it plays. Re-preparing costs the second or two it takes to
     * open the stream, which is the same second or two a channel change costs anyway.
     */
    fun resume() {
        exoPlayer.playWhenReady = true
        // False on the first launch, where `onStart` follows the `play` that has just happened in
        // `onCreate`. Re-preparing there would throw away the head start the whole app is built on.
        if (!stoppedInBackground) return
        stoppedInBackground = false
        retry()
    }

    fun release() {
        recoveryJob?.cancel()
        cancelStallWatchdog()
        cancelPictureWatchdog()
        cancelSettleTimer()
        exoPlayer.release()
    }

    /**
     * Starts the countdown for "this is what they are watching".
     *
     * A timer already running for the same channel is left alone, and that is what stops falling
     * down the feed chain — from Cuatro HD to Cuatro FHD — from restarting the clock: it is the same
     * channel through another door, not a new one. A rebuffer that recovers, likewise.
     */
    private fun startSettleTimer(channel: Channel) {
        val listener = onSettled ?: return
        if (settleJob?.isActive == true && settledLabel == channel.label) return
        settledLabel = channel.label
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(SETTLE_MS)
            listener(channel)
        }
    }

    private fun cancelSettleTimer() {
        settleJob?.cancel()
        settleJob = null
        settledLabel = null
    }

    // ---------------------------------------------------------------------------------- internals

    /** The fallback chain as this device wants it tried. Same feeds everywhere, different order. */
    private fun chain(of: Channel): List<Feed> = of.feedsFor(profile)

    private fun openCurrentFeed() {
        val current = channel ?: return
        val order = chain(current)
        val feed = order.getOrNull(feedIndex) ?: return

        // Which feed of a row was actually opened, and out of what. Without this line the only way
        // to tell a working preference from a broken one is to sit in front of the television, and
        // this television is in somebody else's house: `feedsFor` reorders the chain silently, so a
        // handheld that ends up on an interlaced feed looks identical in the log to one that never
        // had a progressive feed to choose.
        Log.i(
            TAG,
            "Opening feed $feedIndex/${order.size} of ${current.label}: " +
                "${feed.originalName} (${feed.height?.let { "${it}p" } ?: "sin altura"})"
        )

        // Whatever the last feed was showing is no longer true of this one.
        _videoQuality.value = null

        _state.value = if (feedIndex == 0) {
            PlaybackState.Opening(current)
        } else {
            PlaybackState.Recovering(current)
        }

        // Close the previous stream before asking for the next one. On an account with a connection
        // limit — which is most of them — the supplier refuses the new request while it still counts
        // the old socket as in use, and that refusal is exactly the "cannot load" the viewer sees.
        exoPlayer.stop()
        // Before asking for anything: the request factory reads this on every call.
        feedUserAgent = feed.userAgent

        // Preparing a `MediaItem` can throw rather than report through `onPlayerError`: ExoPlayer
        // looks up the factory for whichever format it smells in the URL by reflection, and if that
        // module is not linked in, the exception comes up through here and takes the app with it. A
        // household channel's URL is written by a person in the hosted document, so that is one typo
        // and an `.mpd` away. A channel that cannot even be opened is a channel that failed, which
        // is something this player already knows how to handle: it walks down the chain and, if
        // nothing is left, says so on screen.
        val opened = runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(feed.url ?: client.liveUrl(feed.streamId)))
            exoPlayer.playWhenReady = true
            exoPlayer.prepare()
        }
        if (opened.isFailure) {
            Log.w(TAG, "Could not prepare ${feed.originalName}", opened.exceptionOrNull())
            // Insistir no puede ayudar: el formato no va a estar enlazado dentro de un segundo.
            onFeedFailed("formato no soportado", retryable = false)
            return
        }
        startStallWatchdog()
        startPictureWatchdog()
    }

    /**
     * A feed did not deliver. Retries it before giving up on it, then walks the chain.
     *
     * The retry is what stops a one-second refusal from costing the viewer both the good feed and a
     * warning on screen; the delay is there because retrying instantly hits the same busy socket.
     * Pass [retryable] false when insisting cannot possibly help — a decoder the device does not
     * have will still not be there in a second's time.
     */
    private fun onFeedFailed(reason: String, retryable: Boolean = true) {
        if (channel == null) return
        cancelStallWatchdog()
        cancelPictureWatchdog()

        if (retryable && retriesOnFeed < RETRIES_PER_FEED) {
            retriesOnFeed += 1
            Log.w(TAG, "Feed $feedIndex failed ($reason), retrying it")
            recoveryJob?.cancel()
            recoveryJob = scope.launch {
                delay(FEED_RETRY_DELAY_MS)
                openCurrentFeed()
            }
            return
        }

        Log.w(TAG, "Feed $feedIndex gave up ($reason), moving down the chain")
        retriesOnFeed = 0
        advanceToNextFeed()
    }

    /**
     * Asks the supplier whether the account is full, and only then says so.
     *
     * **It is asked here and not on the first error**, and that is the part that matters: our own
     * socket counts as an open connection for a few seconds after being closed, so asking right
     * after a channel change would say "somebody else is watching it" while pointing at this very
     * television. By the time the whole chain is exhausted — two attempts per feed and every feed —
     * that is no longer in play.
     *
     * If the answer never arrives, or arrives saying no, nothing is touched: the generic message
     * already on screen is still the right one.
     */
    private fun askWhetherAccountIsBusy(current: Channel) {
        scope.launch {
            if (client.accountIsFull() != true) return@launch
            val state = _state.value
            // Puede haber cambiado de canal mientras se preguntaba; entonces la respuesta ya no
            // describes what is on screen.
            if (state is PlaybackState.Failed && state.channel == current) {
                Log.i(TAG, "The account has all of its connections in use")
                _state.value = PlaybackState.Failed(current, accountBusy = true)
            }
        }
    }

    private fun advanceToNextFeed() {
        val current = channel ?: return
        cancelStallWatchdog()
        cancelPictureWatchdog()
        feedIndex += 1

        if (feedIndex < chain(current).size) {
            openCurrentFeed()
            return
        }

        // Silence goes with the message. Not stopping here used to leave a channel sounding perfectly
        // fine underneath "no está disponible", which happens whenever a feed delivers audio but no
        // decodable video — on a handheld, every row whose only variants are this supplier's 1080i
        // ones. The viewer got sound out of a black screen for as long as they left it there, and no
        // amount of walking the chain ever fixed it, because every feed in the chain had the same
        // problem.
        exoPlayer.stop()
        _state.value = PlaybackState.Failed(current)
        askWhetherAccountIsBusy(current)
        // A whole channel being down is usually the supplier restarting it, not a permanent state,
        // so the chain is walked again from the top rather than left dead until someone notices.
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(CHANNEL_RETRY_DELAY_MS)
            feedIndex = 0
            retriesOnFeed = 0
            openCurrentFeed()
        }
    }

    /**
     * Some dead feeds do not error, they just never deliver a frame. Without this the screen stays
     * black forever on a channel the supplier has quietly stopped serving.
     */
    private fun startStallWatchdog() {
        if (stallJob?.isActive == true) return
        stallJob = scope.launch {
            delay(STALL_TIMEOUT_MS)
            if (exoPlayer.playbackState == Player.STATE_BUFFERING) {
                onFeedFailed("nothing in ${STALL_TIMEOUT_MS}ms")
            }
        }
    }

    private fun cancelStallWatchdog() {
        stallJob?.cancel()
        stallJob = null
    }

    /**
     * Catches the feed that plays perfectly and shows nothing.
     *
     * This provider serves its FHD variants as broadcast does — 1080i interlaced with E-AC3 audio —
     * which a television decodes natively and a phone frequently cannot. The failure is silent:
     * ExoPlayer reports `STATE_READY` and reaches the live edge while the surface stays black, so
     * neither [onPlayerError] nor the stall watchdog ever fires and the chain never walks down to the
     * 720p variant that would have played. A rendered frame is the only honest evidence of a picture.
     *
     * The cost of being wrong is bounded: a genuinely audio-only stream would walk its chain and land
     * on [PlaybackState.Failed], which retries from the top rather than looping.
     */
    private fun startPictureWatchdog() {
        pictureJob?.cancel()
        pictureJob = scope.launch {
            delay(PICTURE_TIMEOUT_MS)
            onFeedFailed("no frame in ${PICTURE_TIMEOUT_MS}ms", retryable = false)
        }
    }

    private fun cancelPictureWatchdog() {
        pictureJob?.cancel()
        pictureJob = null
    }

    private companion object {
        const val TAG = "LivePlayer"

        /**
         * How long a channel has to stay on before it counts.
         *
         * The same forty-five seconds SimpleTV used, and for the same reason: to separate the
         * channel somebody stopped on from the eleven they passed through to get there.
         */
        const val SETTLE_MS = 45_000L

        /**
         * Deep enough to ride out the drop-outs a domestic line hands a live TS stream, shallow
         * enough on the playback threshold that a zap still feels immediate.
         */
        const val MIN_BUFFER_MS = 30_000
        const val MAX_BUFFER_MS = 60_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000

        const val STALL_TIMEOUT_MS = 12_000L
        const val CHANNEL_RETRY_DELAY_MS = 15_000L

        /**
         * One. A second attempt catches the refused-connection case, which is nearly all of them;
         * a third would only delay the fallback that a genuinely dead feed needs.
         */
        const val RETRIES_PER_FEED = 1
        const val FEED_RETRY_DELAY_MS = 1_200L

        /** Comfortably longer than a normal tune-in, short enough not to look like a dead app. */
        const val PICTURE_TIMEOUT_MS = 8_000L
    }
}
