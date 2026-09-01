package com.videoclub.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.videoclub.app.Container
import com.videoclub.app.R

/**
 * "Simple" mode: live television only, no video shop, no tabs and no profile picker — the
 * experience SimpleTV used to give, inside this project.
 *
 * The app starts already tuning in and says nothing else: no greeting, no banner. [LiveScreen] is
 * self-contained and knows nothing about this mode, so this is only the wrapper.
 *
 * And it is the only thing that decides what leaving means. With a remote, [LiveScreen] takes two
 * Backs to reach [onExit]: the first opens the channel list and the second leaves. In the normal
 * video shop that is enough, because "leaving" there only changes screen. Here it closes the whole
 * app, so that second Back does not close it: it opens this confirmation, with the same
 * `OverlayMenu` the rest of the app uses for the same thing.
 */
@Composable
fun SimpleRoot(container: Container, onExit: () -> Unit, modifier: Modifier = Modifier) {
    var confirmingExit by remember { mutableStateOf(false) }

    val exitMenu = if (confirmingExit) {
        MenuContent(
            heading = stringResource(R.string.live_exit_title),
            actions = listOf(
                MenuAction(
                    label = stringResource(R.string.live_exit_confirm),
                    primary = true,
                    onSelect = onExit
                ),
                MenuAction(
                    label = stringResource(R.string.live_exit_cancel),
                    onSelect = { confirmingExit = false }
                )
            )
        )
    } else {
        null
    }

    OverlayMenu(
        menu = exitMenu,
        onDismiss = { confirmingExit = false },
        modifier = modifier.fillMaxSize()
    ) {
        LiveScreen(
            container = container,
            onLeave = { confirmingExit = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}
