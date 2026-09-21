package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.videoclub.app.R
import com.videoclub.app.data.FootballMatch
import com.videoclub.app.data.FootballSeason
import com.videoclub.app.data.Matchday
import java.util.Calendar

/**
 * The `Fútbol` tab: one tile per competition, each opening its matchdays.
 *
 * Two tiles today — LaLiga and the Champions — each with its own logo, because that is how anybody
 * already tells them apart. With a single competition published there is nothing to choose, and
 * the tab goes straight to its matchdays.
 */
@Composable
fun FootballScreen(
    seasons: List<FootballSeason>,
    onOpenCompetition: (String) -> Unit,
    onOpenTitle: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    when (seasons.size) {
        0 -> EmptyMessage(stringResource(R.string.football_empty), modifier = modifier)
        1 -> CompetitionScreen(seasons.single(), onOpenTitle, modifier)
        else -> CompetitionTiles(seasons, onOpenCompetition, modifier)
    }
}

@Composable
private fun CompetitionTiles(
    seasons: List<FootballSeason>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(skin.posterGap * 3, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(skin.screenPadding)
            .focusGroup()
    ) {
        seasons.forEach { season ->
            CompetitionTile(season = season, onClick = { onOpen(season.id) })
        }
    }
}

/** A competition's logo on a square, and its name and season underneath. */
@Composable
private fun CompetitionTile(season: FootballSeason, onClick: () -> Unit) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(skin.posterHeight)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        ZoomOnFocus(focused, label = "competitionScale") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(VideoclubColors.SurfaceElevated)
                    .then(
                        if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, shape)
                        else Modifier
                    )
                    .padding(skin.posterHeight * 0.18f)
            ) {
                if (season.logo != null) {
                    AsyncImage(
                        model = season.logo,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Label(
                text = "${season.name} ${season.season}",
                style = skin.sectionTitle,
                color = if (focused) VideoclubColors.TextPrimary else VideoclubColors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * One competition, one matchday per shelf, newest first.
 *
 * The supplier's own shelf for this was sixty copies of the same picture with a name underneath,
 * in upload order. Here a matchday is a row and a match is a card that says who played and on what
 * day — never how it ended. The VPS has already put each row's biggest match first. A card opens
 * the match's page like any film's, which is where its quality chooser and its resume point live.
 */
@Composable
fun CompetitionScreen(
    season: FootballSeason,
    onOpenTitle: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    val shelves = remember(season) {
        season.matchdays.reversed() +
            listOfNotNull(season.unassigned.takeIf { it.isNotEmpty() }?.let { Matchday(null, it) })
    }
    val cursor = rememberShelfCursor(shelves.size)

    LazyColumn(
        state = cursor.page,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(skin.rowGap),
        contentPadding = PaddingValues(top = skin.rowGap / 2, bottom = skin.rowGap)
    ) {
        itemsIndexed(
            shelves,
            key = { _, day -> "${season.id}-${day.number ?: "other"}" },
            contentType = { _, _ -> "matchday" }
        ) { index, day ->
            MatchRow(
                heading = matchdayLabel(day),
                matches = day.matches,
                onOpen = onOpenTitle,
                modifier = cursor.keys(index),
                focus = cursor.requester(index)
            )
        }
    }
}

/** `Jornada 7`, `Octavos · ida`, or — for what the VPS could not place — `Otros partidos`. */
@Composable
internal fun matchdayLabel(day: Matchday): String = when {
    day.name != null -> day.name
    day.number != null -> stringResource(R.string.football_matchday, day.number)
    else -> stringResource(R.string.football_other)
}

@Composable
private fun MatchRow(
    heading: String,
    matches: List<FootballMatch>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null
) {
    val skin = LocalSkin.current
    // Where the cursor was in this row, so the page's own jump between rows lands back on it. See
    // `PosterRow`, which this follows exactly.
    var current by rememberSaveable(matches.size) { mutableIntStateOf(0) }

    Column(modifier = modifier) {
        Label(
            text = heading,
            style = skin.sectionTitle,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = skin.screenPadding)
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(skin.posterGap),
            contentPadding = PaddingValues(horizontal = skin.screenPadding, vertical = 12.dp)
        ) {
            itemsIndexed(matches, key = { _, match -> match.titleId ?: match.streamIds.first() }) { index, match ->
                MatchCard(
                    match = match,
                    onClick = { match.titleId?.let(onOpen) },
                    onFocused = { current = index },
                    modifier = if (focus != null && index == current) Modifier.focusRequester(focus)
                    else Modifier
                )
            }
        }
    }
}

/**
 * Who played. A wide card, the shape of the picture it leads to, with the two crests and names
 * where a poster would be — the supplier's picture is the same for every match, so it says nothing
 * and is left out. A crest the VPS has not fetched yet leaves the name on its own.
 *
 * Nothing underneath: the row already says which matchday it is, and the day and hour are on the
 * match's own page for whoever wants them.
 */
@Composable
private fun MatchCard(
    match: FootballMatch,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.cornerRadius)

    Column(
        modifier = modifier
            .width(skin.posterHeight)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
    ) {
        ZoomOnFocus(focused, label = "matchScale") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(VideoclubColors.SurfaceElevated)
                    .then(
                        if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, shape)
                        else Modifier
                    )
                    .padding(horizontal = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamSide(match.home, match.homeCrest, Modifier.weight(1f))
                    Label(
                        text = "–",
                        style = skin.caption,
                        color = VideoclubColors.TextSecondary,
                        maxLines = 1
                    )
                    TeamSide(match.away, match.awayCrest, Modifier.weight(1f))
                }
            }
        }
    }
}

/** A crest over a name. Also used by the match's own page, larger. */
@Composable
internal fun TeamSide(
    name: String,
    crest: String?,
    modifier: Modifier = Modifier,
    crestSize: Dp = LocalSkin.current.posterWidth * CREST_FRACTION,
    style: TextStyle = LocalSkin.current.caption.copy(fontWeight = FontWeight.SemiBold)
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        if (crest != null) {
            AsyncImage(
                model = crest,
                // The name is written right underneath: the crest says nothing more to a reader.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(crestSize)
            )
        }
        Text(
            text = name,
            style = style,
            color = VideoclubColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** A third of a poster's width: big enough to know the club from across a room, on any device. */
private const val CREST_FRACTION = 0.34f

/**
 * `dom 20 sep · 21:00`, from the fixture list's `2026-09-20` and `21:00`.
 *
 * By hand rather than with `java.time`, which this app's oldest boxes (API 25) do not have, and in
 * Spanish whatever the device is set to, like every other word the app shows.
 */
internal fun kickoff(match: FootballMatch): String {
    val parts = match.date.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return match.time.orEmpty()
    val calendar = Calendar.getInstance().apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2])
    }
    val day = "${WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]} ${parts[2]} ${MONTHS[parts[1] - 1]}"
    return if (match.time.isNullOrBlank()) day else "$day · ${match.time}"
}

private val WEEKDAYS = listOf("dom", "lun", "mar", "mié", "jue", "vie", "sáb")
private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
