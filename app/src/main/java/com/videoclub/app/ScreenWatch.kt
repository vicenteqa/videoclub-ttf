package com.videoclub.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.videoclub.app.data.DeviceProfile

/**
 * Whether there is anybody's television actually lit up in front of this box.
 *
 * On a phone this question is answered by the activity lifecycle and nothing else is needed. On a
 * set-top box it is not: switching the television off with the television's own remote leaves the
 * box powered, the app in the foreground and `onStop` never called, so playback carries on all
 * night to a dark room. That is a stream, a decoder and — on an account that allows one connection
 * — the whole subscription, held by nobody.
 *
 * There is no single reliable signal for it, so this listens to three and treats them as evidence
 * rather than as truth:
 *
 *  - `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`, which is what a box sent to standby over HDMI-CEC
 *    reports. The app holds `FLAG_KEEP_SCREEN_ON`, so this cannot fire from mere idleness — if it
 *    arrives, something deliberately turned the screen off.
 *  - `ACTION_HDMI_AUDIO_PLUG`, which most televisions trigger on the way down because they drop
 *    hotplug detect when they lose power.
 *  - The default display's own state, re-read whenever the display manager says anything changed.
 *
 * The two are kept apart rather than merged into one flag, because they disagree in exactly one
 * important direction: plenty of boxes report `STATE_ON` forever regardless of what the HDMI cable
 * is doing. Folding them together would let that lie overrule a perfectly good HDMI signal, so a
 * source that has never said anything ([hdmi] is null) is ignored, and a source that has said "no"
 * keeps saying it until it says otherwise.
 *
 * A box that reports none of the three keeps playing. Nothing here can fix that from software, and
 * pretending otherwise would mean guessing at a viewer who is sitting there watching.
 */
class ScreenWatch(
    private val context: Context,
    private val profile: DeviceProfile,
    private val onChange: (Boolean) -> Unit,
) {

    /**
     * HDMI evidence is for televisions only.
     *
     * `ACTION_HDMI_AUDIO_PLUG` is sticky, so a device registering for it is handed the last state
     * immediately — and a phone, which has no HDMI socket at all, is handed `state=0`. Believing it
     * there means the app decides there is no screen half a second after starting, on the one
     * device where the screen is the thing you are holding.
     */
    private val trustHdmi = profile == DeviceProfile.Tv

    private val displays =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private var screenOn = true
    private var hdmi: Boolean? = null
    private var reported: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    emit("pantalla apagada")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    emit("pantalla encendida")
                }
                AudioManager.ACTION_HDMI_AUDIO_PLUG -> {
                    if (!trustHdmi) return
                    // Absent extra means a broadcast we cannot read; assume connected rather than
                    // stopping a viewer's programme on a malformed intent.
                    val plugged = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1) == 1
                    hdmi = plugged
                    emit(if (plugged) "HDMI conectado" else "HDMI desconectado")
                }
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = readDisplay("pantalla añadida")
        override fun onDisplayRemoved(displayId: Int) = readDisplay("pantalla retirada")
        override fun onDisplayChanged(displayId: Int) = readDisplay("pantalla cambiada")
    }

    /** Registers, and reports the state it finds so the caller never has to assume one. */
    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            if (trustHdmi) addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        displays.registerDisplayListener(displayListener, null)
        readDisplay("arranque")
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        displays.unregisterDisplayListener(displayListener)
    }

    /**
     * Only an explicit `STATE_OFF` counts as off, and the asymmetry is the whole point.
     *
     * Getting this wrong in one direction leaves a stream running for nobody, which costs a
     * connection until somebody next picks up a remote. Getting it wrong in the other leaves a
     * television permanently black in a house nobody in this project can reach without driving
     * there. `STATE_UNKNOWN` and the various dozing states are exactly the values a cheap box
     * invents, so they are read as "no idea, carry on" rather than as "off".
     *
     * A display that has gone away entirely reads as null: that is the HDMI link disappearing, not
     * a box being vague, and it does count.
     */
    private fun readDisplay(reason: String) {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        screenOn = display != null && display.state != Display.STATE_OFF
        emit(reason)
    }

    private fun emit(reason: String) {
        val on = screenOn && (hdmi ?: true)
        if (on == reported) return
        reported = on
        Log.i(TAG, "Hay pantalla: $on ($reason)")
        onChange(on)
    }

    private companion object {
        const val TAG = "ScreenWatch"
    }
}
