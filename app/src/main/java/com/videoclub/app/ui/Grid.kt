package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoclub.app.R
import com.videoclub.app.data.Title

/**
 * A wall of posters, as many across as the window will take.
 *
 * `Adaptive` rather than a fixed count on purpose: the same code then gives three across on a phone
 * held upright, five on a tablet and eight on a television, without anybody having to decide which
 * device is which.
 */
@Composable
fun TitleGrid(
    titles: List<Title>,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier,
    /** True where films and series appear side by side, so the series say so. */
    showKind: Boolean = false
) {
    val skin = LocalSkin.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = skin.posterWidth + skin.posterGap),
        horizontalArrangement = Arrangement.spacedBy(skin.posterGap),
        verticalArrangement = Arrangement.spacedBy(skin.posterGap),
        contentPadding = PaddingValues(skin.screenPadding),
        modifier = modifier.fillMaxSize()
    ) {
        items(titles, key = Title::id) { title ->
            PosterCard(title = title, onClick = { onOpenTitle(title) }, showKind = showKind)
        }
    }
}

/** One supplier category, opened from a row heading. */
@Composable
fun GridScreen(
    heading: String,
    titles: List<Title>,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current
    Column(modifier = modifier.fillMaxSize().background(VideoclubColors.Surface)) {
        Spacer(Modifier.height(skin.screenPadding))
        Label(
            text = heading,
            style = skin.sectionTitle,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = skin.screenPadding)
        )
        if (titles.isEmpty()) EmptyMessage(stringResource(R.string.loading))
        else TitleGrid(titles = titles, onOpenTitle = onOpenTitle)
    }
}
