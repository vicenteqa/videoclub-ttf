package com.videoclub.app.data

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** What asking the panel which household a username and password belong to came back with. */
sealed interface LoginOutcome {
    /** [url] is the household's document; [house] is what the panel calls it. */
    data class Success(val url: String, val house: String) : LoginOutcome

    /**
     * Wrong username or password — or a simple household, which has an APK of its own and cannot be
     * logged into from the general one. The panel answers all three the same, on purpose.
     */
    data object Refused : LoginOutcome

    data class TooManyAttempts(val retryAfterSeconds: Long) : LoginOutcome

    /** Nothing that could be read as an answer: no network, the VPS down, a captive portal's page. */
    data object Unreachable : LoginOutcome

    companion object {
        /** See `POST /videoclub/login` in the videoclub-server README. */
        fun fromResponse(code: Int, body: String): LoginOutcome {
            val json = runCatching { JSONObject(body) }.getOrNull()
            return when (code) {
                200 -> {
                    // HTTPS or nothing, for the reason [RemoteConfigClient] insists on it: this is
                    // the address the app will read the account from from now on.
                    val url = json?.optString("url")?.trim().orEmpty()
                    if (json != null && url.startsWith("https://")) Success(url, json.optString("house").trim())
                    else Unreachable
                }
                401 -> Refused
                429 -> TooManyAttempts(json?.optLong("retry_after", 0L)?.coerceAtLeast(0L) ?: 0L)
                else -> Unreachable
            }
        }
    }
}

/**
 * The general APK's one question: whose device is this.
 *
 * It trades a household's username and password for that household's document URL, and is asked
 * exactly once per install — see `Container.logIn`. Everything after that is what a household's own
 * APK has always done with the URL compiled into it.
 */
class HouseholdLogin(
    http: OkHttpClient,
    private val url: String
) {

    /** Only the general APK has somewhere to log in; a household's own APK is built without it. */
    val isEnabled: Boolean = url.startsWith("https://")

    // Shorter than the shared client's: somebody is standing in front of this with a remote.
    private val http = http.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun logIn(username: String, password: String): LoginOutcome = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext LoginOutcome.Unreachable
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .toString()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ProviderConfig.DEFAULT_USER_AGENT)
            .post(body.toRequestBody(JSON))
            .build()
        runCatching {
            http.newCall(request).execute().use { response ->
                LoginOutcome.fromResponse(response.code, response.peekBody(MAX_BYTES).string())
            }
        }.onFailure { error ->
            // Neither the username nor the password is logged, nor the URL they were sent to.
            Log.i(TAG, "Login did not reach the panel (${error.javaClass.simpleName})")
        }.getOrDefault(LoginOutcome.Unreachable)
    }

    private companion object {
        const val TAG = "HouseholdLogin"
        const val TIMEOUT_SECONDS = 10L
        const val CALL_TIMEOUT_SECONDS = 20L
        const val MAX_BYTES = 4L * 1024L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
