package com.videoclub.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.ui.LocalPosterMenu
import com.videoclub.app.ui.MainViewModel
import com.videoclub.app.ui.SimpleRoot
import com.videoclub.app.ui.VideoclubRoot
import com.videoclub.app.ui.VideoclubTheme

/**
 * The only activity.
 *
 * It measures the window, hands that to the theme and gets out of the way.
 *
 * A phone is pinned upright and turned on its side only for the player — see [isPhone]. A television
 * is pinned on its side and stays there, film or no film: there is no other way to hold a television,
 * and a set-top box that reports itself as a phone-shaped device must not be allowed to serve a
 * portrait page to a screen on a wall. A tablet is left alone, because it really is used both ways.
 * The activity survives the turn rather than being rebuilt by it: `configChanges` in the manifest
 * covers `orientation` and `screenSize`, so opening a film does not reload the film.
 */
class MainActivity : ComponentActivity() {

    private val profile: DeviceProfile
        get() = (application as VideoclubApp).container.deviceProfile

    /**
     * La tele, más allá de la app. Vale para los dos modos, no sólo para el simple: apagar la tele
     * con su propio mando no llama a `onStop` de esta actividad, y sin esto el aparato se queda
     * reproduciendo toda la noche a una habitación a oscuras.
     */
    private val screenWatch by lazy {
        val container = (application as VideoclubApp).container
        ScreenWatch(this, container.deviceProfile) { on ->
            container.onScreen(on)
            if (on) container.adoptHostedConfig(System.currentTimeMillis())
        }
    }

    /**
     * A handset, as opposed to a tablet or a television.
     *
     * `smallestScreenWidthDp` and not the current width: the question is what the device *is*, and
     * that must give the same answer whichever way it happens to be held at the time. A television
     * is excluded outright rather than by size, since the boxes that lie about being televisions
     * also lie about their size.
     */
    private val isPhone: Boolean
        get() = profile != DeviceProfile.Tv &&
            resources.configuration.smallestScreenWidthDp < TABLET_WIDTH_DP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VideoclubApp).container
        container.start(System.currentTimeMillis())

        requestedOrientation = when {
            container.deviceProfile == DeviceProfile.Tv -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            isPhone -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        setContent {
            // Read from the configuration rather than from the display, so a tablet in split screen
            // gets the phone layout it actually has room for.
            val widthDp = LocalConfiguration.current.screenWidthDp

            // `LiveScreen` reads `LocalSkin` for its notices (`Cargando canales…`, the rebuild
            // dialog) whichever mode draws it, so the theme wraps both branches rather than only
            // the videoclub one.
            VideoclubTheme(profile = container.deviceProfile, widthDp = widthDp) {
                // El modo se decide una vez —de ahí el `remember`, que sobrevive a las recomposiciones
                // y hace que «al arrancar» sea cierto y no una promesa— pero no antes de tiempo: en un
                // aparato recién instalado el caché todavía no dice de qué casa es, así que leerlo en
                // `onCreate` daría videoclub a una casa simple hasta el siguiente arranque. Se espera a
                // que el contenedor sepa de quién es la tele, que es la misma espera que ya paga
                // `VideoclubRoot`.
                val startup by container.startup.collectAsState()
                var simple by remember { mutableStateOf<Boolean?>(null) }
                if (simple == null && startup != Startup.Checking) {
                    simple = container.settings.current.simple
                }

                when (simple) {
                    null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        return@VideoclubTheme
                    }

                    true -> {
                        SimpleRoot(container = container, onExit = ::finish)
                        return@VideoclubTheme
                    }

                    else -> Unit
                }

                val viewModel: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                // Provided once, for every poster in the app: see `LocalPosterMenu`.
                CompositionLocalProvider(LocalPosterMenu provides viewModel::openMenu) {
                    VideoclubRoot(
                        container = container,
                        viewModel = viewModel,
                        onExit = ::finish
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        screenWatch.start()
        // Releer el documento mientras se está viendo algo es lo que permite mandarle un canal a una
        // casa desde el panel: encendida y reproduciendo, la app no volvía a mirarlo nunca.
        (application as VideoclubApp).container.startPolling()
    }

    override fun onStop() {
        super.onStop()
        screenWatch.stop()
        (application as VideoclubApp).container.stopPolling()
    }

    /**
     * Turns the phone on its side for a film and back afterwards.
     *
     * `SENSOR_LANDSCAPE` rather than `LANDSCAPE` so that a phone held the other way round still gets
     * the picture the right way up. Called by the player screen; a no-op on anything but a handset.
     */
    fun setPlaybackOrientation(playing: Boolean) {
        if (!isPhone) return
        requestedOrientation = if (playing) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private companion object {
        /** The usual boundary, and the same one the theme uses to decide the poster sizes. */
        const val TABLET_WIDTH_DP = 600
    }
}
