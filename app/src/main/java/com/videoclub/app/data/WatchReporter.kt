package com.videoclub.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tells the panel what is playing, so that "no va nada" can be answered from another house.
 *
 * ## Why it pushes rather than being asked
 *
 * The obvious design is the server asking the box. It cannot: the box sits behind somebody else's
 * router with no fixed address and no open port, which is the same reason the app *fetches* its
 * account instead of having one pushed to it. Traffic that starts at the box is the only kind
 * there is.
 *
 * ## Why it says so little
 *
 * The name of the film, or the name of the series — never the episode. The panel groups by what it
 * is sent and counts repeats, and "Breaking Bad, catorce veces" is the useful answer; fourteen rows
 * one episode apart is not. The caller decides when something counts as being watched rather than
 * merely opened, and only then is anything sent at all.
 *
 * This deliberately carries **no** viewer: which of the people in the household is watching is the
 * one thing here that would turn a debugging aid into surveillance of a specific person, and the
 * panel has no use for it.
 *
 * ## Why it is off unless switched on
 *
 * It sends nothing at all unless the hosted config carries both `reportUrl` and `reportToken`, and
 * the URL must be HTTPS. A build with no hosted config, or an older document, reports nothing.
 *
 * Failure is silent by design. Reporting is a convenience for whoever is debugging; it must never
 * be able to interrupt somebody's film, so every request is fire-and-forget on its own coroutine
 * and a failure is one log line and nothing else.
 */
class WatchReporter(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val settings: ProviderSettings,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** The last thing actually sent, so re-opening the same title does not re-send it. */
    private var reported: String? = null

    /**
     * Called when something has been playing long enough to count as what somebody is watching.
     *
     * Idempotent per title: the position save that drives this runs on a timer, so without the
     * check below an evening with one film would be a request every ten seconds.
     */
    fun settledOn(label: String, kind: Kind) {
        val config = settings.current
        if (!config.reportsWhatIsOn) {
            // Dicho en voz alta porque desde fuera es indistinguible de que nadie esté viendo nada,
            // y la diferencia —documento sin `reportUrl`— se arregla en el panel en diez segundos.
            Log.i(TAG, "Esta casa no informa: su documento no trae a dónde")
            return
        }
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return

        val key = "${kind.wire}:$trimmed"
        if (key == reported) return
        reported = key

        val body = JSONObject().apply {
            // `canal` for the field name, whatever the kind: the panel has spoken that word since
            // it only knew about live television, and renaming it would break every box already
            // installed for the sake of a tidier noun.
            put("canal", trimmed)
            put("tipo", kind.wire)
            put("desde", nowMillis() / 1000)
        }.toString()

        // On IO explicitly. The scope this is handed is the container's, which runs on
        // `Dispatchers.Main.immediate` because everything else in it drives the UI — and a blocking
        // OkHttp call there is a `NetworkOnMainThreadException`, swallowed by the runCatching below
        // and reported as a mystery.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.reportUrl)
                    .header("Authorization", "Bearer ${config.reportToken}")
                    .post(body.toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        // El éxito también se dice. Sin esto, «no sale nada en el panel» no se
                        // puede separar de «no llegó a intentarlo», que es exactamente donde se
                        // perdió una tarde.
                        Log.i(TAG, "Informado al panel: ${kind.wire}")
                    } else {
                        Log.w(TAG, "The panel refused the report (${response.code})")
                    }
                }
            }.onFailure { error ->
                // The class, never the message: the message can carry the URL, and somebody reads
                // these down a telephone. The class alone is what tells a timeout from a
                // programming mistake, which is the whole difference worth logging.
                Log.w(TAG, "Could not report what is on (${error.javaClass.simpleName})")
            }
        }
    }

    /** Forgets what was last sent, so the next settled title is reported even if it repeats. */
    fun forget() {
        reported = null
    }

    /** Los rótulos ya mandados, para no repetir la misma lista en cada arranque. */
    private var sentLineup: String? = null

    /**
     * Le dice al panel qué canales tiene esta casa.
     *
     * Sin esto, el desplegable de «poner un canal» del panel no tendría de dónde salir: el panel
     * conoce los dos mil nombres crudos del proveedor, no los sesenta y pico rótulos que produce
     * [LiveCuration]. Y copiar la curación al panel sería tener las reglas escritas en dos sitios y
     * en dos lenguajes, que es justo lo que se quitó de en medio al juntar los proyectos.
     *
     * Así que la app, que es quien decide, lo cuenta. Se manda cuando la lista cambia y no en cada
     * arranque —de ahí [sentLineup]—, y como mucho es un kilobyte una vez al día.
     */
    fun lineup(labels: List<String>) {
        val config = settings.current
        if (!config.reportsWhatIsOn) return
        if (labels.isEmpty()) return

        val body = JSONObject().apply {
            put("canales", JSONArray(labels))
        }.toString()
        if (body == sentLineup) return
        sentLineup = body

        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.reportUrl)
                    .header("Authorization", "Bearer ${config.reportToken}")
                    .post(body.toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Log.i(TAG, "Lista de canales enviada al panel")
                    else Log.w(TAG, "El panel rechazó la lista (${response.code})")
                }
            }.onFailure { error ->
                // Que no llegue no rompe nada: el panel se queda con la lista de antes, o sin
                // desplegable si nunca hubo una. Un canal que no se puede mandar en remoto es una
                // comodidad menos, no una avería.
                sentLineup = null
                Log.w(TAG, "No se pudo enviar la lista (${error.javaClass.simpleName})")
            }
        }
    }

    /** The three things this app can play, in the words the panel files them under. */
    enum class Kind(val wire: String) {
        Film("pelicula"),
        Series("serie"),
        Channel("canal"),
    }

    private companion object {
        const val TAG = "WatchReporter"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
