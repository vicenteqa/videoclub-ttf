package com.videoclub.app.ui

import android.graphics.Color
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videoclub.app.player.LivePlayer

/**
 * The picture, and nothing else.
 *
 * [PlayerView] is used purely as a surface host — its controller is off and never inflated, which is
 * the opposite of the videoclub's player, where the controller *is* the screen. A channel has no
 * seek bar to drive and no tracks to pick: everything a viewer does here is a key this app answers
 * itself.
 *
 * Doing it this way rather than with a bare `SurfaceView` buys the two things that are genuinely
 * fiddly to get right: aspect-ratio handling when a channel changes resolution mid-stream, and a
 * shutter that stays black between feeds instead of showing the last frame of the previous one.
 */
// `UnstableApi` is a Java annotation, so Kotlin's own `@OptIn` does not apply to it.
@OptIn(UnstableApi::class)
@Composable
fun LiveSurface(player: LivePlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                setKeepContentOnPlayerReset(false)
                keepScreenOn = true

                // The surface must never hold focus. A focused Android view inside `AndroidView`
                // swallows the D-pad before Compose sees it, and every key on this screen is
                // handled by the Box wrapping this one.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

                this.player = player.exoPlayer
            }
        },
        update = { view -> view.player = player.exoPlayer },
        onRelease = { view -> view.player = null }
    )
}
