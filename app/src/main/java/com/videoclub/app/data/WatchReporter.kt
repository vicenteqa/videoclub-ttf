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
 * A channel also says what its guide has on: "La 1" answers whether the television works, "La 1 ·
 * Telediario" answers what the house is actually watching, which is what the panel is looked at
 * for. It is sent again when the programme changes under a channel that stays on — see
 * [programmeChanged] — and never while somebody is still zapping past.
 *
 * It also says whose profile is on, when the household has more than one: the panel shows it under
 * what is playing. It used to leave the viewer out on purpose; the owner asked for it. It is the
 * profile chosen in the app, not a person, and it only travels with what is on right now: the
 * panel's history stays the household's.
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
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Whose profile is on, or null when there is nobody to tell apart. Read on every report. */
    private val viewer: () -> String? = { null },
    /** Read afresh on every report, like [VodClient]'s: a document adopted mid-session applies at once. */
    private val provider: () -> ProviderConfig,
) {

    /** The last thing actually sent, so re-opening the same title does not re-send it. */
    private var reported: String? = null

    /** The channel that has settled, if one has: the only one whose programme changes are news. */
    private var settledChannel: String? = null

    /** The last thing announced by [tuning], so a feed falling back to the next does not re-send. */
    private var tuned: String? = null

    /**
     * Called when something has been playing long enough to count as what somebody is watching.
     *
     * Idempotent per title: the position save that drives this runs on a timer, so without the
     * check below an evening with one film would be a request every ten seconds.
     */
    fun settledOn(label: String, kind: Kind, programme: String? = null, episode: EpisodeRef? = null) {
        val config = provider()
        if (!config.reportsWhatIsOn) {
            // Said out loud because from the outside it is indistinguishable from nobody watching
            // anything, and the difference — a document with no `reportUrl` — is fixed in the panel
            // in ten seconds.
            Log.i(TAG, "This household does not report: its document carries nowhere to send to")
            return
        }
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        settledChannel = trimmed.takeIf { kind == Kind.Channel }
        val showing = programme?.trim()?.takeIf { it.isNotEmpty() }

        val who = viewer()
        val key = "${kind.wire}:$trimmed:${episode?.wire.orEmpty()}:${who.orEmpty()}:${showing.orEmpty()}"
        if (key == reported) return
        reported = key
        send(config, trimmed, kind, showing, episode, who, provisional = false)
    }

    /**
     * Something has just started playing, before it has been on long enough to count — see
     * [settledOn], which is still what counts.
     *
     * The supplier shows the connection the moment the stream opens, and a panel with nothing from
     * the app said "Cliente desconocido" about a box that was plainly this app, for as long as the
     * settle took. This says straight away that it is Videoclub and what it has on, marked
     * `provisional` so the panel shows it without writing it into the household's history: that
     * still only counts what was actually watched. Sent once per title, and never again for what
     * has already settled.
     */
    fun tuning(label: String, kind: Kind, programme: String? = null, episode: EpisodeRef? = null) {
        val config = provider()
        if (!config.reportsWhatIsOn) return
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        val showing = programme?.trim()?.takeIf { it.isNotEmpty() }
        // The episode is part of what is new: the next episode of the same series is announced too.
        // So is the viewer: the same film put on again under another profile is somebody else's.
        val who = viewer()
        val key = "${kind.wire}:$trimmed:${episode?.wire.orEmpty()}:${who.orEmpty()}"
        if (key == tuned || reported?.startsWith("$key:") == true) return
        tuned = key
        send(config, trimmed, kind, showing, episode, who, provisional = true)
    }

    /** Which episode of a series is on, for the panel's row. See [send]. */
    data class EpisodeRef(val season: Int, val number: Int) {
        val wire: String get() = "${season}x$number"
    }

    private fun send(
        config: ProviderConfig,
        label: String,
        kind: Kind,
        showing: String?,
        episode: EpisodeRef?,
        profile: String?,
        provisional: Boolean
    ) {
        val body = JSONObject().apply {
            // `canal` for the field name, whatever the kind: the panel has spoken that word since
            // it only knew about live television, and renaming it would break every box already
            // installed for the sake of a tidier noun.
            put("canal", label)
            put("tipo", kind.wire)
            put("desde", nowMillis() / 1000)
            // What the guide has on, for a channel. Absent rather than empty when there is no guide:
            // older panels ignore the field, and "no guide" is not a programme.
            if (showing != null) put("programa", showing.take(PROGRAMME_MAX))
            // Which episode, for a series: the panel's row says "Temporada 2 · Capítulo 5" under the
            // name. The name stays the series', so its history still counts the series as one.
            if (episode != null) {
                put("season", episode.season)
                put("episode", episode.number)
            }
            // Whose profile, when there is more than one to choose from. Absent otherwise.
            if (profile != null) put("profile", profile.take(PROFILE_MAX))
            if (provisional) put("provisional", true)
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
                        Log.i(TAG, "Reported to the panel: ${kind.wire}${if (provisional) " (provisional)" else ""}")
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

    /**
     * The guide's programme on [channel] changed — the half hour turned, or the guide arrived after
     * the channel did. Reported only for the channel that has already settled: while somebody is
     * zapping, each channel they pass has a programme too, and none of them is what they watch.
     *
     * A guide that has simply run out says nothing: the programme on the panel stays the last one
     * known until the guide, asked for again, names the next.
     */
    fun programmeChanged(channel: String, programme: String?) {
        if (programme.isNullOrBlank()) return
        if (settledChannel == null || settledChannel != channel.trim()) return
        settledOn(channel, Kind.Channel, programme)
    }

    /** Forgets what was last sent, so the next settled title is reported even if it repeats. */
    fun forget() {
        reported = null
        settledChannel = null
        tuned = null
    }

    /**
     * Called when Videoclub is no longer the one playing anything here — see `MainActivity.onStop`.
     *
     * On a box with one screen, going to the background usually means somebody switched to a
     * different client on the same account. Without this, the panel kept crediting Videoclub for
     * whatever was on last — up to twelve hours after the switch — because nothing ever told it
     * playback had actually stopped; `settledOn` only speaks up when something *starts*.
     *
     * [reported] is cleared too: the panel has just been told this stopped, so the same title must
     * be free to send again the moment playback resumes, rather than being swallowed by the dedupe.
     */
    fun stopped() {
        reported = null
        settledChannel = null
        tuned = null
        val config = provider()
        if (!config.reportsWhatIsOn) return

        val body = JSONObject().apply { put("parado", true) }.toString()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.reportUrl)
                    .header("Authorization", "Bearer ${config.reportToken}")
                    .post(body.toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Log.i(TAG, "Reported to the panel: stopped")
                    else Log.w(TAG, "The panel refused the stop report (${response.code})")
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not report stopping (${error.javaClass.simpleName})")
            }
        }
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
        val config = provider()
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

    /** The version already reported, so it is not repeated on every poll. */
    private var reportedVersion: Int? = null

    /**
     * Tells the panel which version of the app is running here, and whether this device can update
     * itself silently.
     *
     * `owner` matters as much as `version`: a household stuck on an old build with no device owner
     * is not a bug in [Updater], it is the expected state until somebody visits with the device in
     * hand — and that is exactly the distinction the panel needs to show, rather than a version
     * number that never moves and no explanation why.
     *
     * Sent once per version and not on every poll — the version only changes on an install this app
     * itself lives through — and, unlike [lineup], never reset on failure: nothing was recorded as
     * sent, so the next poll simply tries again on its own.
     */
    fun version(code: Int, owner: Boolean) {
        val config = provider()
        if (!config.reportsWhatIsOn) return
        if (code == reportedVersion) return

        val body = JSONObject().apply {
            put("version", code)
            put("owner", owner)
        }.toString()

        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(config.reportUrl)
                    .header("Authorization", "Bearer ${config.reportToken}")
                    .post(body.toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        reportedVersion = code
                        Log.i(TAG, "Reported the running version to the panel")
                    } else {
                        Log.w(TAG, "The panel refused the version report (${response.code})")
                    }
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not report the version (${error.javaClass.simpleName})")
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

        /** The panel keeps 160 characters; a guide's title is rarely a tenth of that. */
        const val PROGRAMME_MAX = 160

        /** The panel keeps 40 characters of a profile's name. */
        const val PROFILE_MAX = 40
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
