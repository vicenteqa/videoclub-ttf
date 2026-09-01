package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.videoclub.app.R
import com.videoclub.app.data.ContinueEntry
import com.videoclub.app.data.SyncState
import com.videoclub.app.data.Title

/**
 * The screen the app opens on: what you were in the middle of, and nothing else above it.
 *
 * There is no billboard here. A catalogue of 33,000 titles has no editorial front page to feature,
 * and the app's own guess at one — the first poster of the first category, blurred to fill the
 * width — was decoration standing where the useful thing goes. The useful thing is the film you
 * stopped forty minutes into on Tuesday.
 *
 * Under it, in this order: `Mi lista`, which is the one row on this page somebody built by hand,
 * one title at a time; then what those two suggest, which the app guessed; and last what the
 * supplier added this week, which nobody asked for at all. A row you wrote outranks a row that was
 * calculated for you.
 *
 * The last two are also the fallback: a new account has no history, and an empty screen would look
 * broken rather than new.
 */
@Composable
fun HomeScreen(
    state: BrowseState,
    syncState: SyncState,
    onOpenEntry: (ContinueEntry) -> Unit,
    onForgetEntry: (ContinueEntry) -> Unit,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val empty = state.continueEntries.isEmpty() &&
        state.suggestions.isEmpty() &&
        state.watchlist.isEmpty() &&
        state.recentlyAdded.isEmpty()

    if (empty) {
        EmptyMessage(
            text = when {
                syncState is SyncState.Running -> stringResource(R.string.building_catalog)
                state.loading -> stringResource(R.string.loading)
                else -> stringResource(R.string.home_empty)
            },
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(skin.rowGap),
        contentPadding = PaddingValues(top = skin.rowGap / 2, bottom = skin.rowGap)
    ) {
        if (state.continueEntries.isNotEmpty()) {
            item(key = "continue") {
                ContinueSection(
                    entries = state.continueEntries,
                    onOpen = onOpenEntry,
                    onForget = onForgetEntry
                )
            }
        }
        // Second, under what you were in the middle of: this is the row somebody *chose*, one title
        // at a time, and it has a claim on the top of the page that a row the app guessed does not.
        item(key = "mylist") {
            PosterRow(
                heading = stringResource(R.string.tab_mylist),
                titles = state.watchlist,
                onOpen = onOpenTitle
            )
        }
        items(state.suggestions, key = { "suggestion-${it.seed.id}" }) { suggestion ->
            PosterRow(
                heading = stringResource(R.string.because_you_watched, suggestion.seed.name),
                titles = suggestion.titles,
                onOpen = onOpenTitle
            )
        }
        item(key = "recent") {
            PosterRow(
                heading = stringResource(R.string.row_recent),
                titles = state.recentlyAdded,
                onOpen = onOpenTitle
            )
        }
    }
}

@Composable
private fun ContinueSection(
    entries: List<ContinueEntry>,
    onOpen: (ContinueEntry) -> Unit,
    onForget: (ContinueEntry) -> Unit
) {
    val skin = LocalSkin.current

    Column {
        Label(
            text = stringResource(R.string.row_continue),
            style = skin.sectionTitle,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = skin.screenPadding)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(skin.posterGap),
            contentPadding = PaddingValues(horizontal = skin.screenPadding, vertical = 12.dp)
        ) {
            // One card per title, never per episode: the store already picked the latest of each,
            // and two cards for the same series would be two answers to a question with one.
            items(entries, key = { it.title.id }) { entry ->
                ContinueEntryCard(
                    entry = entry,
                    onClick = { onOpen(entry) },
                    onLongClick = { onForget(entry) }
                )
            }
        }
    }
}

/**
 * A poster that opens the page of the thing you were in the middle of.
 *
 * It used to start playing on the spot, on the grounds that the decision had already been made. It
 * had not: the remote gets sat on, a child hands you the tablet, and a two-hour film starting from
 * a tap that was meant to be a look is a worse mistake than one extra press. The page it opens is
 * the one with the resume button on it, and that button knows where you were.
 *
 * The card still carries the two facts the decision needs: which episode is next, and how much of
 * it is left.
 *
 * Holding it asks whether to take it off the row. Long press because it works the same on a phone
 * and on a remote — holding the centre of the D-pad on the focused card is the same gesture — and
 * because a delete button drawn on every poster would be a delete button waiting to be hit by a
 * thumb that meant to play something.
 */
@Composable
private fun ContinueEntryCard(
    entry: ContinueEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Column(
        modifier = Modifier
            .width(skin.posterWidth)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        ZoomOnFocus(focused, label = "continueScale") {
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
                AsyncImage(
                    model = entry.title.posterUrl,
                    contentDescription = entry.title.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (entry.fraction > 0f) {
                    ProgressStripe(entry.fraction, Modifier.align(Alignment.BottomCenter))
                }
            }
            Spacer(Modifier.height(6.dp))
            Label(
                text = entry.title.name,
                style = skin.caption,
                color = VideoclubColors.TextPrimary,
                maxLines = 1
            )
            ContinueLine(entry)
        }
    }
}

@Composable
private fun ContinueLine(entry: ContinueEntry) {
    val season = entry.season
    val number = entry.episodeNumber
    val episodeText = if (season != null && number != null) {
        if (entry.isNextEpisode) stringResource(R.string.home_next_episode, season, number)
        else stringResource(R.string.home_episode, season, number)
    } else {
        null
    }
    val timeText = entry.minutesLeft?.let { minutes ->
        if (entry.isNextEpisode) stringResource(R.string.home_minutes, minutes)
        else stringResource(R.string.home_minutes_left, minutes)
    }

    val parts = listOfNotNull(episodeText, timeText)
    if (parts.isEmpty()) return
    Label(
        text = parts.joinToString("  ·  "),
        style = LocalSkin.current.caption,
        color = VideoclubColors.TextSecondary,
        maxLines = 1
    )
}
