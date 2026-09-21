package com.videoclub.app

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.videoclub.app.data.ApkRelease
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads and installs a release the panel has published.
 *
 * ## Why this can be silent at all
 *
 * A normal app cannot install an APK without the system's own confirmation dialog, and this does
 * not try to get around that. What makes an install silent here is [UpdateAdminReceiver]: once this
 * app is the device's owner, a [PackageInstaller] session it opens commits itself, with nobody in
 * the room to tap anything. That path is [consider], and it is the only thing this class ever
 * installs on its own.
 *
 * ## Why every device downloads ahead of being asked
 *
 * Everywhere else the install needs a tap, and the tap used to be where the wait began: a held
 * button, then twenty megabytes, then the prompt — long enough that the press looked as if it had done
 * nothing and got repeated. So [consider] now fetches the release on every device as soon as the
 * panel publishes it, checks it against its hash, and only then says so through [ready], which is
 * what puts the yellow arrow beside `TV`. By the time anybody can see the arrow the file is already
 * here, and pressing it goes straight to Android's own install prompt with [installReady].
 *
 * This is the badge that was once taken out for being one more thing to explain. It is back because
 * the household asked for it, and it stays as quiet as it can: it does not exist until there is
 * something to install, and it goes away on its own once there is not.
 *
 * [checkNow] is still there for simple mode, which has no strip to put an arrow in: held OK over
 * the channel list asks the server there and then.
 *
 * ## Why [consider] waits for the screen to be off
 *
 * Installing replaces the running process. That must never happen while somebody is watching, and
 * the signal for "somebody might be watching" is [Container.screenOn] — the same one `ScreenWatch`
 * already keeps, for the same underlying reason: a box left on with the television switched off is
 * exactly the state this class must not disturb by restarting the app underneath it.
 *
 * ## Why there is a grace period after every start
 *
 * If a published release breaks the app on launch, the last thing that should happen is this class
 * reacting *faster* the next time the box restarts. A fixed quiet period measured from process start
 * — [SystemClock.elapsedRealtime], immune to the wall clock being wrong or stepped — means a crash
 * loop stays a crash loop instead of compounding into one that keeps re-fetching and re-installing
 * on every fresh attempt. Only the silent install waits for it: downloading installs nothing, and
 * [checkNow] and [installReady] both have a person standing there pressing something, which is not
 * a crash loop.
 */
class Updater(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val packageInstaller = appContext.packageManager.packageInstaller
    private val devicePolicyManager =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val updatesDir = File(appContext.filesDir, "updates")
    private val readyAtElapsedRealtime = SystemClock.elapsedRealtime() + STARTUP_GRACE_MS

    /** One attempt at a time; a poll and a long-press could otherwise overlap. */
    @Volatile
    private var busy = false

    /**
     * The release sitting on disk, checked and newer than this build, or null when there is none.
     * Only ever set after the hash has matched, so the arrow never offers a half-downloaded file.
     */
    private val _ready = MutableStateFlow<ReadyRelease?>(null)
    val ready: StateFlow<ReadyRelease?> = _ready.asStateFlow()

    /**
     * Downloads [release] if it is newer than this build and not already on disk, and announces it
     * through [ready]. On a device owner, once the screen is off and the start-up grace has passed,
     * it also installs it silently. Safe to call on every poll: a release already downloaded and
     * announced costs nothing, and anything else expensive short-circuits on its own.
     */
    fun consider(release: ApkRelease?, screenOn: Boolean) {
        if (release == null || release.version <= BuildConfig.VERSION_CODE) {
            forgetReady()
            return
        }
        val owner = isDeviceOwner()
        val silentAllowed = owner && !screenOn &&
            SystemClock.elapsedRealtime() >= readyAtElapsedRealtime
        // Already downloaded and announced, and nothing more this poll could do with it: not even
        // worth hashing twenty megabytes again to find that out.
        if (_ready.value?.version == release.version && !silentAllowed) return
        if (busy) return
        busy = true

        scope.launch(Dispatchers.IO) {
            try {
                val file = downloadIfNeeded(release) ?: return@launch
                _ready.value = ReadyRelease(release.version, file)
                if (!owner) return@launch
                if (!silentAllowed) {
                    Log.i(TAG, "Release ${release.version} is ready; waiting for the screen to go off")
                    return@launch
                }
                install(file, release.version)
            } finally {
                busy = false
            }
        }
    }

    /**
     * The yellow arrow was pressed: the file is already here, so this goes straight to Android's own
     * install prompt. A file that has gone missing since — storage cleared underneath the app — is
     * simply forgotten, and the next poll downloads it again.
     */
    fun installReady() {
        val current = _ready.value ?: return
        if (!current.file.exists()) {
            _ready.value = null
            return
        }
        installViaSystemUi(current.file, current.version)
    }

    /** Nothing newer to offer: no arrow, and no stale release left taking up room on the box. */
    private fun forgetReady() {
        if (busy) return
        _ready.value = null
        updatesDir.listFiles()?.forEach { it.delete() }
    }

    /** Whether this device can install silently — the same fact [WatchReporter.version] tells the panel. */
    fun isDeviceOwner(): Boolean =
        runCatching { devicePolicyManager.isDeviceOwnerApp(appContext.packageName) }
            .getOrDefault(false)

    /**
     * A person asked, directly — see [Container.checkForUpdate]. If [release] is newer than this
     * build, downloads it and hands it straight to Android's own install prompt; otherwise does
     * nothing, silently, on purpose: nobody who did not ask should see a "you are already up to
     * date" message either.
     */
    fun checkNow(release: ApkRelease?) {
        if (release == null || release.version <= BuildConfig.VERSION_CODE) return
        if (busy) return
        busy = true

        scope.launch(Dispatchers.IO) {
            try {
                val file = downloadIfNeeded(release) ?: return@launch
                _ready.value = ReadyRelease(release.version, file)
                installViaSystemUi(file, release.version)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Hands [file] to the system's own install confirmation screen. Not silent — the point is
     * exactly the opposite of [install] — but it is how a household without device owner gets an
     * update at all.
     */
    private fun installViaSystemUi(file: File, version: Int) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                // NEW_TASK because `appContext` is the application context, not an activity's; the
                // grant is what lets the installer — a different app — read a content:// URI backed
                // by a file only this app owns.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            Log.i(TAG, "Handed release $version to the system installer")
        }.onFailure { error ->
            Log.w(TAG, "Could not launch the installer (${error.javaClass.simpleName})")
        }
    }

    private fun downloadIfNeeded(release: ApkRelease): File? {
        val target = File(updatesDir, "videoclub-${release.version}.apk")
        if (target.exists() && matches(target, release.sha256)) return target

        updatesDir.mkdirs()
        val scratch = File(updatesDir, "${target.name}.tmp")
        // `error(...)` rather than an early `return`: `runCatching`'s block is inline, so a bare
        // `return` here would leave the function through it directly and skip both the cleanup
        // below and the log line that explains why.
        val result = runCatching {
            val request = Request.Builder().url(release.url).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty body")
                scratch.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            if (!matches(scratch, release.sha256)) error("failed its hash check")
            if (!scratch.renameTo(target)) scratch.copyTo(target, overwrite = true)
            target
        }
        // A no-op once the rename above has moved it away; deletes it when anything failed instead.
        scratch.delete()

        return result
            .onSuccess { file ->
                pruneOtherReleases(keep = file)
                Log.i(TAG, "Downloaded release ${release.version}")
            }
            .onFailure { error ->
                Log.w(TAG, "Could not download release ${release.version} (${error.message})")
            }
            .getOrNull()
    }

    /**
     * A blank hash means the document carries none to check against, and that is trusted rather
     * than refused: [ApkRelease.sha256] guards against a download cut short, not against a release
     * nobody signed for — Android's own signature check is what actually stands between this and
     * installing something it should not, and it runs regardless.
     */
    private fun matches(file: File, sha256: String): Boolean {
        if (sha256.isBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(sha256, ignoreCase = true)
    }

    /** Keeps only the release just downloaded, so a superseded one never sits there unbounded. */
    private fun pruneOtherReleases(keep: File) {
        updatesDir.listFiles()?.forEach { file -> if (file != keep) file.delete() }
    }

    /** Only ever reached once [consider] has already confirmed [isDeviceOwner]. */
    private fun install(file: File, version: Int) {
        runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The explicit, documented way to ask for a silent install. Device owner installs
                // were silent before this existed too, but this is what stops a platform update
                // from deciding to ask anyway.
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite(SESSION_NAME, 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(resultIntentSender(sessionId))
            }
            Log.i(TAG, "Installing release $version")
        }.onFailure { error ->
            Log.w(TAG, "Could not install release $version (${error.javaClass.simpleName})")
        }
    }

    /**
     * Where the system reports how the install went. Worth registering, but not worth relying on:
     * a successful self-update kills this very process, and a runtime-registered receiver does not
     * survive that — so most of the time this fires never, and the real evidence that an install
     * landed is the next `version` report showing the new number instead.
     */
    private fun resultIntentSender(sessionId: Int): IntentSender {
        val intent = Intent(ACTION_INSTALL_RESULT).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return pendingIntent.intentSender
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_SUCCESS) {
                Log.i(TAG, "Install finished successfully")
            } else {
                Log.w(TAG, "Install finished with status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    /** Registered once, for the life of the process: an install result can arrive at any time. */
    fun start() {
        ContextCompat.registerReceiver(
            appContext,
            resultReceiver,
            IntentFilter(ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "Updater"
        const val ACTION_INSTALL_RESULT = "com.videoclub.app.INSTALL_RESULT"
        const val SESSION_NAME = "videoclub.apk"
        const val DIGEST_BUFFER_BYTES = 8192

        /**
         * How long after the process starts before this does anything at all. Long enough that a
         * release which breaks startup does not get re-fetched and re-installed by a fast crash
         * loop; short enough that a healthy box is still caught up within the same sitting.
         */
        const val STARTUP_GRACE_MS = 5 * 60 * 1000L
    }
}

/** A release on disk, verified, newer than this build, and one tap away from being installed. */
data class ReadyRelease(val version: Int, val file: File)
