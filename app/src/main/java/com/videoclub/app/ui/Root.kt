package com.videoclub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.videoclub.app.Container
import com.videoclub.app.R
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.videoclub.app.Startup
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.Profile

/**
 * The whole app, one screen at a time.
 *
 * Navigation is a list in the view model rather than a navigation library: four destinations, no
 * deep links, no arguments that will not fit in a data class. Back is the only transition that ever
 * needed thought, and it is one call.
 */
@Composable
fun VideoclubRoot(
    container: Container,
    viewModel: MainViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val screen by viewModel.screen.collectAsState()

    BackHandler { if (!viewModel.back()) onExit() }

    /**
     * Everything except the player keeps clear of the system bars.
     *
     * `targetSdk 36` draws edge to edge and offers no way to decline, so on a phone held upright the
     * tab strip lands underneath the clock and the battery unless the app says otherwise. `safeDrawing`
     * rather than `systemBars` because it also covers the camera cutout and, when the search keyboard
     * is up, the keyboard. On a television every one of these insets is zero, so nothing moves.
     */
    val inset = modifier.windowInsetsPadding(WindowInsets.safeDrawing)

    // Nothing is drawn until the app knows whose television this is. There is no account compiled
    // into the APK any more, so before the hosted document arrives there is no shop to show and no
    // household to attribute a viewing to — a spinner is the honest answer, and the wait is the one
    // paid at startup rather than halfway through an evening.
    val startup by viewModel.startup.collectAsState()
    when (startup) {
        Startup.Checking -> {
            Box(modifier = inset.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        // An account that never arrives is a state to say out loud rather than paper over with an
        // empty shop. The container keeps asking in the background, so a box that booted before its
        // network did repairs itself with nobody present.
        Startup.NoCredentials -> {
            Box(modifier = inset.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_provider),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            return
        }

        Startup.Ready -> Unit
    }

    // Nothing below this line belongs to anybody until the app knows who is watching: the rows are
    // the same for both of them, but the progress written while they are on screen is not.
    val viewer = viewModel.viewer.collectAsState().value
    val people by viewModel.profiles.collectAsState()
    if (viewer == null) {
        ProfileScreen(
            people = people,
            suggested = viewModel.suggestedViewer,
            onChoose = viewModel::chooseViewer,
            autoFocus = viewModel.deviceProfile == DeviceProfile.Tv,
            modifier = inset
        )
        return
    }

    // Every held poster and every held `Seguir viendo` card asks its question here, at the top of
    // the app, rather than inside the card that was held: a card is recycled the moment its row
    // scrolls, and it has neither a whole screen to darken nor any way to keep the remote off the
    // row behind it. Only one of the two can be open at a time, since both need a held card.
    val menu by viewModel.menu.collectAsState()
    val pendingForget by viewModel.forget.collectAsState()

    val posterMenu = menu?.let { open ->
        MenuContent(
            heading = open.title.name,
            actions = listOf(
                MenuAction(
                    label = stringResource(
                        if (open.inList) R.string.remove_from_list else R.string.add_to_list_long
                    ),
                    primary = true,
                    onSelect = viewModel::toggleMenuWatchlist
                ),
                MenuAction(label = stringResource(R.string.open_title)) {
                    viewModel.closeMenu()
                    viewModel.openTitle(open.title.id)
                },
                MenuAction(label = stringResource(R.string.cancel), onSelect = viewModel::closeMenu)
            )
        )
    }

    val forgetMenu = pendingForget?.let { entry ->
        MenuContent(
            heading = stringResource(R.string.continue_forget, entry.title.name),
            note = stringResource(R.string.continue_forget_note),
            actions = listOf(
                MenuAction(
                    label = stringResource(R.string.remove),
                    primary = true,
                    onSelect = viewModel::confirmForget
                ),
                MenuAction(
                    label = stringResource(R.string.cancel),
                    onSelect = viewModel::cancelForget
                )
            )
        )
    }

    OverlayMenu(
        menu = posterMenu ?: forgetMenu,
        onDismiss = {
            if (posterMenu != null) viewModel.closeMenu() else viewModel.cancelForget()
        }
    ) {
        VideoclubScreen(
            container, viewModel, screen, viewer, people, inset, modifier,
            onCheckUpdate = viewModel::checkForUpdate
        )
    }
}

/**
 * Whichever screen is on top, and the strip over the three that keep it.
 *
 * Split out from [VideoclubRoot] only so that the menu above wraps it as a single call. Nothing
 * here decides anything the root did not already decide.
 */
@Composable
private fun VideoclubScreen(
    container: Container,
    viewModel: MainViewModel,
    screen: Screen,
    viewer: Profile,
    people: List<Profile>,
    inset: Modifier,
    modifier: Modifier,
    onCheckUpdate: () -> Unit
) {
    when (val current = screen) {
        // The three screens that keep the strip: browsing, a shelf, and a film. Whatever is under
        // it fills what is left, which is what `weight` says here.
        is Screen.Browse, is Screen.Grid, is Screen.Detail -> Column(
            modifier = inset.fillMaxSize().background(VideoclubColors.Surface)
        ) {
            val tab by viewModel.tab.collectAsState()
            val syncState by viewModel.syncState.collectAsState()

            TopStrip(
                tab = tab,
                profile = viewModel.deviceProfile,
                viewer = viewer,
                syncState = syncState,
                onSelectTab = viewModel::selectTab,
                onSwitchViewer = viewModel::switchViewer,
                onRetry = viewModel::retrySync,
                onCheckUpdate = onCheckUpdate,
                autoFocus = viewModel.deviceProfile == DeviceProfile.Tv && current is Screen.Browse
            )

            when (current) {
                is Screen.Browse -> {
                    val browse by viewModel.browse.collectAsState()
                    val query by viewModel.query.collectAsState()
                    val results by viewModel.results.collectAsState()

                    BrowseScreen(
                        tab = tab,
                        state = browse,
                        syncState = syncState,
                        profile = viewModel.deviceProfile,
                        query = query,
                        results = results,
                        onQueryChange = viewModel::setQuery,
                        onSelectTab = viewModel::selectTab,
                        onOpenTitle = { viewModel.openTitle(it.id) },
                        onOpenEntry = { entry ->
                            viewModel.openTitle(entry.title.id, entry.episodeKey.takeIf { it > 0 })
                        },
                        onForgetEntry = viewModel::askForget,
                        onOpenRow = viewModel::openRow,
                        modifier = Modifier.weight(1f)
                    )
                }

                is Screen.Grid -> {
                    val titles by viewModel.grid.collectAsState()
                    GridScreen(
                        heading = current.heading,
                        titles = titles,
                        onOpenTitle = { viewModel.openTitle(it.id) },
                        modifier = Modifier.weight(1f)
                    )
                }

                is Screen.Detail -> {
                    val state by viewModel.detail.collectAsState()
                    val detail = state
                    if (detail == null || detail.title.id != current.titleId) {
                        EmptyMessage(
                            text = stringResource(R.string.loading),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        DetailScreen(
                            state = detail,
                            profile = viewModel.deviceProfile,
                            episodeKey = current.episodeKey,
                            onPlay = viewModel::playCurrent,
                            onPlayEpisode = { viewModel.playEpisode(it) },
                            onSelectSource = viewModel::selectSource,
                            onToggleWatchlist = viewModel::toggleWatchlist,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                else -> Unit
            }
        }

        // Full-screen, like the player and for the same reason: it is a picture. No strip, no
        // insets, and Back is answered by the screen itself until it runs out of layers.
        Screen.Live -> LiveScreen(
            container = container,
            onLeave = { viewModel.back() },
            modifier = modifier
        )

        is Screen.Play -> PlayerScreen(
            request = current.request,
            container = container,
            onSaveProgress = { position, duration, tracks ->
                viewModel.savePosition(current.request, position, duration, tracks)
            },
            modifier = modifier
        )
    }
}
