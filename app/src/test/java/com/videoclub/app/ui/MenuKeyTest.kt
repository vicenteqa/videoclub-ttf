package com.videoclub.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule this pins down is the one that made the menu unusable on the television, and it is
 * invisible on a phone: a long press fires while the button is still down, so the first release the
 * menu sees is the end of the press that opened it.
 */
class MenuKeyTest {

    private val ok = Key.DirectionCenter

    @Test
    fun `the release of the press that opened the menu chooses nothing`() {
        assertThat(menuKeyIntent(ok, KeyEventType.KeyUp, armed = false)).isEqualTo(MenuKey.Arm)
    }

    @Test
    fun `the release after that is a choice`() {
        assertThat(menuKeyIntent(ok, KeyEventType.KeyUp, armed = true)).isEqualTo(MenuKey.Choose)
    }

    @Test
    fun `holding the button down never chooses anything, however long it repeats`() {
        repeat(5) {
            assertThat(menuKeyIntent(ok, KeyEventType.KeyDown, armed = true))
                .isEqualTo(MenuKey.Swallow)
        }
    }

    @Test
    fun `enter counts as the confirm key, for a keyboard and for the boxes that send it`() {
        assertThat(menuKeyIntent(Key.Enter, KeyEventType.KeyUp, armed = true))
            .isEqualTo(MenuKey.Choose)
        assertThat(menuKeyIntent(Key.NumPadEnter, KeyEventType.KeyUp, armed = true))
            .isEqualTo(MenuKey.Choose)
    }

    @Test
    fun `up and down move the cursor, on the press rather than the release`() {
        assertThat(menuKeyIntent(Key.DirectionUp, KeyEventType.KeyDown, armed = true))
            .isEqualTo(MenuKey.MovePrevious)
        assertThat(menuKeyIntent(Key.DirectionDown, KeyEventType.KeyDown, armed = true))
            .isEqualTo(MenuKey.MoveNext)
        assertThat(menuKeyIntent(Key.DirectionUp, KeyEventType.KeyUp, armed = true))
            .isEqualTo(MenuKey.Swallow)
    }

    @Test
    fun `sideways is swallowed, so the row behind the scrim cannot be scrolled blind`() {
        assertThat(menuKeyIntent(Key.DirectionLeft, KeyEventType.KeyDown, armed = true))
            .isEqualTo(MenuKey.Swallow)
        assertThat(menuKeyIntent(Key.DirectionRight, KeyEventType.KeyDown, armed = true))
            .isEqualTo(MenuKey.Swallow)
    }

    @Test
    fun `back and the volume keys are not the menu's business`() {
        listOf(Key.Back, Key.VolumeUp, Key.VolumeDown, Key.Menu).forEach { key ->
            assertThat(menuKeyIntent(key, KeyEventType.KeyDown, armed = true))
                .isEqualTo(MenuKey.Ignore)
            assertThat(menuKeyIntent(key, KeyEventType.KeyUp, armed = true))
                .isEqualTo(MenuKey.Ignore)
        }
    }
}
