package com.videoclub.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Leaves the device clean when another household's APK is installed over the top.
 *
 * Every household shares one `applicationId`, deliberately: that way a household that already has
 * the app receives the next version as an update rather than as a second app. The price is that
 * installing another household's APK over it **keeps the previous one's data** — Android only wipes
 * that on uninstall — and what stays on disk is not an internal detail: it is the previous
 * household's catalogue, "Continue watching" and "My list", with film titles and people's names in
 * them.
 *
 * The hosted document being fetched at launch does not fix this on its own. When the account
 * changes, [CatalogRepository.refresh] rebuilds the catalogue, but that is nine hundred requests and
 * a couple of minutes during which what is on screen is still the other household's shop. And a
 * device that starts with no network never fixes it at all.
 *
 * So the household is decided here, up front and before anything opens the database: the APK
 * carries its document's URL compiled in — `BuildConfig.REMOTE_CONFIG_URL`, one per household — and
 * this is the only place that compares it against the one the previous installation left stamped.
 *
 * **It wipes by sweep, not by a list of names.** Databases, files, caches and preferences,
 * everything but the stamp. Enumerating `catalogo.db`, `provider.json` and friends would be a copy
 * of the truth that lives in [CatalogStore] and [ChannelStore], and the day somebody adds a new
 * cache that copy would quietly fall short — with another household's data inside it. Here, what is
 * not recognised goes, which for a device that has just changed hands is the right answer.
 *
 * **A device with no stamp is left alone.** That is every installation older than this version:
 * wiping their disk for failing to recognise themselves would cost them the progress they have not
 * synchronised yet, which is precisely the part that cannot be recovered. They get stamped and life
 * goes on.
 */
fun wipeIfHouseholdChanged(context: Context, remoteConfigUrl: String): Boolean {
    // A build with no compiled URL has no household to compare against; there is nothing to decide.
    if (remoteConfigUrl.isBlank()) return false

    val stamp = context.getSharedPreferences(STAMP_PREFS, Context.MODE_PRIVATE)
    val previous = stamp.getString(KEY_URL, null)
    if (previous == remoteConfigUrl) return false

    // `commit` rather than `apply` on both paths: the very next thing that happens is those files
    // being opened again, and a half-written stamp turns the next launch into another wipe.
    if (previous == null) {
        Log.i(TAG, "First launch with a household stamp; nothing is wiped")
        stamp.edit().putString(KEY_URL, remoteConfigUrl).commit()
        return false
    }

    // Said out loud and without the URL: it carries the secret path, which acts as the credential.
    Log.w(TAG, "This APK belongs to a different household than the installed one; wiping what was here")

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
 * What this app's shared preferences are called.
 *
 * There is no API to ask, so the directory Android keeps them in is read instead. If it ever stops
 * being there, this returns an empty list and the rest of the wipe still happens: better to clean
 * too little than to crash during startup.
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
