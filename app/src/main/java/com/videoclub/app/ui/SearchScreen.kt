package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.videoclub.app.R
import com.videoclub.app.data.Title

/**
 * A box and a grid of what it found.
 *
 * The search runs against the folded names in SQLite, so accents and case do not matter and the
 * answer comes back before the next keystroke. The list therefore updates as you type rather than
 * waiting for a button that would only be there for the television.
 *
 * [autoFocus] opens the keyboard the moment the magnifier is tapped, and is why the tab is one tap
 * rather than two. It is for handsets only: on a television the keyboard is a grid of letters that
 * covers the screen, and throwing it up before the viewer has asked for it takes the page away from
 * somebody who may only have wanted to look at the shelves.
 */
@Composable
fun SearchScreen(
    query: String,
    results: List<Title>,
    onQueryChange: (String) -> Unit,
    onOpenTitle: (Title) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current

    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            autoFocus = autoFocus,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = skin.screenPadding)
        )
        when {
            query.isBlank() -> EmptyMessage(stringResource(R.string.search_prompt))
            results.isEmpty() -> EmptyMessage(stringResource(R.string.search_empty, query))
            // One box searches the whole catalogue, so a result can be either thing.
            else -> TitleGrid(titles = results, onOpenTitle = onOpenTitle, showKind = true)
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val focusRequester = remember { FocusRequester() }

    // Once, on the way in. Keyed on nothing so that coming back from a film does not shove the
    // keyboard over the results the viewer went to fetch.
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = skin.body.copy(color = VideoclubColors.TextPrimary),
        cursorBrush = SolidColor(VideoclubColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(VideoclubColors.SurfaceElevated)
            .border(
                width = 2.dp,
                color = if (focused) VideoclubColors.TextPrimary else VideoclubColors.SurfaceElevated,
                shape = shape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { field ->
            if (query.isEmpty()) {
                Label(
                    text = stringResource(R.string.search_hint),
                    style = skin.body,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 1
                )
            }
            field()
        }
    )
}
