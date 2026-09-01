package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.videoclub.app.R
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.Episode
import com.videoclub.app.data.Kind
import com.videoclub.app.data.Quality
import com.videoclub.app.data.Source
import com.videoclub.app.data.Title

/**
 * Everything known about one work, and the buttons that start it.
 *
 * The quality picker is the unusual part. The supplier publishes the same film two to four times —
 * a 4K remux, a 60fps encode, an ordinary HD one — and every other app hides that behind three
 * near-identical rows in a list. Here they are one title with a chooser, and the codec, resolution
 * and bitrate of the chosen encode are printed underneath: a 73 Mbps HEVC remux and an 8 Mbps h264
 * are very different propositions over a hotel wifi, and the app has no business guessing which one
 * is wanted.
 */
@Composable
fun DetailScreen(
    state: DetailState,
    profile: DeviceProfile,
    onPlay: (resume: Boolean) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onSelectSource: (Source) -> Unit,
    onToggleWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
    /** The episode this page was opened *on*, if it was opened from `Seguir viendo`. */
    episodeKey: Int? = null
) {
    val skin = LocalSkin.current
    val title = state.title
    val detail = state.detail
    val playFocus = remember { FocusRequester() }
    val page = rememberLazyListState()

    /**
     * Which episode this page is about, whoever opened it.
     *
     * The card in `Seguir viendo` says so outright. Everybody else — a poster, a search result,
     * and above all the Back button on the way out of the player — says nothing, and the answer is
     * then the last episode this profile watched. Without that second half, walking out of episode
     * four landed back at the top of season one, which is the one place nobody wanted to be.
     */
    val resumeKey = episodeKey ?: state.progress?.episodeId?.takeIf { it > 0 }

    LaunchedEffect(title.id, profile, resumeKey) {
        // Not when the page is about an episode: the cursor belongs on that episode, and the
        // episode list puts it there itself.
        if (profile == DeviceProfile.Tv && resumeKey == null) {
            runCatching { playFocus.requestFocus() }
        }
    }

    // Straight past the poster and the plot to the list, once the list exists. Whoever this page is
    // about an episode for is four episodes in and knows what the series is about.
    LaunchedEffect(resumeKey, state.episodes.isNotEmpty()) {
        if (resumeKey != null && state.episodes.isNotEmpty()) {
            runCatching { page.animateScrollToItem(page.layoutInfo.totalItemsCount - 1) }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(VideoclubColors.Surface)) {
        Backdrop(backdropUrl = detail?.backdropUrl, posterUrl = title.posterUrl)

        LazyColumn(
            state = page,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(
                start = skin.screenPadding,
                end = skin.screenPadding,
                top = skin.screenPadding,
                bottom = skin.rowGap
            )
        ) {
            item(key = "head") {
                Header(
                    state = state,
                    playFocus = playFocus,
                    onPlay = onPlay,
                    onSelectSource = onSelectSource,
                    onToggleWatchlist = onToggleWatchlist
                )
            }

            detail?.plot?.let { plot ->
                item(key = "plot") { Label(text = plot, style = skin.body) }
            }

            item(key = "credits") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    detail?.director?.let {
                        Label(
                            text = stringResource(R.string.director, it),
                            style = skin.caption,
                            color = VideoclubColors.TextSecondary,
                            maxLines = 2
                        )
                    }
                    detail?.cast?.let {
                        Label(
                            text = stringResource(R.string.cast, it),
                            style = skin.caption,
                            color = VideoclubColors.TextSecondary,
                            maxLines = 3
                        )
                    }
                }
            }

            if (title.kind == Kind.Series) {
                episodeSection(
                    state = state,
                    onPlayEpisode = onPlayEpisode,
                    resumeKey = resumeKey,
                    autoFocus = profile == DeviceProfile.Tv && resumeKey != null
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: DetailState,
    playFocus: FocusRequester,
    onPlay: (Boolean) -> Unit,
    onSelectSource: (Source) -> Unit,
    onToggleWatchlist: () -> Unit
) {
    val skin = LocalSkin.current
    val title = state.title
    val detail = state.detail
    val resumeAt = state.progress?.takeIf { !it.isFinished && it.positionMillis > RESUME_FLOOR_MS }

    Row(horizontalArrangement = Arrangement.spacedBy(skin.screenPadding)) {
        AsyncImage(
            model = title.posterUrl,
            contentDescription = title.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(skin.posterWidth)
                .height(skin.posterHeight)
                .clip(RoundedCornerShape(skin.cornerRadius))
                .background(VideoclubColors.PosterPlaceholder)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(text = title.name, style = skin.heroTitle, maxLines = 2)

            val facts = listOfNotNull(
                title.year?.toString(),
                title.rating?.let { String.format("%.1f", it) },
                detail?.genre,
                detail?.durationSeconds
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.minutes, it / 60) }
            )
            if (facts.isNotEmpty()) {
                Label(
                    text = facts.joinToString("  ·  "),
                    style = skin.body,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 2
                )
            }

            // The whole point of the quality picker: what you are actually about to download.
            detail?.techLine?.let {
                Label(text = it, style = skin.caption, color = VideoclubColors.Accent, maxLines = 1)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (resumeAt != null) {
                    ActionButton(
                        text = stringResource(R.string.resume, clock(resumeAt.positionMillis)),
                        onClick = { onPlay(true) },
                        filled = true,
                        modifier = Modifier.focusRequester(playFocus)
                    )
                    ActionButton(text = stringResource(R.string.play), onClick = { onPlay(false) })
                } else {
                    ActionButton(
                        text = stringResource(R.string.play),
                        onClick = { onPlay(false) },
                        filled = true,
                        modifier = Modifier.focusRequester(playFocus)
                    )
                }
                ActionButton(
                    text = stringResource(
                        if (state.inWatchlist) R.string.in_list else R.string.add_to_list
                    ),
                    onClick = onToggleWatchlist
                )
            }

            if (title.sources.size > 1) {
                QualityPicker(
                    sources = title.sources,
                    selected = state.selected,
                    onSelect = onSelectSource
                )
            }
        }
    }
}

/** One chip per encode the supplier published. Only drawn when there is a real choice to make. */
@Composable
private fun QualityPicker(
    sources: List<Source>,
    selected: Source?,
    onSelect: (Source) -> Unit
) {
    val skin = LocalSkin.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(
            text = stringResource(R.string.quality),
            style = skin.caption,
            color = VideoclubColors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source ->
                Chip(
                    text = source.quality.label,
                    selected = source.remoteId == selected?.remoteId,
                    onClick = { onSelect(source) }
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

    Box(
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (selected) VideoclubColors.Accent else VideoclubColors.SurfaceElevated)
            .then(
                if (focused) Modifier.border(2.dp, VideoclubColors.TextPrimary, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Label(text = text, style = skin.caption, maxLines = 1)
    }
}

/**
 * Seasons and their episodes.
 *
 * A `LazyListScope` extension rather than a composable so the episode list is part of the one
 * scrolling column — a series with 200 episodes should not be a nested scroller inside the page.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.episodeSection(
    state: DetailState,
    onPlayEpisode: (Episode) -> Unit,
    resumeKey: Int?,
    autoFocus: Boolean
) {
    if (state.episodes.isEmpty()) {
        item(key = "episodes-empty") {
            Label(
                text = stringResource(
                    if (state.loading) R.string.loading else R.string.episodes_none
                ),
                style = LocalSkin.current.body,
                color = VideoclubColors.TextSecondary
            )
        }
        return
    }

    item(key = "seasons") {
        SeasonList(state.episodes, state.selected?.quality, onPlayEpisode, resumeKey, autoFocus)
    }
}

@Composable
private fun SeasonList(
    episodes: List<Episode>,
    preferred: Quality?,
    onPlayEpisode: (Episode) -> Unit,
    /** The episode the page was opened on: it picks the season and it keeps the cursor. */
    resumeKey: Int? = null,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current
    val seasons = remember(episodes) { episodes.map(Episode::season).distinct().sorted() }
    var season by remember(episodes) {
        mutableIntStateOf(
            episodes.firstOrNull { it.key == resumeKey }?.season ?: seasons.firstOrNull() ?: 1
        )
    }
    val shown = remember(episodes, season) { episodes.filter { it.season == season } }
    val chips = rememberLazyListState()

    // Where the cursor goes when the list it was standing on has just been replaced. Only the
    // remote asks for this: a thumb that swiped is not holding a cursor to put anywhere, and
    // lighting up the first episode after a swipe would look like the app had selected it.
    val firstEpisode = remember(episodes) { FocusRequester() }
    val resumeEpisode = remember(episodes) { FocusRequester() }
    var carryCursor by remember(episodes) { mutableStateOf(false) }

    LaunchedEffect(episodes, autoFocus) {
        if (autoFocus && episodes.any { it.key == resumeKey }) {
            runCatching { resumeEpisode.requestFocus() }
        }
    }

    LaunchedEffect(season) {
        // Guarded: with one season there is no chip strip, and scrolling a list that was never laid
        // out is asking a question of nothing.
        if (seasons.size > 1) {
            chips.animateScrollToItem(seasons.indexOf(season).coerceAtLeast(0))
        }
        if (carryCursor) {
            runCatching { firstEpisode.requestFocus() }
            carryCursor = false
        }
    }

    /** Returns whether it went anywhere, which is also whether the key press was used up. */
    fun step(delta: Int, withCursor: Boolean): Boolean {
        val next = seasons.getOrNull(seasons.indexOf(season) + delta) ?: return false
        carryCursor = withCursor
        season = next
        return true
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (seasons.size > 1) {
            LazyRow(
                state = chips,
                // One strip, one thing to arrive at. Without the group, coming down from the
                // quality chips landed on whichever season chip happened to be directly underneath
                // them — which with three seasons or more is season three.
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(seasons, key = { it }) { number ->
                    Chip(
                        text = stringResource(R.string.season, number),
                        selected = number == season,
                        onClick = { season = number }
                    )
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.seasonPaging(enabled = seasons.size > 1, onStep = ::step)
        ) {
            shown.forEachIndexed { index, episode ->
                EpisodeRow(
                    episode = episode,
                    playsAs = episode.sourceFor(preferred)?.quality,
                    onClick = { onPlayEpisode(episode) },
                    resuming = episode.key == resumeKey,
                    modifier = when {
                        episode.key == resumeKey -> Modifier.focusRequester(resumeEpisode)
                        index == 0 -> Modifier.focusRequester(firstEpisode)
                        else -> Modifier
                    }
                )
            }
        }
        Spacer(Modifier.height(skin.rowGap))
    }
}

/**
 * Sideways on an episode means the season before or the season after.
 *
 * An episode row is the full width of the page, so left and right had nothing to move to and did
 * nothing at all — while the one control they obviously belong to, the strip of seasons, was four
 * presses away up at the top of the list. This puts the two together: the chips are still there and
 * still work, and holding the cursor on `4. La noche de la iguana` and pressing right is now the
 * short way to season three.
 *
 * The drag is the same gesture for a thumb, and the same 72dp of travel the tab strip asks for.
 * Neither wraps around: a series has a first season and a last one, and arriving back at season one
 * from the finale reads as a bug rather than as a feature.
 *
 * [onStep] answers whether it moved, and that answer is what the key press is consumed by — at the
 * last season, right is left to the focus system, which does the ordinary nothing with it.
 */
@Composable
private fun Modifier.seasonPaging(enabled: Boolean, onStep: (Int, Boolean) -> Boolean): Modifier {
    if (!enabled) return this
    val threshold = with(LocalDensity.current) { SEASON_SWIPE.toPx() }
    // The drag detector is started once and would otherwise hold the first season it ever saw.
    val step by rememberUpdatedState(onStep)

    return this
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionRight -> step(1, true)
                Key.DirectionLeft -> step(-1, true)
                else -> false
            }
        }
        .pointerInput(Unit) {
            var travelled = 0f
            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = {
                    when {
                        travelled <= -threshold -> step(1, false)
                        travelled >= threshold -> step(-1, false)
                        else -> false
                    }
                }
            ) { _, delta -> travelled += delta }
        }
}

/**
 * @param playsAs the encode this row would actually start, which is not always the one the chips at
 *   the top say: the 4K copy of a series routinely skips whole seasons, and those episodes come off
 *   the ordinary copy instead. Saying so here is cheaper than an explanation.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    playsAs: Quality?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The one the viewer is in the middle of, marked so the page says why it opened here. */
    resuming: Boolean = false
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                if (focused || resuming) VideoclubColors.SurfaceElevated else Color.Transparent
            )
            // Where the cursor is, in the same white border the posters use, and louder than
            // anything else on the row. The red box the resume mark used to be was the other way
            // round: it shouted from a row nobody was on while the row under the cursor whispered.
            .then(
                if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The quiet half of the same idea: a bar down the edge says "this is the one you were on"
        // without competing with the cursor for attention.
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(skin.posterWidth * 9 / 16)
                .clip(RoundedCornerShape(2.dp))
                .background(if (resuming) VideoclubColors.Accent else Color.Transparent)
        )
        AsyncImage(
            model = episode.stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(skin.posterWidth)
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(VideoclubColors.PosterPlaceholder)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(
                text = stringResource(R.string.episode_label, episode.number, episode.title),
                style = skin.body,
                maxLines = 1
            )
            val facts = listOfNotNull(
                episode.durationSeconds
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.minutes, it / 60) },
                playsAs?.takeIf { it.notable }?.label
            )
            if (facts.isNotEmpty()) {
                Label(
                    text = facts.joinToString("  ·  "),
                    style = skin.caption,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 1
                )
            }
            episode.plot?.let {
                Label(
                    text = it,
                    style = skin.caption,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * The wash behind the page: the real backdrop when the supplier has one, otherwise the poster
 * blurred into a colour field. Either way it is scenery, so it is dimmed until the text on top of
 * it stays readable.
 */
@Composable
private fun Backdrop(backdropUrl: String?, posterUrl: String?) {
    val height = (LocalConfiguration.current.screenHeightDp * BACKDROP_FRACTION).dp
    val model = backdropUrl ?: posterUrl ?: return

    Box(Modifier.fillMaxWidth().height(height)) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdropUrl == null) Modifier.blur(40.dp) else Modifier)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to VideoclubColors.Surface.copy(alpha = 0.72f),
                    1f to VideoclubColors.Surface
                )
            )
        )
    }
}

/** `1:12:40`, or `12:40` for anything under an hour. */
internal fun clock(millis: Long): String {
    val total = millis / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%d:%02d", minutes, seconds)
}

/** The same travel the tab strip asks for, so one sideways drag means one thing in this app. */
private val SEASON_SWIPE = 72.dp

/** Below this the viewer barely started, and "resume" would be a worse offer than "play". */
private const val RESUME_FLOOR_MS = 30_000L
private const val BACKDROP_FRACTION = 0.55f
