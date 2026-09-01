package com.videoclub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** One row of an [OverlayMenu]: what it says, and what it does. */
@Immutable
class MenuAction(
    val label: String,
    /** The one the cursor starts on and the one drawn in the app's red. */
    val primary: Boolean = false,
    val onSelect: () -> Unit
)

/** What a menu is about. Null means no menu is open. */
@Immutable
class MenuContent(
    val heading: String,
    val note: String? = null,
    val actions: List<MenuAction>
)

/**
 * A menu drawn over the screen, which never takes the cursor off whatever opened it.
 *
 * ## Why this is not a `Dialog`
 *
 * It was one, and on the television it was unusable: the panel appeared and went away again, and
 * nothing on it could be reached. A `Dialog` is a **second Android window**. Opening one hands the
 * key focus across from the activity in the middle of a key press — the press is still physically
 * down, since a long press fires while the button is held — and the two windows then disagree about
 * who owns the release. On a phone none of this shows, because a finger does not hold a button down
 * across the appearance of the thing it opened.
 *
 * So there is no second window. The panel is drawn in the same composition, and the keys are taken
 * with [Modifier.onPreviewKeyEvent] on an ancestor of everything on screen. A preview travels from
 * the root down to whatever holds the focus, so this sees every key *before* the poster underneath
 * does and can simply swallow it.
 *
 * ## Nothing moves the focus
 *
 * The cursor inside the menu is an index this composable owns, exactly like the channel list in the
 * television section, and for the same reason. The poster that opened the menu keeps the real focus
 * the whole time — which costs nothing, is visible through the scrim as the thing the menu is about,
 * and means there is no focus to give back when the menu closes. Restoring focus to a card inside a
 * lazy row that may have been recycled is the bug this design does not have.
 *
 * ## The release of the press that opened it
 *
 * A long press fires while the button is down, so the very next event this sees is the `KeyUp` of
 * the press that asked for the menu. Acting on it would fire the first item instantly — the menu
 * would appear and vanish, which is precisely what the television did. So the confirm key is dead
 * until it has been released once ([armed]).
 */
@Composable
fun OverlayMenu(
    menu: MenuContent?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Keyed on the heading rather than on the object: callers build the content inline, so the
    // instance is new on every recomposition and the cursor would reset under the viewer's hand.
    var cursor by remember(menu?.heading) {
        mutableIntStateOf(menu?.actions?.indexOfFirst { it.primary }?.coerceAtLeast(0) ?: 0)
    }
    var armed by remember(menu?.heading) { mutableStateOf(false) }

    if (menu != null) BackHandler(onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (menu == null) {
                    Modifier
                } else {
                    Modifier.onPreviewKeyEvent { event ->
                        handleMenuKey(
                            event = event,
                            menu = menu,
                            cursor = cursor,
                            armed = armed,
                            setCursor = { cursor = it },
                            setArmed = { armed = it }
                        )
                    }
                }
            )
    ) {
        content()

        if (menu != null) {
            MenuPanel(menu = menu, cursor = cursor, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun MenuPanel(menu: MenuContent, cursor: Int, onDismiss: () -> Unit) {
    val skin = LocalSkin.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SCRIM)
            // A tap anywhere but the panel puts it away, which is what a finger expects of
            // something that appeared over the top of what it was touching.
            .pointerInput(menu) { detectTapGestures { onDismiss() } }
            // Keeps the panel off the edges on a phone, where the screen is narrower than the
            // width the panel would otherwise take, and off the overscan on a television.
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PANEL_MAX_WIDTH)
                .clip(RoundedCornerShape(skin.cornerRadius))
                .background(VideoclubColors.Surface)
                // Taps that land on the panel stop here rather than reaching the dismissal above.
                .pointerInput(menu) { detectTapGestures { } }
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Label(text = menu.heading, style = skin.sectionTitle, maxLines = 2)
            menu.note?.let { note ->
                Label(
                    text = note,
                    style = skin.caption,
                    color = VideoclubColors.TextSecondary,
                    maxLines = 3
                )
            }
            Spacer(Modifier.height(6.dp))
            menu.actions.forEachIndexed { index, action ->
                MenuOption(action = action, selected = index == cursor)
            }
        }
    }
}

/**
 * One item. It looks focused when the cursor is on it although it does not hold the focus, because
 * from three metres away those are the same thing and only one of them is reliable on a television.
 */
@Composable
private fun MenuOption(action: MenuAction, selected: Boolean) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(6.dp)
    val background = when {
        selected -> VideoclubColors.TextPrimary
        action.primary -> VideoclubColors.Accent
        else -> VideoclubColors.SurfaceElevated
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .clickable(onClick = action.onSelect)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Label(
            text = action.label,
            style = skin.button,
            color = if (selected) VideoclubColors.Surface else VideoclubColors.TextPrimary,
            maxLines = 1
        )
    }
}

/**
 * What a key means to an open menu.
 *
 * A pure function of the key, its edge and one bit of state, so the rule that actually broke on the
 * television — [Arm] — is something a test can pin down without a device in the room.
 */
internal enum class MenuKey {
    /** Not ours. Volume, Back, anything else: it belongs to whatever else wants it. */
    Ignore,

    /** Ours, and it does nothing. Swallowed so it cannot reach the row behind the scrim. */
    Swallow,

    /** The release of the long press that opened the menu. It only makes the next release count. */
    Arm,
    MovePrevious,
    MoveNext,
    Choose
}

/**
 * The remote, while a menu is up.
 *
 * The confirm key is dead until it has been released once. A long press fires while the button is
 * still physically down, so the first `KeyUp` a menu ever sees is the end of the press that asked
 * for it — and treating that as a choice fires the top item the instant the panel appears, which is
 * exactly the "it shows up and disappears" this menu was rewritten to fix.
 */
internal fun menuKeyIntent(key: Key, type: KeyEventType, armed: Boolean): MenuKey {
    if (key !in SWALLOWED) return MenuKey.Ignore
    val isOk = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

    return when {
        type == KeyEventType.KeyUp && isOk -> if (armed) MenuKey.Choose else MenuKey.Arm
        type != KeyEventType.KeyDown -> MenuKey.Swallow
        key == Key.DirectionUp -> MenuKey.MovePrevious
        key == Key.DirectionDown -> MenuKey.MoveNext
        else -> MenuKey.Swallow
    }
}

private fun handleMenuKey(
    event: KeyEvent,
    menu: MenuContent,
    cursor: Int,
    armed: Boolean,
    setCursor: (Int) -> Unit,
    setArmed: (Boolean) -> Unit
): Boolean {
    when (menuKeyIntent(event.key, event.type, armed)) {
        MenuKey.Ignore -> return false
        MenuKey.Swallow -> Unit
        MenuKey.Arm -> setArmed(true)
        // Clamped rather than wrapped. Three items is short enough to see all of at once, and a
        // cursor that leaps from the bottom to the top of something that small reads as a slip.
        MenuKey.MovePrevious -> setCursor((cursor - 1).coerceAtLeast(0))
        MenuKey.MoveNext -> setCursor((cursor + 1).coerceAtMost(menu.actions.lastIndex))
        MenuKey.Choose -> menu.actions.getOrNull(cursor)?.onSelect?.invoke()
    }
    return true
}

/** Everything the menu answers for. Anything else passes through to whatever else wants it. */
private val SWALLOWED = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter
)

/** Dark enough to read against a wall of posters, light enough to see which one this is about. */
private val SCRIM = Color.Black.copy(alpha = 0.78f)

private val PANEL_MAX_WIDTH = 460.dp
