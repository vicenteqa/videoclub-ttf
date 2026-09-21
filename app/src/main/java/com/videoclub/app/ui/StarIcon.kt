package com.videoclub.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * An empty star, for a title that is not in `Mi lista` yet: the filled one, from the core set, is
 * the title that is.
 *
 * Drawn here for the same reason as [SoccerIcon] — the core icon set does not carry it. This is
 * Material's `star_border`, as its path.
 */
val StarOutlineIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "StarOutline",
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE
    ).addPath(
        pathData = addPathNodes(STAR_OUTLINE_PATH),
        // Tinted by `Icon`, like the others.
        fill = SolidColor(Color.Black)
    ).build()
}

private const val ICON_SIZE = 24f

private const val STAR_OUTLINE_PATH =
    "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 " +
        "9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 " +
        "4.28L12 15.4z"
