package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoclub.app.R
import com.videoclub.app.data.ContinueEntry
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.HomeRow
import com.videoclub.app.data.Profile
import com.videoclub.app.data.SyncState
import com.videoclub.app.data.Title
import kotlinx.coroutines.launch
import com.videoclub.app.data.SyncProgress
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator

/**
 * The strip across the top of everything: where you are, who you are, and what the sync is doing.
 *
 * It lives above the whole app rather than inside the browsing screen, and it did not always. It
 * used to belong to [BrowseScreen], which meant that opening a film took it away — the tabs and the
 * profile circle vanished for as long as you were reading about the film, and came back when you
 * left. Nothing else in the app moves like that, and there is no reason a detail page should be the
 * one screen with no way back to `Series` except the Back button.
 *
 * The player is the exception, and the settings are the other: one is a film and wants the whole
 * panel, the other is a form and should not offer five ways to abandon it mid-edit.
 *
 * [autoFocus] is only ever true on the browsing screens. A television arriving at a film wants the
 * cursor on `Reproducir`, not up here.
 */
@Composable
fun TopStrip(
    tab: Tab,
    profile: DeviceProfile,
    /** Whose history is on screen. Named apart from [profile], which is what kind of device this is. */
    viewer: Profile,
    syncState: SyncState,
    onSelectTab: (Tab) -> Unit,
    onSwitchViewer: () -> Unit,
    onRetry: () -> Unit,
    autoFocus: Boolean = false
) {
    // While the videoclub is being built, every chip that leads into the catalogue leads to the
    // same progress ring — so they are turned off rather than left to be pressed. That includes the
    // magnifier: there is nothing to search yet. What is left is the television, which needs no
    // catalogue, and the profile circle, which is not a destination.
    //
    // The daily refresh counts as being built for the same reason the first one does: the shelves
    // reorder themselves as the batches land, and aiming at one that is about to be pushed down is
    // not browsing.
    val building = syncState is SyncState.Running
    val tabs = listOf(
        TabEntry(
            tab = Tab.Home,
            label = stringResource(R.string.tab_home),
            icon = Icons.Default.Home,
            enabled = !building
        ),
        TabEntry(Tab.Movies, stringResource(R.string.tab_movies), enabled = !building),
        TabEntry(Tab.Series, stringResource(R.string.tab_series), enabled = !building),
        TabEntry(Tab.MyList, stringResource(R.string.tab_mylist), enabled = !building),
        // Pinned, next to the magnifier: the two chips up here that are not shelves of the
        // videoclub. Everything to the left of them is somewhere in the catalogue; these two leave
        // it. Pinning also means neither can be scrolled out of reach on a phone held upright,
        // which is exactly where a strip of six destinations runs out of room.
        TabEntry(
            tab = Tab.Live,
            label = stringResource(R.string.tab_live),
            icon = TvIcon,
            pinned = true
        ),
        TabEntry(
            tab = Tab.Search,
            label = stringResource(R.string.tab_search),
            icon = Icons.Default.Search,
            pinned = true,
            enabled = !building
        )
    )

    Column {
        Spacer(Modifier.height(if (profile == DeviceProfile.Tv) 24.dp else 12.dp))
        // With the billboard gone there is no play button to land on, so on a television the strip
        // itself is what holds the cursor when a browsing screen appears.
        TabBar(
            tabs = tabs,
            selected = tab,
            onSelect = onSelectTab,
            autoFocus = autoFocus,
            trailing = {
                ProfileChip(
                    profile = viewer,
                    onClick = onSwitchViewer
                )
            }
        )
        SyncBanner(syncState, onRetry)
    }
}

/**
 * One of the five bodies the tab strip switches between.
 *
 * The rows of the Films and Series tabs are the supplier's categories, merged and reordered by
 * [RowPlan] rather than taken as they come.
 */
@Composable
fun BrowseScreen(
    tab: Tab,
    state: BrowseState,
    syncState: SyncState,
    profile: DeviceProfile,
    query: String,
    results: List<Title>,
    onQueryChange: (String) -> Unit,
    onSelectTab: (Tab) -> Unit,
    onOpenTitle: (Title) -> Unit,
    onOpenEntry: (ContinueEntry) -> Unit,
    onForgetEntry: (ContinueEntry) -> Unit,
    onOpenRow: (HomeRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val skin = LocalSkin.current

    Column(modifier = modifier.fillMaxSize().background(VideoclubColors.Surface)) {
        // While the videoclub is being built there is nothing worth navigating: rows appear one at
        // a time under whatever the cursor was on, and aiming at a shelf that is about to be pushed
        // down by the one arriving above it is not browsing. So the whole body is the progress
        // instead — and the strip above it stays, which leaves `TV` reachable, since live
        // television needs no catalogue at all.
        //
        // Every sync, not only the first. The daily refresh moves the shelves exactly as much as
        // the first build does, and a line of small print under the tabs while the rows reorder
        // themselves underneath is the worst of both: it neither lets you browse nor says to stop.
        val building = syncState as? SyncState.Running
        if (building != null) {
            BuildingCatalog(building.progress)
            return@Column
        }

        Box(Modifier.fillMaxSize().tabSwipe(current = tab, onSelect = onSelectTab)) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    state = state,
                    syncState = syncState,
                    onOpenEntry = onOpenEntry,
                    onForgetEntry = onForgetEntry,
                    onOpenTitle = onOpenTitle
                )

                Tab.Movies, Tab.Series ->
                    CategoryRows(state, syncState, onOpenTitle, onOpenRow)

                Tab.Search -> SearchScreen(
                    query = query,
                    results = results,
                    onQueryChange = onQueryChange,
                    onOpenTitle = onOpenTitle,
                    autoFocus = profile != DeviceProfile.Tv
                )

                Tab.MyList ->
                    if (state.watchlist.isEmpty()) EmptyMessage(stringResource(R.string.mylist_empty))
                    else TitleGrid(
                        titles = state.watchlist,
                        onOpenTitle = onOpenTitle,
                        showKind = true
                    )

                // Unreachable: the television is not a body this strip switches between, it is a
                // screen of its own that the strip happens to be the way into. Nothing ever puts a
                // `Browse(Live)` on the stack. Answered rather than thrown, since an exhaustive
                // `when` is the only thing that would notice if that ever changed.
                Tab.Live -> Unit
            }
        }
        Spacer(Modifier.height(skin.rowGap / 2))
    }
}

/**
 * What the app shows while the videoclub is being built, for the two or three minutes it takes.
 *
 * The first time it is opened, and once a day after that, when [CatalogRepository.refreshIfStale]
 * decides the catalogue has gone stale.
 *
 * A determinate ring rather than a spinner wherever the total is known, which is from the first
 * batch onwards: "it is working" is worth much less than "it is two thirds of the way through", and
 * on a fresh install this is the only screen there is for long enough that the difference matters.
 *
 * The last line is not filler. The television section needs no catalogue at all, so it is the one
 * thing the app can actually do while this runs — and somebody staring at a progress ring is
 * exactly the person who would like to know that.
 */
@Composable
private fun BuildingCatalog(progress: SyncProgress, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val percent = if (progress.total > 0) progress.done * 100 / progress.total else null

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(skin.screenPadding).widthIn(max = 460.dp)
        ) {
            if (percent == null) {
                CircularProgressIndicator(
                    color = VideoclubColors.Accent,
                    trackColor = VideoclubColors.SurfaceElevated,
                    modifier = Modifier.size(RING_SIZE)
                )
            } else {
                CircularProgressIndicator(
                    progress = { percent / 100f },
                    color = VideoclubColors.Accent,
                    trackColor = VideoclubColors.SurfaceElevated,
                    modifier = Modifier.size(RING_SIZE)
                )
            }

            Spacer(Modifier.height(28.dp))
            Label(
                text = stringResource(R.string.building_catalog),
                style = skin.sectionTitle,
                maxLines = 1
            )
            percent?.let {
                Spacer(Modifier.height(8.dp))
                Label(
                    text = stringResource(R.string.building_catalog_percent, it),
                    style = skin.body,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(24.dp))
            Label(
                text = stringResource(R.string.building_catalog_note),
                style = skin.caption,
                color = VideoclubColors.TextSecondary,
                maxLines = 3
            )
        }
    }
}

private val RING_SIZE = 64.dp

/**
 * Sideways drag changes tab, but only where nothing else wanted the drag.
 *
 * A poster row is a horizontal list and takes the gesture itself, which is right: dragging across
 * `TERROR` must move the posters of `TERROR`. Everything else on the page — the headings, the bands
 * between the rows, the whole of a grid, the empty half of a short page — leaves the horizontal
 * drag unclaimed, and that is what lands here. This works because a child sees a pointer event
 * before its parent does: by the time the drag reaches this modifier, any list that wanted it has
 * already consumed it and the gesture is cancelled before it starts.
 *
 * The order is [SWIPE_TABS], which is also the order of the chips in the strip, so the movement on
 * screen matches the movement of the highlight above it. It deliberately does not wrap around:
 * a strip has two ends, and arriving back at `Inicio` from the magnifier reads as a bug.
 */
/**
 * The tabs a sideways drag walks through, in strip order.
 *
 * [Tab.Live] is deliberately left out. Everything else here is a shelf that costs a database read
 * and is undone by swiping back; the television costs a hardware decoder and one of the account's
 * connections, and a channel that started playing because a thumb travelled too far past `Mi lista`
 * is not a mistake the same gesture can take back. It is a place to go on purpose, so it is reached
 * by pressing its chip and no other way.
 */
private val SWIPE_TABS: List<Tab> = Tab.entries.filterNot { it == Tab.Live }

@Composable
private fun Modifier.tabSwipe(current: Tab, onSelect: (Tab) -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { SWIPE_THRESHOLD.toPx() }
    return pointerInput(current) {
        var travelled = 0f
        detectHorizontalDragGestures(
            onDragStart = { travelled = 0f },
            onDragEnd = {
                // Distance rather than velocity: a slow deliberate drag across the screen means the
                // same thing as a flick, and half the point of this is not having to aim at a chip.
                val step = when {
                    travelled <= -threshold -> 1
                    travelled >= threshold -> -1
                    else -> 0
                }
                val at = SWIPE_TABS.indexOf(current)
                if (step != 0 && at >= 0) {
                    SWIPE_TABS.getOrNull(at + step)?.let(onSelect)
                }
            }
        ) { _, delta -> travelled += delta }
    }
}

/** Far enough that no tap with a wobble in it ever changes the page. */
private val SWIPE_THRESHOLD = 72.dp

@Composable
private fun CategoryRows(
    state: BrowseState,
    syncState: SyncState,
    onOpenTitle: (Title) -> Unit,
    onOpenRow: (HomeRow) -> Unit
) {
    val skin = LocalSkin.current

    if (state.rows.isEmpty() && state.continueWatching.isEmpty()) {
        // The first run reaches here with a sync in flight: say what is happening rather than
        // announcing an empty catalogue that is in the middle of arriving.
        EmptyMessage(
            when {
                syncState is SyncState.Running -> stringResource(R.string.building_catalog)
                state.loading -> stringResource(R.string.loading)
                else -> stringResource(R.string.catalog_empty)
            }
        )
        return
    }

    val resume = if (state.continueWatching.isEmpty()) 0 else 1
    val shelves = state.rows.size + resume
    val page = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cursor = remember(shelves) { List(shelves) { FocusRequester() } }

    /**
     * How far below the top of the page the shelf under the cursor sits, always.
     *
     * A fixed place is the whole point. Scrolling the shelf to the very top — which is what this
     * did — meant every press moved the page by a different amount depending on where things
     * happened to be, and the page appeared to lurch at random. Landing every shelf in the same
     * spot makes one press mean one shelf, and leaves the bottom of the shelf above showing, which
     * is what tells you which way you just came from.
     */
    val perch = with(LocalDensity.current) { (skin.rowGap * 2).roundToPx() }

    /**
     * Up and down between shelves, moved by hand rather than left to the focus search.
     *
     * The focus search can only find something that exists, and in a lazy list the shelf above is
     * usually not on screen and therefore not composed. What it finds instead is the tab strip,
     * which is how pressing `up` in the middle of the page jumped straight to the top of it.
     *
     * So the shelf is scrolled to first — that is what brings it into being — and only then handed
     * the cursor. Asking a whole shelf for the cursor rather than a poster means the row decides
     * which poster gets it, which is the row's business and not this function's.
     *
     * Off either end it returns false and the search takes over, so `up` from the first shelf still
     * reaches the tabs, which is exactly where it should go.
     */
    fun step(from: Int, delta: Int): Boolean {
        val target = from + delta
        if (target !in 0 until shelves) return false
        scope.launch {
            page.animateScrollToItem(target, -perch)
            // A shelf that has just been scrolled into existence is not placed until the next
            // frame, and asking an unplaced node for the cursor throws. Two frames is plenty.
            repeat(3) { attempt ->
                if (runCatching { cursor[target].requestFocus() }.isSuccess) return@launch
                if (attempt < 2) withFrameNanos { }
            }
        }
        return true
    }

    fun shelfKeys(index: Int) = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionUp -> step(index, -1)
                Key.DirectionDown -> step(index, 1)
                else -> false
            }
        }
    }

    LazyColumn(
        state = page,
        verticalArrangement = Arrangement.spacedBy(skin.rowGap),
        contentPadding = PaddingValues(bottom = skin.rowGap)
    ) {
        if (state.continueWatching.isNotEmpty()) {
            item(key = "continue") {
                ContinueRow(
                    heading = stringResource(R.string.row_continue),
                    entries = state.continueWatching,
                    onOpen = onOpenTitle,
                    modifier = shelfKeys(0),
                    focus = cursor[0]
                )
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.heading }) { index, row ->
            PosterRow(
                heading = row.heading,
                titles = row.titles,
                onOpen = onOpenTitle,
                // `Novedades` is the app's own row and has no category behind it to open.
                onHeadingClick = if (row.categoryIds.isEmpty()) null else ({ onOpenRow(row) }),
                modifier = shelfKeys(index + resume),
                focus = cursor[index + resume]
            )
        }
    }
}

/** `2000  ·  8,2  ·  4K` — the three facts that fit on one line and are worth the space. */
@Composable
fun MetaLine(title: Title, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(
        title.year?.toString(),
        title.rating?.let { String.format("%.1f", it) },
        title.bestQuality?.takeIf { it.notable }?.label
    )
    if (parts.isEmpty()) return
    Label(
        text = parts.joinToString("  ·  "),
        style = LocalSkin.current.body,
        color = VideoclubColors.TextSecondary,
        maxLines = 1,
        modifier = modifier
    )
}

/** One line under the tabs, and only when there is something to say. */
@Composable
private fun SyncBanner(state: SyncState, onRetry: () -> Unit) {
    val skin = LocalSkin.current
    when (state) {
        // A build says all of this in the middle of the screen, at a size somebody across a room
        // can read. Repeating it up here would be the same sentence twice.
        is SyncState.Running -> Spacer(Modifier.height(6.dp))

        is SyncState.Failed -> Row(
            modifier = Modifier.padding(horizontal = skin.screenPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Label(
                text = state.message,
                style = skin.caption,
                color = VideoclubColors.Accent,
                maxLines = 1
            )
            ActionButton(text = stringResource(R.string.retry), onClick = onRetry)
        }

        SyncState.Idle, SyncState.Ready -> Spacer(Modifier.height(6.dp))
    }
}

