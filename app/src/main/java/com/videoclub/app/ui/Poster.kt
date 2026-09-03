package com.videoclub.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.videoclub.app.R
import com.videoclub.app.data.InProgress
import com.videoclub.app.data.Kind
import com.videoclub.app.data.Profile
import com.videoclub.app.data.Title

/**
 * What a long press on any poster opens, wherever that poster happens to be.
 *
 * A composition local rather than a parameter, and for once that is the simple option: posters are
 * drawn from five different screens, and threading a callback through every one of them — the rows,
 * the grids, the search results — to reach a card that already knows which title it is would be
 * five signatures changed to say the same thing.
 *
 * Null where nothing provides it, and then a long press does nothing at all, which is the right
 * behaviour for a preview or a test rather than a crash.
 */
val LocalPosterMenu = staticCompositionLocalOf<((Title) -> Unit)?> { null }

/**
 * One poster, with its name under it.
 *
 * The artwork does carry the title, but not at this size and not always in this language: a poster
 * is 108dp across on a phone, where printed lettering is a texture rather than a word, and the
 * supplier's artwork is whatever TMDB had — often the original release, occasionally nothing at all.
 * So the name is written underneath, which is also what the resume row has always done.
 *
 * Two lines are reserved whether or not the name needs them. A grid of cards whose captions are one
 * line here and two lines there has no baseline anywhere, and the ragged rows read as broken.
 *
 * Focus and touch are the same gesture here. `clickable` already makes the card focusable and
 * already answers to the centre key of a remote, so a television and a phone need one code path and
 * differ only in how far the card grows when it is picked out.
 *
 * Holding it opens [LocalPosterMenu] — the list, without going into the film to get at it. Long
 * press because it is the same gesture that takes a card off `Seguir viendo` and the same one that
 * opens the settings: in this app, holding something down is how you ask what else it can do.
 *
 * [showKind] marks series, and only the callers that mix the two kinds ask for it. In the Films tab
 * every poster is a film, so a badge there would be a sticker on every single card saying nothing.
 *
 * There is no quality badge. A wall of posters is a place to recognise a film, not to compare
 * encodes, and `4K` printed over the corner of every other card was the app talking about itself.
 * The encode is a decision, and the place for it is the page where the decision is made — where the
 * chooser, the codec, the resolution and the bitrate all live.
 */
@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progressFraction: Float? = null,
    showKind: Boolean = false,
    /** Told to the row, which is how the row knows where to put the cursor back. */
    onFocused: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    val menu = LocalPosterMenu.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Column(
        modifier = modifier
            .width(skin.posterWidth)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = menu?.let { open -> { open(title) } }
            )
    ) {
        ZoomOnFocus(focused, label = "posterScale") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(skin.posterHeight)
                    .clip(shape)
                    .background(VideoclubColors.PosterPlaceholder)
                    .then(
                        if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, shape)
                        else Modifier
                    )
            ) {
                if (title.posterUrl != null) {
                    AsyncImage(
                        model = title.posterUrl,
                        contentDescription = title.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (showKind && title.kind == Kind.Series) {
                    KindBadge(
                        text = stringResource(R.string.badge_series),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    )
                }

                progressFraction?.let { fraction ->
                    ProgressStripe(fraction, Modifier.align(Alignment.BottomCenter))
                }
            }
            Spacer(Modifier.height(6.dp))
            Label(
                text = title.name,
                style = skin.caption,
                // Brightens with the cursor, so the caption belongs to the card the remote is on.
                color = if (focused) VideoclubColors.TextPrimary else VideoclubColors.TextSecondary,
                maxLines = 2,
                minLines = 2
            )
        }
    }
}

/**
 * The focus zoom, wrapped around the *inside* of a card rather than applied to the card.
 *
 * ### Why it is not simply `Modifier.scale` on the card
 *
 * `Modifier.scale` is a graphics layer, and a layer on the same node as `clickable` sits inside the
 * rectangle Compose reports for the focused element. Every scroll container between that card and
 * the window is then holding a focused child whose rectangle *grows for as long as the zoom
 * animation lasts* — and `bringIntoView`, the machinery that scrolls a newly focused card into
 * sight, re-reads that rectangle on every frame and keeps scrolling after it.
 *
 * On a television that is exactly what it sounds like: move the cursor one poster along `TERROR`,
 * and the page underneath nudges itself for a third of a second, so the row above appears to hop.
 * It only settles at the ends of a row, where the list has nowhere left to scroll — which is why
 * running to the end of the row and coming back looked like it fixed it.
 *
 * Scaling a child leaves the card's own bounds exactly the size the row measured them, so there is
 * nothing left to chase. The zoom is drawing; drawing is all it should ever have been.
 */
@Composable
fun ZoomOnFocus(
    focused: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) LocalSkin.current.focusScale else 1f,
        label = label
    )
    Column(modifier = modifier.scale(scale), content = content)
}

/**
 * `Serie`, and the only sticker a poster carries.
 *
 * Black rather than red: this is a fact about what the poster is, not a boast, and the one thing it
 * has to survive is being drawn over somebody else's artwork.
 */
@Composable
private fun KindBadge(text: String, modifier: Modifier = Modifier) {
    Label(
        text = text,
        style = LocalSkin.current.caption,
        color = VideoclubColors.TextPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** How far in you are, drawn along the bottom edge of whatever card is showing it. */
@Composable
fun ProgressStripe(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxSize()
                .background(VideoclubColors.Accent)
        )
    }
}

/**
 * Where the cursor sits in a shelf: a fixed point, with the posters sliding under it.
 *
 * This is the other half of what makes a wall of posters feel like a wall rather than a list. Left
 * to itself a scrolling row moves as little as it can get away with, so the focused card ends up
 * pinned against whichever edge you were travelling towards and the row merely twitches. Every
 * television that does this well slides the *content* instead, and leaves the cursor where the eye
 * already is.
 *
 * A little over a quarter of the way across: far enough in that there is somewhere to have come
 * from, far enough out that most of the shelf is still ahead. At either end of a row there is
 * nothing left to slide, so the cursor moves instead — which is right, and is what makes the end of
 * a shelf feel like the end of something.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ShelfPivot = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - containerSize * PIVOT
}

private const val PIVOT = 0.28f

/** A heading and a horizontal strip of posters — the unit the whole home screen is built from. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterRow(
    heading: String,
    titles: List<Title>,
    onOpen: (Title) -> Unit,
    modifier: Modifier = Modifier,
    onHeadingClick: (() -> Unit)? = null,
    /** Lets the page move the cursor into this row from outside it — see `CategoryRows`. */
    focus: FocusRequester? = null
) {
    if (titles.isEmpty()) return
    val skin = LocalSkin.current

    /**
     * Which card the cursor was on when it last left this shelf.
     *
     * `rememberSaveable` and not `remember`, and that is the whole trick: a row scrolled off the
     * page is disposed, so an ordinary `remember` would forget the position exactly when the page
     * has scrolled far enough to need it. A lazy list keeps saveable state per item key, so the row
     * comes back knowing where it was — as does its own sideways scroll, which is why the card is
     * still composed and can be focused at all.
     */
    var current by rememberSaveable(titles.size) { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Label(
            text = heading,
            style = skin.sectionTitle,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = skin.screenPadding)
                .then(if (onHeadingClick != null) Modifier.clickable(onClick = onHeadingClick) else Modifier)
        )
        Spacer(Modifier.height(10.dp))
        // The posters slide under the cursor rather than the cursor travelling to the edge.
        CompositionLocalProvider(LocalBringIntoViewSpec provides ShelfPivot) {
            LazyRow(
                // One shelf is one thing as far as the cursor is concerned. `focusRestorer` covers
                // the ordinary way out of it — the focus search leaving sideways — but not the
                // page's own jump between shelves, which moves the cursor by hand and never asks
                // the group's permission to leave. That case is what [current] is for.
                modifier = Modifier.focusRestorer().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(skin.posterGap),
                // The extra room is what a focused card grows into: without it the first and last
                // posters of a row are clipped by the screen edge the moment they are picked out.
                contentPadding = PaddingValues(horizontal = skin.screenPadding, vertical = 12.dp)
            ) {
                itemsIndexed(titles, key = { _, title -> title.id }) { index, title ->
                    PosterCard(
                        title = title,
                        onClick = { onOpen(title) },
                        onFocused = { current = index },
                        // Only ever on one card: the one the page will hand the cursor back to.
                        modifier = when {
                            focus != null && index == current -> Modifier.focusRequester(focus)
                            else -> Modifier
                        }
                    )
                }
            }
        }
    }
}

/**
 * The resume row.
 *
 * Wide cards rather than posters, and for a reason: this row is about a specific moment in a
 * specific film, so it wants the backdrop and the bar showing how far in you are, neither of which
 * fits on a 2:3 poster.
 */
@Composable
fun ContinueRow(
    heading: String,
    entries: List<InProgress>,
    onOpen: (Title) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null
) {
    if (entries.isEmpty()) return
    val skin = LocalSkin.current
    var current by rememberSaveable(entries.size) { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Label(
            text = heading,
            style = skin.sectionTitle,
            modifier = Modifier.padding(horizontal = skin.screenPadding)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(skin.posterGap),
            contentPadding = PaddingValues(horizontal = skin.screenPadding, vertical = 12.dp)
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.title.id }) { index, entry ->
                ContinueCard(
                    entry = entry,
                    onClick = { onOpen(entry.title) },
                    onFocused = { current = index },
                    modifier = when {
                        focus != null && index == current -> Modifier.focusRequester(focus)
                        else -> Modifier
                    }
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(
    entry: InProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Column(
        modifier = modifier
            .width(skin.posterHeight)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .clickable(onClick = onClick)
    ) {
        ZoomOnFocus(focused, label = "continueScale") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(VideoclubColors.PosterPlaceholder)
                    .then(
                        if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, shape)
                        else Modifier
                    )
            ) {
                AsyncImage(
                    model = entry.title.posterUrl,
                    contentDescription = entry.title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                ProgressStripe(entry.progress.fraction, Modifier.align(Alignment.BottomCenter))
            }
            Spacer(Modifier.height(6.dp))
            Label(
                text = entry.title.name,
                style = skin.caption,
                color = VideoclubColors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/** A focusable button that reads well from three metres and from thirty centimetres. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val background = when {
        focused -> VideoclubColors.TextPrimary
        filled -> VideoclubColors.Accent
        else -> VideoclubColors.SurfaceElevated
    }
    val foreground = if (focused) VideoclubColors.Surface else VideoclubColors.TextPrimary

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp)
    ) {
        Label(text = text, style = skin.button, color = foreground, maxLines = 1)
    }
}

/**
 * One destination in the strip.
 *
 * [icon] replaces the word when there is a symbol everybody already reads — a magnifier says
 * "search" faster than `Buscar` does and a house says "home", and each of them costs a third of
 * what the word costs. That is what lets five destinations fit across a phone held upright.
 * [label] is still required: it becomes the spoken name.
 *
 * [pinned] keeps a chip out of the scrolling part, against the right edge. Only the magnifier asks
 * for it. The house is a symbol too and stays on the left, because it is the first destination and
 * a strip that starts somewhere other than its first destination reads as scrolled.
 */
data class TabEntry(
    val tab: Tab,
    val label: String,
    val icon: ImageVector? = null,
    val pinned: Boolean = false,
    /**
     * A chip that is drawn but cannot be pressed, and that the D-pad walks straight past.
     *
     * Used while the catalogue is being built, when every shelf leads to the same progress ring.
     * Drawn rather than removed: a strip that loses four of its six chips and puts them back two
     * minutes later is a strip that moves under the cursor, and the four that are missing are
     * exactly the information somebody is waiting for.
     */
    val enabled: Boolean = true
)

/**
 * The tab strip. Five destinations, always visible, never nested.
 *
 * ### Why the magnifier is not in the scrolling part
 *
 * `Inicio Películas Series Mi lista` plus a magnifier was about 460dp of chips, and a phone held
 * upright is 411dp wide. A plain row simply draws the fifth chip off the right edge, which is a
 * search button that does not exist as far as anybody holding the phone is concerned. So the words
 * scroll and the magnifier does not: it is pinned to the right edge, where it is both always on
 * screen and where a magnifier belongs anyway. Turning `Inicio` into a house took another 45dp out
 * of the scrolling half, which is most of what was overflowing.
 *
 * [autoFocus] puts the cursor on the selected chip when the screen appears. A television needs it —
 * with nothing focused, the first press of the D-pad goes nowhere — and a phone must not have it.
 */
@Composable
fun TabBar(
    tabs: List<TabEntry>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    /**
     * A long press on a chip. Every entry ignores it except `TV`, which asks the server whether
     * there is a release waiting — see [Container.checkForUpdate]. Routed here rather than baked
     * into `TabChip` itself, so this file stays ignorant of which destination that is.
     */
    onLongClick: (Tab) -> Unit = {},
    /** Pinned to the right, past the symbols. The profile chip lives here. */
    trailing: @Composable () -> Unit = {}
) {
    val skin = LocalSkin.current
    val focusRequester = remember { FocusRequester() }


    // The selected chip carries the requester — unless it is one of the disabled ones, since a
    // request against a chip the D-pad cannot reach fails silently and leaves a television with
    // nothing focused and a first key press that goes nowhere. Then the first chip that *can* be
    // reached takes it, which during a build is the television.
    val focusTarget = tabs.firstOrNull { it.tab == selected && it.enabled }?.tab
        ?: tabs.firstOrNull { it.enabled }?.tab
    val chipModifier = { entry: TabEntry ->
        if (entry.tab == focusTarget) Modifier.focusRequester(focusRequester) else Modifier
    }

    LaunchedEffect(autoFocus, focusTarget) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = skin.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.filterNot { it.pinned }.forEach { entry ->
                TabChip(
                    entry = entry,
                    selected = entry.tab == selected,
                    onClick = { onSelect(entry.tab) },
                    onLongClick = { onLongClick(entry.tab) },
                    modifier = chipModifier(entry)
                )
            }
        }
        tabs.filter { it.pinned }.forEach { entry ->
            TabChip(
                entry = entry,
                selected = entry.tab == selected,
                onClick = { onSelect(entry.tab) },
                onLongClick = { onLongClick(entry.tab) },
                modifier = chipModifier(entry)
            )
        }
        trailing()
    }
}

/**
 * Who the app currently thinks is watching, and the way to say otherwise.
 *
 * A letter in a circle rather than a word: it is the one thing up here that is not a destination,
 * and at 34dp it fits beside the magnifier without taking a chip's worth of the strip. Pressing it
 * moves straight on to the next person, `V` to `L` to `E` and round again.
 *
 * It used to re-open the question at the door instead, on the grounds that a mis-tap here changes
 * whose evening is being recorded. It does — and the letter that changed under the thumb is what
 * says so, immediately, in the place the thumb is already looking. A whole screen to confirm what
 * the chip can show is a screen nobody reads.
 *
 * Tapping it changes who is watching, and that is all it does now.
 *
 * Holding it used to open the settings, which were the account and the household. Both are edited
 * on a server now and neither has a screen here to open, so the gesture was removed rather than
 * left pointing at nothing — a long press that does nothing is worse than no long press, because
 * somebody keeps trying it.
 */
@Composable
fun ProfileChip(
    profile: Profile,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(skin.chipSize)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(
                if (focused) VideoclubColors.TextPrimary
                else VideoclubColors.avatarColor(profile.id)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Label(
            text = profile.initial,
            style = skin.button,
            color = if (focused) VideoclubColors.Surface else VideoclubColors.TextPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun TabChip(
    entry: TabEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Pill(
        filled = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        enabled = entry.enabled,
        horizontalPadding = if (entry.icon != null) 12.dp else 16.dp
    ) { foreground ->
        if (entry.icon != null) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.label,
                tint = foreground,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Label(text = entry.label, style = skin.button, color = foreground, maxLines = 1)
        }
    }
}

/**
 * The one chip shape in the app, and the three states it has.
 *
 * Focus is louder than selection on purpose: on a television the only thing the viewer needs to see
 * across the room is where the cursor is, and inverting the chip says that from further away than a
 * border does. [content] receives the foreground colour, because an icon tints and a label colours.
 */
@Composable
private fun Pill(
    filled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontalPadding: Dp = 16.dp,
    content: @Composable (foreground: Color) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val foreground = when {
        // First, and not just for looks: `clickable(enabled = false)` takes the chip out of the
        // focus order, so a disabled chip can never be the focused one and would otherwise be
        // indistinguishable from a chip that simply is not selected.
        !enabled -> VideoclubColors.TextDisabled
        focused -> VideoclubColors.Surface
        filled -> VideoclubColors.TextPrimary
        else -> VideoclubColors.TextSecondary
    }

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> VideoclubColors.TextPrimary
                    filled -> VideoclubColors.SurfaceElevated
                    else -> Color.Transparent
                }
            )
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
    ) {
        content(foreground)
    }
}

/** Centred one-liner for the handful of states that have nothing to show. */
@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Label(
            text = text,
            style = skin.body,
            color = VideoclubColors.TextSecondary,
            modifier = Modifier
                .padding(skin.screenPadding)
                .width(420.dp)
        )
    }
}

