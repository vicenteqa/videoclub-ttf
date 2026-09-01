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
            // Said out loud because from the outside it is indistinguishable from nobody watching
            // anything, and the difference — a document with no `reportUrl` — is fixed in the panel
            // in ten seconds.
            Log.i(TAG, "This household does not report: its document carries nowhere to send to")
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
                        // Success is said out loud too. Without this, "nothing shows up in the
                        // panel" cannot be separated from "it never got as far as trying", which is
                        // exactly where an afternoon once went.
                        Log.i(TAG, "Reported to the panel: ${kind.wire}")
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

    /** The labels already sent, so the same list is not repeated on every launch. */
    private var sentLineup: String? = null

    /**
     * Tells the panel which channels this household has.
     *
     * Without this, the panel's "send a channel" dropdown would have nowhere to come from: the panel
     * knows the supplier's two thousand raw names, not the sixty-odd labels [LiveCuration] produces.
     * And copying the curation into the panel would mean the rules written in two places and two
     * languages, which is exactly what merging the projects got rid of.
     *
     * So the app, which is the one that decides, says so. It is sent when the list changes rather
     * than on every launch — hence [sentLineup] — and at most it is a kilobyte once a day.
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
                    if (response.isSuccessful) Log.i(TAG, "Channel list sent to the panel")
                    else Log.w(TAG, "The panel refused the list (${response.code})")
                }
            }.onFailure { error ->
                // Failing to arrive breaks nothing: the panel keeps the previous list, or has no
                // dropdown if there never was one. A channel that cannot be sent remotely is one
                // convenience fewer, not a fault.
                sentLineup = null
                Log.w(TAG, "Could not send the list (${error.javaClass.simpleName})")
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
