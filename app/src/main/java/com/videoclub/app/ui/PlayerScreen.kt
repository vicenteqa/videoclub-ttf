package com.videoclub.app.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.videoclub.app.Container
import com.videoclub.app.MainActivity
import com.videoclub.app.R
import com.videoclub.app.data.TrackChoice
import com.videoclub.app.player.VodPlayer
import kotlinx.coroutines.delay

/**
 * Full-screen playback.
 *
 * The controls are Media3's own [PlayerView], and all of them are: it already gives a seek bar a
 * D-pad can drive, a subtitle button and a gear with the audio tracks behind it.
 *
 * This screen used to add two buttons of its own for audio and subtitles, on the grounds that one
 * small gear is not a findable place to keep them. What that produced was two sets of controls on
 * one picture — one along the top in this app's type, one along the bottom in Material's, each with
 * its own idea of what a subtitle icon looks like — and a viewer who could not tell which was which.
 * One extra press on a gear is cheaper than two answers to the same question.
 *
 * Position is written every [SAVE_EVERY_MS] and once more on the way out. Writing continuously
 * would be pointless; writing only on exit would lose the film every time the app is killed in the
 * background, which for a two-hour film is the common case rather than the rare one.
 *
 * A copy the device cannot decode is not treated as a failure but as a fact about the device: the
 * screen moves to the next copy in [PlayRequest.copies] and says which way it went. Only when the
 * list runs out does it show an error, and then with the real `ERROR_CODE_*`, because "no se ha
 * podido reproducir" is not something anybody can act on. An unplayable *audio* track is handled a
 * level down, inside [VodPlayer], because the answer there is a different track and not a different
 * file.
 */
// `UnstableApi` is a Java annotation, so Kotlin's own `@OptIn` does not apply to it.
@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    request: PlayRequest,
    container: Container,
    onSaveProgress: (positionMillis: Long, durationMillis: Long, tracks: TrackChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val skin = LocalSkin.current
    val player = remember {
        VodPlayer(context, container.http, container.provider.userAgent)
    }
    val failure by player.failure.collectAsState()
    val audioFallback by player.audioFallback.collectAsState()

    /** Which copy is on screen. Only ever moves forward, and only on a decoding failure. */
    var attempt by remember(request) { mutableIntStateOf(0) }
    /** One line explaining a switch the viewer did not ask for. Clears itself. */
    var notice by remember(request) { mutableStateOf<String?>(null) }
    /** Whether the transport controls are up, which is when the track buttons make sense. */
    var controlsVisible by remember { mutableStateOf(true) }
    /** The view itself, because on a television the controls have to be summoned by hand. */
    var view by remember { mutableStateOf<PlayerView?>(null) }
    val copy = request.copies.getOrNull(attempt)

    /**
     * The controls hide themselves after four seconds, and something has to be able to call them
     * back. On a television that something is the remote, and the remote reaches whatever holds the
     * focus — which is why the picture itself is focusable and takes the focus back every time the
     * controls go away. A dialog closing counts as them going away.
     */
    LaunchedEffect(controlsVisible, copy?.url) {
        if (!controlsVisible) runCatching { view?.requestFocus() }
    }

    LaunchedEffect(copy?.url) {
        copy?.let { player.open(it.url, request.startPositionMillis, request.tracks) }
    }

    // Keyed on the failure alone: opening the next copy clears it, so one failure steps one copy.
    val switchingTo = stringResource(
        R.string.playback_switching,
        copy?.label.orEmpty(),
        request.copies.getOrNull(attempt + 1)?.label.orEmpty()
    )
    LaunchedEffect(failure) {
        val problem = failure ?: return@LaunchedEffect
        if (!problem.isFormatProblem || attempt + 1 >= request.copies.size) return@LaunchedEffect
        notice = switchingTo
        attempt += 1
    }

    /**
     * Whether the failure was really the account being busy.
     *
     * Asked only when the failure is [PlaybackFailure.isNetworkProblem] — a format problem has
     * nothing to do with how many connections are open, and neither does
     * `ERROR_CODE_FAILED_RUNTIME_CHECK`: measured directly, Media3's own stall watchdog fired an
     * hour into a film nobody else was watching, and answering "la cuenta se está usando en otro
     * aparato" for that is not an explanation, it is a second wrong guess on top of the first. The
     * format-copy logic above still gets its turn first, switching without bothering anybody.
     */
    var accountBusy by remember { mutableStateOf(false) }
    LaunchedEffect(failure) {
        val problem = failure
        if (problem == null) {
            accountBusy = false
            return@LaunchedEffect
        }
        if (problem.isFormatProblem && attempt + 1 < request.copies.size) return@LaunchedEffect
        accountBusy = problem.isNetworkProblem && container.client.accountIsFull() == true
    }

    val audioSwitched = stringResource(
        R.string.playback_audio_switched,
        audioFallback?.from.orEmpty(),
        audioFallback?.to.orEmpty()
    )
    LaunchedEffect(audioFallback) {
        if (audioFallback != null) notice = audioSwitched
    }

    // The notice is about the switch, not about the state, so it goes away on its own.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    LaunchedEffect(copy?.url) {
        while (true) {
            delay(SAVE_EVERY_MS)
            val (position, duration) = player.position() ?: (0L to 0L)
            onSaveProgress(position, duration, player.chosen())
        }
    }

    val activity = context as? Activity

    // The one screen that really does want the whole panel: no clock, no gesture bar over the film,
    // and on a handset the phone turned on its side. All of it comes back on the way out, which is
    // also the way back to a screen that expects it.
    DisposableEffect(activity) {
        val bars = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        (activity as? MainActivity)?.setPlaybackOrientation(playing = true)
        container.setPlaying(true)
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            (activity as? MainActivity)?.setPlaybackOrientation(playing = false)
            container.setPlaying(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val (position, duration) = player.position() ?: (0L to 0L)
            onSaveProgress(position, duration, player.chosen())
            player.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // The other half of the same thought: when the cursor happens to be on one of the two
            // buttons above rather than on the picture, any key still brings the controls back.
            // Back is left alone — that one means "leave", and it always has.
            .onPreviewKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyDown -> false
                    controlsVisible -> false
                    event.key == Key.Back -> false
                    else -> {
                        view?.showController()
                        true
                    }
                }
            }
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player.exoPlayer
                    useController = true
                    controllerShowTimeoutMs = CONTROLS_TIMEOUT_MS
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // The catalogue's Matroska files carry SubRip tracks — six of them on the one
                    // that was measured — and without this there is no way to reach them at all.
                    setShowSubtitleButton(true)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == android.view.View.VISIBLE
                        }
                    )
                    keepScreenOn = true
                    // A television remote talks to whatever has the focus. Without this the picture
                    // is not a thing that can have it, and once the controls hid themselves there
                    // was nothing left on the screen for the D-pad to talk to at all.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    requestFocus()
                }.also { view = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        notice?.let { text ->
            Label(
                text = text,
                style = skin.body,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp)
                    .background(VideoclubColors.Surface.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                maxLines = 2
            )
        }

        // Opening the next copy clears the failure, so anything left here has no copy behind it.
        failure?.let { problem ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(VideoclubColors.Surface.copy(alpha = 0.9f))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Label(text = request.heading, style = skin.sectionTitle, maxLines = 2)
                Label(
                    text = stringResource(
                        when {
                            accountBusy -> R.string.account_busy
                            problem.isFormatProblem -> R.string.playback_unsupported
                            else -> R.string.playback_failed
                        }
                    ),
                    style = skin.body,
                    color = VideoclubColors.TextSecondary
                )
                // The code, verbatim. It is the only part of this box worth reading out loud when
                // something has to be diagnosed from the other end of a phone call.
                Label(
                    text = problem.code,
                    style = skin.caption,
                    color = VideoclubColors.TextSecondary
                )
            }
        }
    }
}

private const val SAVE_EVERY_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 4_000
private const val NOTICE_MS = 5_000L
