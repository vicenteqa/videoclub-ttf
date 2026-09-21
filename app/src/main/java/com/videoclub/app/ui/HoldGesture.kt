package com.videoclub.app.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/**
 * [action] once something has been held for [millis] — a finger, or OK on the remote.
 *
 * Far longer than Android's own long press, on purpose: this is for things nobody should find by
 * accident. What it is on keeps its ordinary tap: a shorter press goes through untouched, and a hold
 * long enough to count is swallowed whole, so it is never also taken as a tap on release. A finger
 * that moves further than a slop — somebody scrolling the strip — is not holding anything.
 */
fun Modifier.onHeld(millis: Long, action: (() -> Unit)?): Modifier {
    if (action == null) return this
    return composed {
        var keyDownAt by remember { mutableLongStateOf(0L) }
        var keyFired by remember { mutableStateOf(false) }

        pointerInput(action) {
            awaitEachGesture {
                // The initial pass sees every event before the chip's own clickable does, which is
                // what lets a hold that counted take its release away from it.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val letGo = withTimeoutOrNull(millis) {
                    var moved = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        moved += event.changes.sumOf { it.positionChange().getDistance().toDouble() }.toFloat()
                        if (event.changes.none { it.pressed } || moved > viewConfiguration.touchSlop) break
                    }
                    true
                }
                if (letGo == null) {
                    action()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }.onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) {
                return@onPreviewKeyEvent false
            }
            val native = event.nativeKeyEvent
            when (event.type) {
                // A remote repeats the key while it is held; the first press starts the clock and
                // the repeats read it.
                KeyEventType.KeyDown -> {
                    if (native.repeatCount == 0) {
                        keyDownAt = native.eventTime
                        keyFired = false
                    } else if (!keyFired && native.eventTime - keyDownAt >= millis) {
                        keyFired = true
                        action()
                    }
                    keyFired
                }
                KeyEventType.KeyUp -> keyFired.also { keyFired = false }
                else -> false
            }
        }
    }
}
