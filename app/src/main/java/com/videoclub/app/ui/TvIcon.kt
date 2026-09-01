package com.videoclub.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A television set, for the strip.
 *
 * Drawn here rather than taken from `Icons.Default` because the core icon set does not carry one —
 * it holds about three hundred symbols and a television is not among them. Pulling in
 * `material-icons-extended` for a single glyph would add a couple of thousand of them to the APK,
 * so this is Material's own `tv` outline, as its path, and nothing else.
 *
 * `by lazy` so the vector is built once, on the first strip that draws it, and never again.
 */
val TvIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Tv",
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE
    ).addPath(
        pathData = addPathNodes(TV_PATH),
        // Black, and always: `Icon` tints whatever it is handed, so the colour here is never seen.
        fill = SolidColor(Color.Black)
    ).build()
}

private const val ICON_SIZE = 24f

private const val TV_PATH =
    "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2z" +
        "m0 14H3V5h18v12z"
