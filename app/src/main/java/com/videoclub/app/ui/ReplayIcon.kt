package com.videoclub.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A circular arrow: play from the beginning.
 *
 * Drawn here for the same reason as [TvIcon] — the core icon set does not carry it. This is
 * Material's `replay`, as its path.
 */
val ReplayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Replay",
        defaultWidth = ICON_SIZE.dp,
        defaultHeight = ICON_SIZE.dp,
        viewportWidth = ICON_SIZE,
        viewportHeight = ICON_SIZE
    ).addPath(
        pathData = addPathNodes(REPLAY_PATH),
        // Tinted by `Icon`, like the television.
        fill = SolidColor(Color.Black)
    ).build()
}

private const val ICON_SIZE = 24f

private const val REPLAY_PATH =
    "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z"
