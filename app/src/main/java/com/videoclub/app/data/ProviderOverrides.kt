package com.videoclub.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hosted document, parsed.
 *
 * Everything this app needs to know about *which household it belongs to* arrives here: the
 * supplier account, and the people who watch. Nothing of it is compiled into the APK, so a rotated
 * password or a new nephew is an edit on a server rather than a visit to a television.
 *
 * Every field is nullable and every null means "leave what is cached". That is what makes the file
 * safe to write by hand at three in the morning: it can hold one line, and nothing else moves.
 *
 *     { "password": "the-new-one" }
 *
 * A blank string is absent rather than an instruction to blank the field, and an empty list of
 * people is absent rather than an instruction to leave the household with nobody in it. In both
 * cases the failure the strict reading would cause — an app that has forgotten its account, or one
 * nobody can watch — is far worse than the edit it would enable.
 */
data class ProviderOverrides(
    val baseUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val userAgent: String? = null,
    /**
     * Where to report what is being watched, and with which credential.
     *
     * The panel sets both when it creates the household. Without both, the app reports nothing: the
     * sender checks, so an old build or a hand-edited document never sends anything by accident.
     */
    val reportUrl: String? = null,
    val reportToken: String? = null,
    val profiles: List<Profile>? = null,
    /**
     * The next profile id the panel will hand out, carried so it survives a deletion.
     *
     * Without it, removing the last person makes the highest id in the list drop, and the next
     * person created is handed a number somebody else's history is already filed under. [Profile]
     * says as much about ids belonging to people rather than to positions; this is where that rule
     * is kept now that the editing happens on a server.
     */
    val nextProfileId: Int? = null,
    /**
     * Whether this household behaves like SimpleTV: live television only, no video shop and no
     * profile picker. Null means "leave whatever is cached", exactly like every other field.
     */
    val simple: Boolean? = null,
    /**
     * Channels the supplier does not carry, added by the household — local stations. See
     * [ExtraChannel].
     *
     * Null means "leave whatever is cached"; an empty list means "this household has none", which is
     * how they all get removed.
     */
    val extraChannels: List<ExtraChannel>? = null,
    /**
     * "Tune to this channel", sent from the panel. See [TuneOrder].
     *
     * It is not stored in [ProviderConfig] and is compared against nothing: it is a dated errand,
     * not a setting, and what decides whether it has already been obeyed is [ChannelStore], not this.
     */
    val tune: TuneOrder? = null
) {

    val isEmpty: Boolean
        get() = baseUrl == null && username == null && password == null &&
            userAgent == null && profiles == null &&
            reportUrl == null && reportToken == null && simple == null &&
            extraChannels == null && tune == null

    /**
     * What gets written to disk for the next launch.
     *
     * [tune] is deliberately left out: an errand belongs to now, and storing it would mean a box
     * starting tomorrow with no network finding last night's order in its cache. An errand existing
     * only while the document says so is what prevents that.
     */
    fun encode(): String = JSONObject().apply {
        baseUrl?.let { put(KEY_URL, it) }
        username?.let { put(KEY_USERNAME, it) }
        password?.let { put(KEY_PASSWORD, it) }
        userAgent?.let { put(KEY_USER_AGENT, it) }
        reportUrl?.let { put(KEY_REPORT_URL, it) }
        reportToken?.let { put(KEY_REPORT_TOKEN, it) }
        nextProfileId?.let { put(KEY_NEXT_PROFILE_ID, it) }
        simple?.let { put(KEY_SIMPLE, it) }
        extraChannels?.let { canales ->
            put(KEY_CHANNELS, JSONArray().apply {
                canales.forEach { canal ->
                    put(JSONObject().apply {
                        put(KEY_CHANNEL_NAME, canal.name)
                        put(KEY_CHANNEL_URL, canal.url)
                        canal.logoUrl?.let { put(KEY_CHANNEL_LOGO, it) }
                        canal.userAgent?.let { put(KEY_CHANNEL_USER_AGENT, it) }
                    })
                }
            })
        }
        profiles?.let { people ->
            put(KEY_PROFILES, JSONArray().apply {
                people.forEach { person ->
                    put(JSONObject().apply {
                        put(KEY_PROFILE_ID, person.id)
                        put(KEY_PROFILE_NAME, person.name)
                        if (person.childrenOnly) put(KEY_PROFILE_CHILDREN, true)
                    })
                }
            })
        }
    }.toString()

    companion object {
        const val KEY_URL = "url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_USER_AGENT = "userAgent"
        const val KEY_REPORT_URL = "reportUrl"
        const val KEY_REPORT_TOKEN = "reportToken"
        const val KEY_PROFILES = "perfiles"
        const val KEY_NEXT_PROFILE_ID = "siguientePerfilId"
        const val KEY_PROFILE_ID = "id"
        const val KEY_PROFILE_NAME = "nombre"
        const val KEY_PROFILE_CHILDREN = "infantil"
        const val KEY_SIMPLE = "simple"
        const val KEY_CHANNELS = "canales"
        const val KEY_CHANNEL_NAME = "nombre"
        const val KEY_CHANNEL_URL = "url"
        const val KEY_CHANNEL_LOGO = "logo"
        const val KEY_CHANNEL_USER_AGENT = "userAgent"
        const val KEY_TUNE = "poner"
        const val KEY_TUNE_CHANNEL = "canal"
        const val KEY_TUNE_AT = "cuando"

        val NONE = ProviderOverrides()

        /**
         * Reads the hosted document, or null when it is not readable as one.
         *
         * Null and [NONE] mean different things and the difference matters: null is "this response
         * was not the config file" — a captive portal, an nginx 404 page, a half-finished edit —
         * and must never overwrite a working cached config. [NONE] is a valid document that happens
         * to override nothing.
         */
        fun parse(body: String): ProviderOverrides? {
            val json = runCatching { JSONObject(body) }
                .onFailure { Log.w(TAG, "Hosted config is not JSON; ignoring it") }
                .getOrNull()
                ?: return null

            return ProviderOverrides(
                baseUrl = json.string(KEY_URL)?.trimEnd('/'),
                username = json.string(KEY_USERNAME),
                password = json.string(KEY_PASSWORD),
                userAgent = json.string(KEY_USER_AGENT),
                reportUrl = json.string(KEY_REPORT_URL),
                reportToken = json.string(KEY_REPORT_TOKEN),
                profiles = json.people(),
                nextProfileId = if (json.has(KEY_NEXT_PROFILE_ID)) {
                    json.optInt(KEY_NEXT_PROFILE_ID, -1).takeIf { it >= 0 }
                } else {
                    null
                },
                simple = if (json.has(KEY_SIMPLE) && !json.isNull(KEY_SIMPLE)) {
                    json.optBoolean(KEY_SIMPLE)
                } else {
                    null
                },
                extraChannels = json.channels(),
                tune = json.tuneOrder()
            )
        }

        /**
         * The "tune to this channel" errand, or null when the document carries none.
         *
         * Without `cuando` there is no order: one with no timestamp could not be obeyed exactly
         * once, and the box would jump to that channel every time it re-read the document. Rather
         * than do that, it is ignored.
         */
        private fun JSONObject.tuneOrder(): TuneOrder? {
            val row = optJSONObject(KEY_TUNE) ?: return null
            val label = row.optString(KEY_TUNE_CHANNEL).trim()
            val issued = row.optLong(KEY_TUNE_AT, 0L)
            if (label.isEmpty() || issued <= 0L) return null
            return TuneOrder(label = label, issuedAt = issued)
        }

        /**
         * The channels the household adds, or null when the document does not mention them.
         *
         * Unlike [people], an empty list **is respected** rather than turned into null: removing the
         * last added channel is something somebody genuinely wants to do, whereas ending up with no
         * people would leave the household with nobody to attribute anything to. A channel with no
         * name or no URL drops itself, for the same reason a person with no name does: better to
         * lose the row than to reject the whole document, and with it a password change that was
         * perfectly good.
         */
        private fun JSONObject.channels(): List<ExtraChannel>? {
            val array = optJSONArray(KEY_CHANNELS) ?: return null
            return (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val name = row.optString(KEY_CHANNEL_NAME).trim()
                val url = row.optString(KEY_CHANNEL_URL).trim()
                if (name.isEmpty() || url.isEmpty()) {
                    Log.w(TAG, "Skipping an unusable channel at position $index")
                    return@mapNotNull null
                }
                ExtraChannel(
                    name = name,
                    url = url,
                    logoUrl = row.string(KEY_CHANNEL_LOGO),
                    userAgent = row.string(KEY_CHANNEL_USER_AGENT)
                )
            }
        }

        /**
         * The household, or null when the document does not say.
         *
         * A person with no name is dropped rather than rendered as an empty circle, and a repeated
         * id is dropped rather than allowed to make two people share one history. Both are things a
         * hand edit does, and neither is worth refusing the whole document over — the alternative
         * is an app that ignores a perfectly good password change because somebody left a comma in
         * the wrong place of a list this app can survive without.
         */
        private fun JSONObject.people(): List<Profile>? {
            val array = optJSONArray(KEY_PROFILES) ?: return null
            val seen = mutableSetOf<Int>()
            val people = (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optInt(KEY_PROFILE_ID, -1)
                val name = row.optString(KEY_PROFILE_NAME).trim()
                if (id < 0 || name.isEmpty() || !seen.add(id)) {
                    Log.w(TAG, "Skipping an unusable profile at position $index")
                    return@mapNotNull null
                }
                Profile(
                    id = id,
                    name = name,
                    childrenOnly = row.optBoolean(KEY_PROFILE_CHILDREN, false)
                )
            }
            return people.takeIf { it.isNotEmpty() }
        }

        private fun JSONObject.string(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).trim().takeIf { it.isNotEmpty() }
        }

        private const val TAG = "ProviderOverrides"
    }
}

/** The empty account with the hosted file's contents laid over it. */
fun ProviderConfig.mergedWith(overrides: ProviderOverrides): ProviderConfig = copy(
    baseUrl = overrides.baseUrl ?: baseUrl,
    username = overrides.username ?: username,
    password = overrides.password ?: password,
    userAgent = overrides.userAgent ?: userAgent,
    reportUrl = overrides.reportUrl ?: reportUrl,
    reportToken = overrides.reportToken ?: reportToken,
    simple = overrides.simple ?: simple,
    extraChannels = overrides.extraChannels ?: extraChannels
)
