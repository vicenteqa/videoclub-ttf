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
 * El modo «simple»: sólo la tele en directo, sin videoclub, sin pestañas y sin selector de
 * personas — la experiencia que tenía SimpleTV, dentro de este proyecto.
 *
 * La app arranca sintonizando y no dice nada más: ni saludo ni cartel. [LiveScreen] es autocontenida
 * y no sabe nada de este modo, así que esto es sólo la envoltura.
 *
 * Y es lo único que decide qué significa salir. Con el mando, [LiveScreen] pide dos Atrás para
 * llegar hasta [onExit]: el primero abre la lista de canales y el segundo se va. En el videoclub
 * normal eso basta, porque «irse» ahí sólo cambia de pantalla. Aquí cierra la app entera, así que
 * ese segundo Atrás no la cierra: abre esta confirmación, con el mismo `OverlayMenu` que usa el
 * resto de la app para lo mismo.
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
