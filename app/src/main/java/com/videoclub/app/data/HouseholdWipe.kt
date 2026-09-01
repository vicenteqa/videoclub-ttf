package com.videoclub.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Deja el aparato limpio cuando le instalan encima el APK de otra casa.
 *
 * Todas las casas comparten `applicationId`, a propósito: así una casa que ya tiene la app instalada
 * recibe la siguiente versión como una actualización y no como una segunda app. El precio es que
 * instalar el APK de otra casa encima **conserva los datos de la anterior** —Android sólo los borra
 * al desinstalar—, y lo que queda en disco no es un detalle interno: es el catálogo, el «Seguir
 * viendo» y «Mi lista» de la casa de antes, con nombres de películas y de personas.
 *
 * Que el documento alojado se descargue al arrancar no lo arregla solo. Cuando la cuenta cambia,
 * [CatalogRepository.refresh] reconstruye el catálogo, pero eso son novecientas peticiones y un par
 * de minutos durante los cuales lo que se ve en pantalla sigue siendo la tienda de la otra casa. Y
 * un aparato que arranca sin red no lo arregla nunca.
 *
 * Así que la casa se decide aquí, en seco y antes de que nada abra la base de datos: el APK lleva
 * compilada la URL de su documento —`BuildConfig.REMOTE_CONFIG_URL`, una por casa— y este es el
 * único sitio que la compara con la que dejó grabada la instalación anterior.
 *
 * **Se borra por barrido y no por lista de nombres.** Bases de datos, ficheros, cachés y
 * preferencias, todo menos el sello. Enumerar `catalogo.db`, `provider.json` y compañía sería una
 * copia de la verdad que vive en [CatalogStore] y [ChannelStore], y el día que alguien añada un
 * caché nuevo esa copia se quedaría corta en silencio — con datos de otra casa dentro. Aquí lo que
 * no se reconoce se va, que para un aparato que acaba de cambiar de dueño es la respuesta correcta.
 *
 * **Un aparato sin sello no se toca.** Es el caso de todas las instalaciones anteriores a esta
 * versión: borrarles el disco por no reconocerse a sí mismas les costaría el progreso que todavía
 * no han sincronizado, que es justo lo que no se puede reponer. Se les pone el sello y se sigue.
 */
fun wipeIfHouseholdChanged(context: Context, remoteConfigUrl: String): Boolean {
    // Una build sin URL compilada no tiene casa que comparar; no hay nada que decidir.
    if (remoteConfigUrl.isBlank()) return false

    val stamp = context.getSharedPreferences(STAMP_PREFS, Context.MODE_PRIVATE)
    val previous = stamp.getString(KEY_URL, null)
    if (previous == remoteConfigUrl) return false

    // `commit` y no `apply` en los dos caminos: lo siguiente que ocurre es que se vuelven a abrir
    // esos ficheros, y un sello escrito a medias convierte el próximo arranque en otro borrado.
    if (previous == null) {
        Log.i(TAG, "Primer arranque con sello de casa; no se borra nada")
        stamp.edit().putString(KEY_URL, remoteConfigUrl).commit()
        return false
    }

    // Se dice en voz alta y sin la URL: lleva dentro la ruta secreta, que hace de credencial.
    Log.w(TAG, "Este APK es de otra casa que la instalada; se borra lo que había")

    for (name in context.databaseList()) context.deleteDatabase(name)
    context.filesDir.deleteContents()
    context.cacheDir.deleteContents()
    for (name in sharedPrefsNames(context)) {
        if (name != STAMP_PREFS) context.deleteSharedPreferences(name)
    }

    stamp.edit().putString(KEY_URL, remoteConfigUrl).commit()
    return true
}

/**
 * Cómo se llaman las preferencias de esta app.
 *
 * No hay API para preguntarlo, así que se lee el directorio donde Android las guarda. Si un día deja
 * de estar ahí, esto devuelve una lista vacía y el resto del borrado sigue haciéndose: mejor limpiar
 * de menos que caerse durante el arranque.
 */
private fun sharedPrefsNames(context: Context): List<String> =
    File(context.applicationInfo.dataDir, "shared_prefs")
        .listFiles()
        .orEmpty()
        .mapNotNull { file -> file.name.takeIf { it.endsWith(".xml") }?.removeSuffix(".xml") }

private fun File.deleteContents() {
    listFiles().orEmpty().forEach { it.deleteRecursively() }
}

private const val TAG = "HouseholdWipe"
private const val STAMP_PREFS = "videoclub-casa"
private const val KEY_URL = "remote_config_url"
