package com.videoclub.app.data

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the hosted account document.
 *
 * Two rules hold this together, and both exist because the document carries the account password in
 * clear text:
 *
 *  - The URL must be `https`. The rest of the app talks to the supplier over plain HTTP because
 *    suppliers frequently offer nothing else, but that concession stops here: a config served over
 *    HTTP could be rewritten in flight by anyone on the path, and this file decides which server the
 *    app authenticates against.
 *  - The URL is the credential. There is no login, so the path carries a long random segment and
 *    that secrecy is the whole of the access control. It follows that the document must not be
 *    served from a directory with autoindex on, and must never be linked from anywhere.
 *
 * Anyone holding the APK can read both the URL and the account out of it, so this is not a defence
 * against someone with the file. It is a defence against the open internet — and it is strictly
 * better than the alternative it replaces, because credentials that live in one hosted file can be
 * rotated in seconds, while credentials compiled into an APK cannot be rotated at all.
 */
class RemoteConfigClient(
    http: OkHttpClient,
    private val url: String
) {

    /** Whether this build has somewhere to ask. An unset or non-HTTPS URL disables the feature. */
    val isEnabled: Boolean = url.startsWith("https://")

    // Derived from the shared client so the connection pool is reused, but with its own timeouts:
    // the shared read timeout is tuned for a live stream, where twenty silent seconds is normal.
    // Nothing here should keep a startup refresh alive that long.
    private val http = http.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * The hosted document, or null when there was not one to be had.
     *
     * Null covers every failure alike — offline, DNS, a 404, a captive portal's login page — and
     * they are all the same instruction to the caller: keep using the cached account. This is the
     * routine case, not an error case. The box this runs on may well be starting up before its
     * network is ready.
     */
    suspend fun fetch(): ProviderOverrides? = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ProviderConfig.DEFAULT_USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Hosted config answered with ${response.code}")
                    return@use null
                }
                // Capped rather than read whole: whatever answers this URL is not necessarily the
                // file, and a wrong turn should not pull a video down onto a set-top box's heap.
                ProviderOverrides.parse(response.peekBody(MAX_BYTES).string())
            }
        }.onFailure { error ->
            // The URL is not logged: it is the secret that guards the account.
            Log.i(TAG, "No hosted config this time (${error.javaClass.simpleName}); keeping the cached account")
        }.getOrNull()
    }

    private companion object {
        const val TAG = "RemoteConfigClient"
        const val TIMEOUT_SECONDS = 8L
        const val CALL_TIMEOUT_SECONDS = 15L
        const val MAX_BYTES = 8L * 1024L
    }
}
