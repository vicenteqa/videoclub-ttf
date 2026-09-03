package com.videoclub.app.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * What household this install belongs to, and the only thing allowed to change it.
 *
 * Neither the account nor the people are compiled into the APK. The last document fetched from the
 * hosted config is cached here, and that cache is the whole of what this app knows about itself
 * between one launch and the next.
 *
 * The cache is read synchronously in the constructor, for the same reason [CatalogStore] reads its
 * preferences that way: it is a few hundred bytes, and the first frame must not wait on a network
 * call. A launch with no connectivity uses the last household that worked, which is almost always
 * the right one.
 *
 * Nothing here throws. An unreadable cache reads back as "no overrides", which lands on an empty
 * account — a state the UI reports rather than papers over.
 */
class ProviderSettings(
    context: Context,
    private val baked: ProviderConfig = ProviderConfig.empty()
) {

    private val file = File(context.filesDir, FILE_NAME)

    private var overrides: ProviderOverrides = readCache()

    /**
     * Read from the catalogue client, from the player and from the guide, on whichever thread they
     * run on, while [apply] may be writing from another. Volatile rather than synchronised: the
     * value is an immutable snapshot, so a reader either sees the whole old account or the whole
     * new one, never a half-updated pair of credentials.
     */
    @Volatile
    var current: ProviderConfig = baked.mergedWith(overrides)
        private set

    /**
     * The household, as the hosted document last described it.
     *
     * Empty until a document has been read. The app has nobody to attribute a viewing to until
     * then, which is exactly what the startup gate waits for.
     */
    @Volatile
    var profiles: List<Profile> = overrides.profiles.orEmpty()
        private set

    /**
     * The last "tune to this channel" the document carried, or null.
     *
     * It never comes from the cache: only [apply] sets it, so a launch with no network brings no
     * stale errands. See [ProviderOverrides.encode].
     */
    @Volatile
    var tuneOrder: TuneOrder? = null
        private set

    /**
     * The last release the document carried, or null.
     *
     * Same shape as [tuneOrder] and for the same reason: only [apply] ever sets it, so a launch with
     * no network compares against nothing rather than against a release that may since have been
     * withdrawn.
     */
    @Volatile
    var apkRelease: ApkRelease? = null
        private set

    /** What the panel would hand to the next person created. Kept only to be written back. */
    val nextProfileId: Int
        get() = overrides.nextProfileId ?: ((profiles.maxOfOrNull { it.id } ?: -1) + 1)

    /**
     * Takes the hosted document as the new truth.
     *
     * Returns whether the *account* moved — a different supplier, user or password — which is the
     * caller's cue to throw away the catalogue and rebuild it: nothing else in the document changes
     * what the supplier would answer. Something else changing (`simple`, `extraChannels`, a renamed
     * profile…) is still adopted and cached below, just without paying for a rebuild that would not
     * find anything different — flipping "Modo Simple" on and back off to try it used to cost a full
     * catalogue rebuild for exactly that reason, comparing the whole config instead of the account.
     */
    fun apply(fetched: ProviderOverrides): Boolean {
        // First of all, and outside the shortcut below: a document whose only change is the "tune
        // to this channel" errand — or a newly published release — does not move the configuration,
        // so the comparison that follows would say "nothing changed" and swallow it whole. And that
        // is this function's normal case, not a rare one: neither touches the account.
        tuneOrder = fetched.tune
        apkRelease = fetched.apk

        val updated = baked.mergedWith(fetched)
        val people = fetched.profiles ?: profiles
        if (updated == current && people == profiles && fetched.nextProfileId == overrides.nextProfileId) {
            return false
        }

        val accountMoved = updated.baseUrl != current.baseUrl ||
            updated.username != current.username ||
            updated.password != current.password ||
            updated.userAgent != current.userAgent
        current = updated
        profiles = people
        overrides = fetched
        writeCache(fetched)
        // Deliberately not logged with values: this object holds the account password.
        Log.i(
            TAG,
            "Adopted a hosted document" +
                (if (accountMoved) " (account changed)" else "") +
                " with ${people.size} profile(s)"
        )
        return accountMoved
    }

    private fun readCache(): ProviderOverrides {
        if (!file.exists()) {
            // Said out loud because the difference matters and is otherwise invisible: no cache
            // means every launch adopts the household afresh and rebuilds the catalogue, which
            // looks like a working app quietly doing the slow thing every single time.
            Log.i(TAG, "No cached household on disk; this launch starts from nothing")
            return ProviderOverrides.NONE
        }
        val cached = runCatching { ProviderOverrides.parse(file.readText()) }
            .onFailure { error -> Log.w(TAG, "Discarding an unreadable provider cache", error) }
            .getOrNull()
        Log.i(
            TAG,
            if (cached == null) "Provider cache was unreadable"
            else "Loaded a cached household (${cached.profiles?.size ?: 0} profiles)"
        )
        return cached ?: ProviderOverrides.NONE
    }

    private fun writeCache(fetched: ProviderOverrides) {
        runCatching {
            // Write beside the real file and rename: a power cut during the write must not leave a
            // truncated file where the credentials are supposed to be.
            val scratch = File(file.parentFile, "$FILE_NAME.tmp")
            scratch.writeText(fetched.encode())
            if (!scratch.renameTo(file)) {
                file.writeText(scratch.readText())
                scratch.delete()
            }
        }
            .onSuccess { Log.i(TAG, "Provider cache written") }
            .onFailure { error -> Log.w(TAG, "Could not save the provider cache", error) }
    }

    private companion object {
        const val TAG = "ProviderSettings"
        const val FILE_NAME = "provider.json"
    }
}
