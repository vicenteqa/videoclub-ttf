package com.videoclub.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * An arrow pointing up: there is a newer version waiting.
 *
 * Drawn here for the same reason as [TvIcon] — the core icon set does not carry it, and the extended
 * one is thousands of glyphs for the sake of one. This is Material's `arrow_upward`, as its path.
 */
val UpdateIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Update",
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE
    ).addPath(
        pathData = addPathNodes(ARROW_UP_PATH),
        // Tinted by `Icon`, like the television.
        fill = SolidColor(Color.Black)
    ).build()
}

private const val ICON_SIZE = 24f

private const val ARROW_UP_PATH = "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"
