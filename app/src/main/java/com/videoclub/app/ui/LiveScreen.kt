package com.videoclub.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.videoclub.app.BuildConfig
import com.videoclub.app.Container
import com.videoclub.app.MainActivity
import com.videoclub.app.R
import com.videoclub.app.data.RefreshState
import com.videoclub.app.data.WatchReporter
import com.videoclub.app.player.LivePlayer
import com.videoclub.app.player.PlaybackState
import com.videoclub.app.player.currentChannel
import kotlin.math.abs
import kotlinx.coroutines.delay

/** Which layer, if any, is on top of the picture. There is never more than one. */
internal enum class LiveLayer { None, List, RefreshResult }

/** What Back means here. Three answers, and none of them is "nothing". */
internal enum class LiveBack { ShowList, Leave, DismissRefresh }

/**
 * Back, from every state the television section can be in.
 *
 * Two presses from the picture to the videoclub, which is what was asked for: the first shows the
 * channel list, the second leaves. That is one press shorter than the standalone app this is a port
 * of, and the difference is the whole point — there, the picture *was* the app and leaving meant
 * closing it, so the list had to be closable back to the picture. Here the picture is one screen of
 * a larger app, and the way back to it from the list is to choose a channel, which is what somebody
 * looking at a list of channels was going to do anyway.
 *
 * With no channels there is no list to show, so Back leaves straight away rather than doing nothing
 * — a fresh install with a refresh still in flight must not trap anybody.
 */
internal fun liveBackIntent(layer: LiveLayer, hasChannels: Boolean): LiveBack = when (layer) {
    LiveLayer.RefreshResult -> LiveBack.DismissRefresh
    LiveLayer.List -> LiveBack.Leave
    LiveLayer.None -> if (hasChannels) LiveBack.ShowList else LiveBack.Leave
}

/**
 * Live television: a picture, and things that appear over it.
 *
 * ## Why this screen owns every key itself
 *
 * There is no focus traversal here and no navigation. One composable holds the focus and answers
 * the whole remote, which is what makes a channel list that cannot be lost to a neighbouring
 * composable and a Back that means the same thing from everywhere. It is the opposite of how the
 * poster rows work, and deliberately so: a poster wall is a place you move around in, a channel is
 * a thing you are watching.
 *
 * | | Remote | Phone |
 * |---|---|---|
 * | Change channel | up / down | swipe left or right |
 * | Channel list | OK, or Back | tap, or Back |
 * | Back to the videoclub | Back, from the list | Back, from the list |
 * | Rebuild the channel list | hold OK | long press |
 *
 * ## Held OK is the maintenance hatch
 *
 * The list rebuilds itself once a day on its own, so this is only ever needed when the supplier has
 * renamed something today. It is a held press rather than a menu item because it is the sort of
 * thing that must not happen by accident and must not need a settings screen either.
 *
 * ## The player belongs to this screen
 *
 * Built here and released on the way out, unlike the repositories behind it, which live in the
 * [Container]. A channel holds a hardware decoder and one of the account's connections; leaving it
 * running under the videoclub would cost a film the decoder it needs and the supplier's permission
 * to open it.
 */
@Composable
fun LiveScreen(
    container: Container,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val profile = container.deviceProfile
    val skin = remember(profile) { LiveSkin.of(profile) }

    val player = remember {
        LivePlayer(
            context = context,
            httpClient = container.http,
            userAgent = container.provider.userAgent,
            client = container.client,
            scope = container.scope,
            profile = profile,
            onSettled = { channel ->
                container.reporter.settledOn(channel.label, WatchReporter.Kind.Channel)
            }
        )
    }

    val channels by container.liveChannels.channels.collectAsState()
    val refreshState by container.liveChannels.refreshState.collectAsState()
    val guide by container.epg.guide.collectAsState()
    val playback by player.state.collectAsState()
    val videoQuality by player.videoQuality.collectAsState()

    /**
     * A coarse clock. The guide only ever needs to know which programme is on now, and a programme
     * boundary is never more than half a minute away from being noticed at this rate.
     */
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CLOCK_TICK_MS)
        }
    }

    var layer by remember { mutableStateOf(LiveLayer.None) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var infoToken by remember { mutableIntStateOf(0) }
    var infoVisible by remember { mutableStateOf(false) }
    var awaitingRefreshResult by remember { mutableStateOf(false) }
    // Set when a held OK has already fired, so releasing the key does not also open the list.
    var okHandledAsHold by remember { mutableStateOf(false) }

    val currentChannel = playback.currentChannel
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun select(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        selectedIndex = index
        container.liveChannels.lastWatchedLabel = channel.label
        player.play(channel)
        container.epg.request(channel.feeds.first().streamId, System.currentTimeMillis())
    }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        val playing = currentChannel?.label
        val from = channels.indexOfFirst { it.label == playing }.coerceAtLeast(0)
        select(wrap(from + delta, channels.size))
    }

    // Only ever fires on the way in, and on a fresh install once the first refresh lands: `play`
    // is a no-op for the channel already playing, so coming back to a list that has been rebuilt
    // underneath does not restart the picture.
    LaunchedEffect(channels) {
        if (channels.isEmpty() || playback != PlaybackState.Idle) return@LaunchedEffect
        container.liveChannels.initialChannel()?.let { channel ->
            select(channels.indexOfFirst { it.label == channel.label }.coerceAtLeast(0))
        }
    }

    // Daily, and only when it is actually stale. On a warm start this decides to do nothing.
    LaunchedEffect(Unit) { container.liveChannels.refreshIfStale(System.currentTimeMillis()) }

    // Which channels this household has, so the panel can offer them in a dropdown. It is sent from
    // here rather than from the container because the television section is built the first time
    // somebody opens it: before that there is no list to tell anyone about.
    LaunchedEffect(channels) {
        container.reporter.lineup(channels.map { it.label })
    }

    // The panel has asked for a channel. Whether the order deserves obeying was already decided by
    // the container; all that happens here is finding the row and tuning it. A label this household
    // does not have is dropped silently: the channel was sent from somewhere else and nobody is
    // watching this screen waiting for an error message.
    val tuneTo by container.tuneTo.collectAsState()
    LaunchedEffect(tuneTo, channels) {
        val wanted = tuneTo ?: return@LaunchedEffect
        val index = channels.indexOfFirst { it.label.equals(wanted, ignoreCase = true) }
        if (index >= 0) {
            layer = LiveLayer.None
            select(index)
        }
        container.tuneHandled()
    }

    // Announce whatever ends up on screen, whichever way it got there.
    LaunchedEffect(currentChannel?.label) {
        if (currentChannel != null) infoToken += 1
    }

    LaunchedEffect(infoToken) {
        if (infoToken == 0) return@LaunchedEffect
        infoVisible = true
        delay(INFO_VISIBLE_MS)
        infoVisible = false
    }

    // Only a rebuild the viewer asked for interrupts them; the daily one stays silent.
    LaunchedEffect(refreshState, awaitingRefreshResult) {
        if (awaitingRefreshResult && refreshState !is RefreshState.Idle) {
            layer = LiveLayer.RefreshResult
        }
    }

    // The guide for what the cursor is on and the few rows after it, which is what a viewer reads
    // next. Requests for an answer already held are dropped inside the repository.
    LaunchedEffect(layer, selectedIndex, channels) {
        if (layer != LiveLayer.List) return@LaunchedEffect
        for (offset in 0 until GUIDE_LOOKAHEAD) {
            channels.getOrNull(selectedIndex + offset)?.let { channel ->
                container.epg.request(channel.feeds.first().streamId, System.currentTimeMillis())
            }
        }
    }

    fun openList() {
        if (channels.isEmpty()) return
        selectedIndex = channels
            .indexOfFirst { it.label == currentChannel?.label }
            .coerceAtLeast(0)
        layer = LiveLayer.List
    }

    fun startRefresh() {
        awaitingRefreshResult = true
        container.liveChannels.refresh(System.currentTimeMillis())
    }

    // A rebuild in flight is a couple of seconds and cannot be cancelled, so the panel stays put
    // until it resolves. That is not a dead end: it closes itself.
    fun dismissRefreshResult() {
        if (refreshState is RefreshState.Running) return
        awaitingRefreshResult = false
        container.liveChannels.acknowledgeRefresh()
        layer = LiveLayer.None
    }

    fun confirmSelection() {
        select(selectedIndex)
        layer = LiveLayer.None
    }

    fun goBack() {
        when (liveBackIntent(layer, channels.isNotEmpty())) {
            LiveBack.ShowList -> openList()
            LiveBack.Leave -> onLeave()
            LiveBack.DismissRefresh -> dismissRefreshResult()
        }
    }

    BackHandler(onBack = ::goBack)

    // Live television has no "where you left off", so leaving the app gives the decoder and the
    // account's one connection back, and coming back re-opens on the live edge.
    LifecycleStartEffect(player) {
        player.resume()
        onStopOrDispose { player.pause() }
    }

    // The activity lifecycle above catches leaving the app; it does not catch the television being
    // switched off with its own remote, which never calls `onStop`. `ScreenWatch`, via the
    // container, is the only source for that — see its own docstring for why it takes three signals
    // to answer one question.
    val screenOn by container.screenOn.collectAsState()
    LaunchedEffect(screenOn) {
        if (screenOn) player.resume() else player.pause()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val activity = context as? Activity

    // The whole panel, exactly as a film gets: no clock and no gesture bar over the picture, and on
    // a handset the phone turned on its side. All of it comes back on the way out.
    DisposableEffect(activity) {
        val bars = activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        bars?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        (activity as? MainActivity)?.setPlaybackOrientation(playing = true)
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            (activity as? MainActivity)?.setPlaybackOrientation(playing = false)
        }
    }

    // A `pointerInput` block keeps whatever lambdas it was first handed for as long as its keys are
    // unchanged. Reading them through a holder that recomposition keeps current is what stops a tap
    // opening the channel list as it was on the way in — which on a fresh install is no list at all.
    //
    // The swipe was left out of that list, and that is why "the swipe sometimes stops working":
    // [zap] captured `channels` and `currentChannel` exactly as they were on the first composition.
    // If the list had not arrived yet, the gesture is stuck with an empty list and never does
    // anything again; and with a list, it works out which channel to move on from using whichever
    // was playing back then, not the one playing now. It came back to life on opening and closing
    // the channel list, because that removes and re-adds this node — hence the "sometimes".
    val onTap by rememberUpdatedState<() -> Unit> { openList() }
    val onLongPress by rememberUpdatedState<() -> Unit> { startRefresh() }
    val onTapBesideList by rememberUpdatedState<() -> Unit> { layer = LiveLayer.None }
    val onSwipe by rememberUpdatedState<(Int) -> Unit> { delta -> zap(delta) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                handleLiveKey(
                    event = event,
                    layer = layer,
                    channelCount = channels.size,
                    selectedIndex = selectedIndex,
                    okHandledAsHold = okHandledAsHold,
                    setSelectedIndex = { selectedIndex = it },
                    setOkHandledAsHold = { okHandledAsHold = it },
                    onZap = ::zap,
                    onShowInfo = { infoToken += 1 },
                    onOpenList = ::openList,
                    onConfirmSelection = ::confirmSelection,
                    onHold = ::startRefresh,
                    onDismissRefreshResult = ::dismissRefreshResult
                )
            }
    ) {
        LiveSurface(player = player, modifier = Modifier.fillMaxSize())

        // Only ever attached while the picture is what is on screen. A layer that is up owns the
        // touchscreen outright, so a swipe over the channel list scrolls it instead of zapping.
        if (layer == LiveLayer.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                    }
                    .pointerInput(Unit) {
                        var travelled = 0f
                        val threshold = SWIPE_THRESHOLD.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = {
                                // Right to left is forward, the direction a page turns.
                                if (abs(travelled) >= threshold) onSwipe(if (travelled < 0) 1 else -1)
                            },
                            onHorizontalDrag = { change, delta ->
                                travelled += delta
                                change.consume()
                            }
                        )
                    }
            )
        }

        LiveNotice(
            playback = playback,
            hasChannels = channels.isNotEmpty(),
            refreshing = refreshState is RefreshState.Running,
            modifier = Modifier.align(Alignment.Center)
        )

        if (infoVisible && layer == LiveLayer.None && currentChannel != null) {
            LiveInfoBar(
                channel = currentChannel,
                programmes = guide[currentChannel.feeds.first().streamId].orEmpty(),
                nowMillis = now,
                quality = videoQuality?.label,
                skin = skin,
                modifier = Modifier.align(Alignment.BottomStart)
            )
        }

        // Under the list rather than over it, so a finger only reaches this where the panel is not:
        // the picture beside it. Tapping there closes the list and nothing else. Deliberately *not*
        // what a remote's second Back does — which leaves the screen: tapping outside a panel is the
        // universal gesture for "get rid of this", and in simple mode it led straight to the "turn
        // the television off" dialog, which is a lot to ask of a finger that strayed to the edge.
        if (layer == LiveLayer.List) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onTapBesideList() } }
            )

            ChannelList(
                channels = channels,
                selectedIndex = selectedIndex,
                playingLabel = currentChannel?.label,
                guide = guide,
                nowMillis = now,
                skin = skin,
                onSelect = { index ->
                    select(index)
                    layer = LiveLayer.None
                },
                modifier = Modifier.align(Alignment.CenterStart),
                house = BuildConfig.FLAVOR,
                accountUser = container.provider.username
            )
        }

        if (layer == LiveLayer.RefreshResult) {
            RefreshNotice(
                state = refreshState,
                onDismiss = ::dismissRefreshResult,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * The only thing ever drawn over a black picture: why it is black.
 *
 * Nothing here is a spinner for a normal launch — on a warm start the stream is already opening and
 * this shows for the fraction of a second before the first frame lands.
 */
@Composable
private fun LiveNotice(
    playback: PlaybackState,
    hasChannels: Boolean,
    refreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current

    val message = when {
        !hasChannels && refreshing -> stringResource(R.string.live_loading_channels)
        !hasChannels -> stringResource(R.string.live_no_channels)
        // A busy account is the most likely failure in a one-connection household, and saying "this
        // channel is not available" sends somebody to look at the channel, which is not where the
        // problem is.
        playback is PlaybackState.Failed && playback.accountBusy ->
            stringResource(R.string.account_busy)
        playback is PlaybackState.Failed -> stringResource(R.string.live_channel_dead)
        playback is PlaybackState.Recovering -> stringResource(R.string.live_channel_failed)
        playback is PlaybackState.Opening -> stringResource(R.string.live_starting)
        else -> null
    } ?: return

    // Opening and recovering are states the app usually leaves on its own within a second or two,
    // so they are held back: a channel that sorts itself out should never have put a warning in
    // front of the viewer in the first place. Anything to act on appears at once.
    val transient = playback is PlaybackState.Opening || playback is PlaybackState.Recovering
    var settled by remember(playback) { mutableStateOf(false) }
    LaunchedEffect(playback) {
        delay(NOTICE_GRACE_MS)
        settled = true
    }
    if (transient && !settled) return

    Label(
        text = message,
        style = skin.body,
        color = if (playback is PlaybackState.Failed) VideoclubColors.Accent
        else VideoclubColors.TextSecondary,
        modifier = modifier.background(LivePanel).padding(horizontal = 24.dp, vertical = 16.dp)
    )
}

/** What the rebuild did. Dismissed by any key or a tap, which is why it carries no button. */
@Composable
private fun RefreshNotice(
    state: RefreshState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val message = when (state) {
        is RefreshState.Done -> stringResource(R.string.live_refreshed, state.channelCount)
        RefreshState.NotThisLineup, RefreshState.Failed ->
            stringResource(R.string.live_refresh_failed)

        RefreshState.Running -> stringResource(R.string.live_refreshing)
        RefreshState.Idle -> return
    }

    Column(
        modifier = modifier
            .pointerInput(state) { detectTapGestures { onDismiss() } }
            .background(LivePanel)
            .padding(horizontal = 40.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Label(text = message, style = skin.sectionTitle, maxLines = 3)
        if (state !is RefreshState.Running) {
            Spacer(Modifier.height(12.dp))
            Label(
                text = stringResource(R.string.live_dismiss_hint),
                style = skin.caption,
                color = VideoclubColors.TextSecondary
            )
        }
    }
}

/**
 * The remote, in one function.
 *
 * Returns true when the key was consumed. While the rebuild panel is up everything is consumed, so
 * a stray press on a crowded remote cannot reach the channel list behind it.
 */
private fun handleLiveKey(
    event: KeyEvent,
    layer: LiveLayer,
    channelCount: Int,
    selectedIndex: Int,
    okHandledAsHold: Boolean,
    setSelectedIndex: (Int) -> Unit,
    setOkHandledAsHold: (Boolean) -> Unit,
    onZap: (Int) -> Unit,
    onShowInfo: () -> Unit,
    onOpenList: () -> Unit,
    onConfirmSelection: () -> Unit,
    onHold: () -> Unit,
    onDismissRefreshResult: () -> Unit
): Boolean {
    val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter ||
        event.key == Key.NumPadEnter

    if (event.type == KeyEventType.KeyUp) {
        if (!isOk) return false
        val handled = okHandledAsHold
        setOkHandledAsHold(false)
        if (handled) return true
        when (layer) {
            LiveLayer.None -> onOpenList()
            LiveLayer.List -> onConfirmSelection()
            LiveLayer.RefreshResult -> onDismissRefreshResult()
        }
        return true
    }

    if (event.type != KeyEventType.KeyDown) return false

    if (isOk) {
        // The first auto-repeat is the moment a press becomes a hold. Firing on that edge means the
        // viewer gets their answer while the button is still down, rather than on release.
        if (event.nativeKeyEvent.repeatCount == 1 && layer == LiveLayer.None) {
            setOkHandledAsHold(true)
            onHold()
        }
        return true
    }

    return when (layer) {
        LiveLayer.RefreshResult -> true

        LiveLayer.List -> when (event.key) {
            Key.DirectionUp -> {
                if (channelCount > 0) setSelectedIndex(wrap(selectedIndex - 1, channelCount))
                true
            }

            Key.DirectionDown -> {
                if (channelCount > 0) setSelectedIndex(wrap(selectedIndex + 1, channelCount))
                true
            }

            // A page is a screenful. Sixty channels is three pages, which is the difference between
            // reaching the football block in three presses and in thirty.
            Key.DirectionLeft, Key.ChannelDown -> {
                if (channelCount > 0) setSelectedIndex(wrap(selectedIndex - PAGE, channelCount))
                true
            }

            Key.DirectionRight, Key.ChannelUp -> {
                if (channelCount > 0) setSelectedIndex(wrap(selectedIndex + PAGE, channelCount))
                true
            }

            else -> false
        }

        // Up advances, which is what every television remote in the house already does: channel up
        // goes to the next channel, not the previous one. It reads as the opposite of the list,
        // where up moves the cursor up — but there the viewer is looking at rows, and here at a
        // picture. It also settles a contradiction: `ChannelUp` already meant forward in the list.
        LiveLayer.None -> when (event.key) {
            Key.DirectionUp, Key.ChannelUp -> {
                onZap(1)
                true
            }

            Key.DirectionDown, Key.ChannelDown -> {
                onZap(-1)
                true
            }

            Key.DirectionRight, Key.DirectionLeft, Key.Info -> {
                onShowInfo()
                true
            }

            else -> false
        }
    }
}

private fun wrap(index: Int, size: Int): Int = ((index % size) + size) % size

private const val CLOCK_TICK_MS = 30_000L
private const val INFO_VISIBLE_MS = 6_000L

/** Longer than a normal zap, shorter than the point at which a black screen looks broken. */
private const val NOTICE_GRACE_MS = 1_800L
private const val GUIDE_LOOKAHEAD = 6
private const val PAGE = 8

/** Long enough that dragging a finger across the picture is not a channel change by accident. */
private val SWIPE_THRESHOLD = 72.dp
