package com.videoclub.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.compose.runtime.Immutable
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_CATEGORY
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_DETAIL
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_META
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_PROGRESS
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_SOURCE
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_TITLE
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_TITLE_CATEGORY
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_TRACK
import com.videoclub.app.data.CatalogDatabase.Companion.TABLE_WATCHLIST
import org.json.JSONArray
import org.json.JSONObject

/**
 * A progress row on its way to the server, carrying the identity both devices share.
 *
 * [titleId] travels too, but only so the local row can be found again when the write is confirmed:
 * it never leaves this machine.
 */
@Immutable
data class PendingProgress(
    val profileId: Int,
    val mergeKey: String,
    val episodeId: Int,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
    val deleted: Boolean,
    val titleId: Long
)

/** A progress row arriving from the server, not yet translated into this catalogue. */
@Immutable
data class RemoteProgress(
    val profileId: Int,
    val mergeKey: String,
    val episodeId: Int,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
    val deleted: Boolean,
    /** Its place in the household ledger. This is what lets a read stop just before what will not fit. */
    val counter: Long
)

/**
 * A "My list" entry on its way to the server.
 *
 * The same shape as [PendingProgress] with one field fewer: saving something for later has no
 * position and no episode, only the title, who saved it and when that was decided.
 */
@Immutable
data class PendingListEntry(
    val profileId: Int,
    val mergeKey: String,
    val updatedAtMillis: Long,
    val addedAtMillis: Long,
    val deleted: Boolean,
    val titleId: Long
)

/** A "My list" entry arriving from the server, not yet translated into this catalogue. */
@Immutable
data class RemoteListEntry(
    val profileId: Int,
    val mergeKey: String,
    val updatedAtMillis: Long,
    val addedAtMillis: Long,
    val deleted: Boolean,
    /** Its place in the household ledger. This is what lets a read stop just before what will not fit. */
    val counter: Long
)

/** Where the user left a film or an episode. */
@Immutable
data class Progress(
    val titleId: Long,
    val episodeId: Int,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long
) {
    /** 0f–1f, or 0f while the duration is still unknown. */
    val fraction: Float
        get() = if (durationMillis <= 0) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)

    /** Near enough to the end that offering to resume would be silly. */
    val isFinished: Boolean get() = fraction >= FINISHED_FRACTION

    private companion object {
        const val FINISHED_FRACTION = 0.94f
    }
}

/**
 * Which part of the catalogue a read is allowed to come back with, as three lists of categories.
 *
 * All three are needed and none of them does the other's job. [only] is a whitelist and is what
 * builds a child's videoclub; [never] is the veto that goes with it, because the supplier's
 * children's shelves are really its *drawings* shelves and Rick y Morty is on them; [neverAlone] is
 * neither, and it is what keeps sport out of the new-arrival rows without taking a boxing film off
 * the drama shelf it also lives on.
 */
@Immutable
data class Shelves(
    /** Only titles on one of these. Null is the whole shop, which is what everybody but Emma gets. */
    val only: List<Long>? = null,
    /** Never a title on one of these, whatever else it is also on. */
    val never: List<Long> = emptyList(),
    /**
     * Folded name prefixes [never] does not apply to. See [Genres.CHILD_SAFE_TITLES].
     *
     * An exemption from the veto and from nothing else: a name here still has to pass [only], so it
     * can only ever put back a title the supplier had already shelved as a child's.
     */
    val spared: List<String> = emptyList(),
    /** Never a title that is on *nothing but* these. */
    val neverAlone: List<Long> = emptyList()
) {
    companion object {
        /** The unfiltered catalogue: what every read did before there was anything to filter. */
        val Everything = Shelves()
    }
}

/** A title with the user's place in it, which is what a "continue watching" row is. */
@Immutable
data class InProgress(val title: Title, val progress: Progress)

/**
 * Every read and write against the catalogue database.
 *
 * All of it is blocking: callers are expected to be on a background dispatcher already. Keeping the
 * threading decision at the repository layer rather than sprinkling `withContext` here means the
 * long sync can hold one thread for its whole run instead of hopping per statement.
 */
class CatalogStore(context: Context) {

    private val helper = CatalogDatabase(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Epoch millis of the last sync that ran to completion, or 0 when there has never been one.
     *
     * In the database rather than in preferences, and that is the whole point: it describes the rows
     * next to it, so anything that takes the rows away — a schema migration that rebuilds a table,
     * the user clearing storage — takes the timestamp with them. It lived in preferences once, and
     * the first migration to touch the catalogue left the app with no titles and a timestamp saying
     * it had downloaded them an hour ago, which is a day of an empty videoclub that refuses to fill.
     */
    var syncedAtMillis: Long
        get() = helper.readableDatabase
            .rawQuery("SELECT value FROM $TABLE_META WHERE key = ?", arrayOf(KEY_SYNCED_AT))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        private set(value) {
            helper.writableDatabase.replace(
                TABLE_META,
                null,
                ContentValues().apply {
                    put("key", KEY_SYNCED_AT)
                    put("value", value)
                }
            )
        }

    /**
     * Who watched last on this device, by id.
     *
     * Not who is watching now — the app asks that every time it opens. This is only so the cursor
     * lands on the likely answer, which on a television is the difference between one press and
     * three. An id and not a profile, because the person behind it can be renamed or deleted
     * between one launch and the next; whoever reads this has the current list to resolve it
     * against, and falls back to the first person when it resolves to nobody.
     */
    var lastProfileId: Int
        get() = prefs.getInt(KEY_LAST_PROFILE, Profile.DEFAULT.first().id)
        set(value) = prefs.edit().putInt(KEY_LAST_PROFILE, value).apply()

    /** Everything a deleted person leaves behind. Their id can be handed out again afterwards. */
    fun forgetProfile(profileId: Int) {
        val db = helper.writableDatabase
        val id = arrayOf(profileId.toString())
        db.delete(TABLE_PROGRESS, "profile = ?", id)
        db.delete(TABLE_WATCHLIST, "profile = ?", id)
    }

    val hasCatalogue: Boolean
        get() = helper.readableDatabase
            .rawQuery("SELECT 1 FROM $TABLE_TITLE LIMIT 1", null)
            .use(Cursor::moveToFirst)

    // --------------------------------------------------------------------------------- reading

    fun categories(kind: Kind): List<Category> =
        helper.readableDatabase.rawQuery(
            """
            SELECT c.id, c.remote_id, c.name, c.position, COUNT(tc.title_id)
            FROM $TABLE_CATEGORY c
            LEFT JOIN $TABLE_TITLE_CATEGORY tc ON tc.category_id = c.id
            WHERE c.kind = ?
            GROUP BY c.id
            HAVING COUNT(tc.title_id) > 0
            ORDER BY c.position
            """,
            arrayOf(kind.ordinal.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Category(
                            id = cursor.getLong(0),
                            kind = kind,
                            remoteId = cursor.getString(1),
                            name = cursor.getString(2),
                            position = cursor.getInt(3),
                            titleCount = cursor.getInt(4)
                        )
                    )
                }
            }
        }

    /**
     * The titles of one row, which is normally several of the supplier's categories at once.
     *
     * `GROUP BY` rather than a plain join: `TERROR 4K` and `TERROR HD` list the same film, and the
     * app already treats those two listings as one work. Ordered by the best position it holds in
     * any of them, so the supplier's own ordering survives the merge.
     */
    fun titlesInCategories(
        categoryIds: List<Long>,
        limit: Int,
        offset: Int = 0,
        shelves: Shelves = Shelves.Everything
    ): List<Title> {
        if (categoryIds.isEmpty()) return emptyList()
        val filter = shelves.clauses()
        return query(
            "$TITLE_SELECT JOIN $TABLE_TITLE_CATEGORY tc ON tc.title_id = t.id " +
                "WHERE tc.category_id IN (${placeholders(categoryIds)})" + filter.sql.prefixedWith(" AND ") +
                " GROUP BY t.id ORDER BY MIN(tc.position) LIMIT ? OFFSET ?",
            (categoryIds.map(Long::toString) + filter.args + limit.toString() + offset.toString())
                .toTypedArray()
        )
    }

    /** The newest of one kind, or of both when [kind] is null — which is the home screen's row. */
    /** Every category one title sits in. Cheap: it is what `idx_title_category_title` is for. */
    fun categoryIdsOf(titleId: Long): List<Long> =
        helper.readableDatabase.rawQuery(
            "SELECT category_id FROM $TABLE_TITLE_CATEGORY WHERE title_id = ?",
            arrayOf(titleId.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    /** Everything this profile has ever started. What a suggestion must not suggest. */
    fun watchedTitleIds(profile: Profile): List<Long> =
        helper.readableDatabase.rawQuery(
            "SELECT DISTINCT title_id FROM $TABLE_PROGRESS WHERE profile = ?",
            arrayOf(profile.id.toString())
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    /**
     * Titles that share the most of [categoryIds] with something already watched.
     *
     * The count is what orders it and it is never selected: two films sharing `RUSSELL CROWE` and
     * `RIDLEY SCOTT` beat one sharing only `ACCION`, and that is the whole ranking. Rating breaks
     * the ties, because at equal kinship the better film is the better suggestion.
     */
    fun similarTo(
        categoryIds: List<Long>,
        excluding: List<Long>,
        limit: Int,
        shelves: Shelves = Shelves.Everything
    ): List<Title> {
        if (categoryIds.isEmpty()) return emptyList()
        val filter = shelves.clauses()
        val skip = if (excluding.isEmpty()) {
            ""
        } else {
            " AND t.id NOT IN (${excluding.joinToString(",") { "?" }})"
        }
        return query(
            "$TITLE_SELECT JOIN $TABLE_TITLE_CATEGORY tc ON tc.title_id = t.id " +
                "WHERE tc.category_id IN (${placeholders(categoryIds)})" + skip +
                filter.sql.prefixedWith(" AND ") +
                " GROUP BY t.id ORDER BY COUNT(*) DESC, t.rating IS NULL, t.rating DESC LIMIT ?",
            (
                categoryIds.map(Long::toString) +
                    excluding.map(Long::toString) +
                    filter.args +
                    limit.toString()
                ).toTypedArray()
        )
    }

    fun recentlyAdded(kind: Kind?, limit: Int, shelves: Shelves = Shelves.Everything): List<Title> {
        val filter = shelves.clauses()
        val where = listOfNotNull(kind?.let { "t.kind = ?" }, filter.sql.takeIf { it.isNotEmpty() })
        return query(
            "$TITLE_SELECT " + where.joinToString(" AND ").prefixedWith("WHERE ") +
                " ORDER BY t.added DESC LIMIT ?",
            (listOfNotNull(kind?.ordinal?.toString()) + filter.args + limit.toString()).toTypedArray()
        )
    }

    /**
     * Substring search over the folded names.
     *
     * Titles that *start* with what was typed come first — somebody typing `blade` wants
     * `Blade Runner` before `Kill Bill: la venganza de la novia de Blade`. After that it is by
     * rating, because with 33,000 works the second page is never read.
     */
    fun search(query: String, limit: Int, shelves: Shelves = Shelves.Everything): List<Title> {
        val folded = TitleNaming.fold(query)
        if (folded.isEmpty()) return emptyList()
        val filter = shelves.clauses()
        return query(
            "$TITLE_SELECT WHERE t.search_name LIKE ?" + filter.sql.prefixedWith(" AND ") +
                " ORDER BY CASE WHEN t.search_name LIKE ? THEN 0 ELSE 1 END, " +
                "t.rating IS NULL, t.rating DESC, t.added DESC LIMIT ?",
            (listOf("%$folded%") + filter.args + "$folded%" + limit.toString()).toTypedArray()
        )
    }

    fun title(id: Long): Title? =
        query("$TITLE_SELECT WHERE t.id = ?", arrayOf(id.toString()))
            .firstOrNull()
            ?.copy(sources = sources(id))

    fun sources(titleId: Long): List<Source> =
        helper.readableDatabase.rawQuery(
            "SELECT remote_id, quality, container FROM $TABLE_SOURCE WHERE title_id = ? ORDER BY quality",
            arrayOf(titleId.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Source(
                            remoteId = cursor.getInt(0),
                            quality = quality(cursor.getInt(1)),
                            container = cursor.getString(2)
                        )
                    )
                }
            }
        }

    fun detail(titleId: Long): TitleDetail? =
        helper.readableDatabase.rawQuery(
            "SELECT plot, genre, cast_list, director, release_date, duration, tmdb_id, " +
                "backdrop_url, trailer, video_codec, video_height, audio_codec, bitrate " +
                "FROM $TABLE_DETAIL WHERE title_id = ?",
            arrayOf(titleId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            TitleDetail(
                plot = cursor.textOrNull(0),
                genre = cursor.textOrNull(1),
                cast = cursor.textOrNull(2),
                director = cursor.textOrNull(3),
                releaseDate = cursor.textOrNull(4),
                durationSeconds = cursor.intOrNull(5),
                tmdbId = cursor.textOrNull(6),
                backdropUrl = cursor.textOrNull(7),
                trailer = cursor.textOrNull(8),
                videoCodec = cursor.textOrNull(9),
                videoHeight = cursor.intOrNull(10),
                audioCodec = cursor.textOrNull(11),
                bitrateBps = cursor.longOrNull(12)
            )
        }

    /** True when [detail] has already been fetched, so the screen can skip the network call. */
    fun hasDetail(titleId: Long): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_DETAIL WHERE title_id = ? AND fetched_at > 0",
            arrayOf(titleId.toString())
        ).use(Cursor::moveToFirst)

    /**
     * One entry per title, at the episode last left unfinished.
     *
     * Progress is stored per episode, so a series watched across two evenings has as many rows as
     * episodes started, and without the `GROUP BY` the same series appeared several times in the
     * row — which is both wrong to read and fatal, since a `LazyRow` keyed by title id throws on a
     * repeated key. `MAX(p.updated_at)` with the other columns bare is SQLite's documented way of
     * saying "the rest of the fields from the row that won the maximum", which here is the most
     * recent episode.
     */
    fun continueWatching(profile: Profile, limit: Int): List<InProgress> =
        lastProgressPerTitle(profile, "AND p.position_ms * 100 < p.duration_ms * 94", limit)

    /**
     * The same, but keeping the titles whose last episode was watched to the end.
     *
     * The home screen wants those too: a series whose episode four is finished is not finished, it
     * is waiting at episode five. Working out whether there *is* an episode five costs a request, so
     * it happens a layer up, in [CatalogRepository].
     */
    fun recentProgress(profile: Profile, limit: Int): List<InProgress> =
        lastProgressPerTitle(profile, "", limit)

    private fun lastProgressPerTitle(
        profile: Profile,
        extraWhere: String,
        limit: Int
    ): List<InProgress> =
        helper.readableDatabase.rawQuery(
            """
            SELECT p.title_id, p.episode_id, p.position_ms, p.duration_ms, MAX(p.updated_at)
            FROM $TABLE_PROGRESS p
            WHERE p.profile = ? AND p.deleted = 0 AND p.duration_ms > 0 $extraWhere
            GROUP BY p.title_id
            ORDER BY MAX(p.updated_at) DESC LIMIT ?
            """,
            arrayOf(profile.id.toString(), limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val progress = Progress(
                        titleId = cursor.getLong(0),
                        episodeId = cursor.getInt(1),
                        positionMillis = cursor.getLong(2),
                        durationMillis = cursor.getLong(3),
                        updatedAtMillis = cursor.getLong(4)
                    )
                    // A title withdrawn by the supplier since it was watched leaves its progress
                    // row behind; skip it rather than drawing a hole in the row.
                    title(progress.titleId)?.let { add(InProgress(it, progress)) }
                }
            }
        }

    fun watchlist(profile: Profile): List<Title> =
        query(
            "$TITLE_SELECT JOIN $TABLE_WATCHLIST w ON w.title_id = t.id " +
                "WHERE w.profile = ? AND w.deleted = 0 ORDER BY w.added_at DESC",
            arrayOf(profile.id.toString())
        )

    fun isInWatchlist(profile: Profile, titleId: Long): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_WATCHLIST WHERE profile = ? AND title_id = ? AND deleted = 0",
            arrayOf(profile.id.toString(), titleId.toString())
        ).use(Cursor::moveToFirst)

    fun progress(profile: Profile, titleId: Long, episodeId: Int = 0): Progress? =
        helper.readableDatabase.rawQuery(
            "SELECT position_ms, duration_ms, updated_at FROM $TABLE_PROGRESS " +
                "WHERE profile = ? AND title_id = ? AND episode_id = ? AND deleted = 0",
            arrayOf(profile.id.toString(), titleId.toString(), episodeId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Progress(titleId, episodeId, cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        }

    /**
     * The most recently watched part of a title, whichever part that was.
     *
     * For a film this is the same row [progress] returns. For a series it is what makes the Play
     * button mean "carry on with episode 4" rather than "start again from the pilot".
     */
    fun lastProgress(profile: Profile, titleId: Long): Progress? =
        helper.readableDatabase.rawQuery(
            "SELECT episode_id, position_ms, duration_ms, updated_at FROM $TABLE_PROGRESS " +
                "WHERE profile = ? AND title_id = ? AND deleted = 0 " +
                "ORDER BY updated_at DESC LIMIT 1",
            arrayOf(profile.id.toString(), titleId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            Progress(
                titleId = titleId,
                episodeId = cursor.getInt(0),
                positionMillis = cursor.getLong(1),
                durationMillis = cursor.getLong(2),
                updatedAtMillis = cursor.getLong(3)
            )
        }

    // --------------------------------------------------------------------------------- writing

    fun saveProgress(
        profile: Profile,
        titleId: Long,
        episodeId: Int,
        positionMillis: Long,
        durationMillis: Long,
        nowMillis: Long
    ) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_PROGRESS,
            null,
            ContentValues().apply {
                put("profile", profile.id)
                put("title_id", titleId)
                put("episode_id", episodeId)
                put("position_ms", positionMillis)
                put("duration_ms", durationMillis)
                put("updated_at", nowMillis)
                // Pending. Cleared only once the server confirms it has the row.
                put("dirty", 1)
                put("deleted", 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Forgets a title's history, every episode of it, for one profile.
     *
     * All the episodes rather than the one on screen: the row shows one card per series, so removing
     * only the latest would put the previous episode back in its place, which is not what anybody
     * meant by taking it off the list. Playing it again writes a new row and it returns.
     */
    fun forgetProgress(profile: Profile, titleId: Long, nowMillis: Long = System.currentTimeMillis()) {
        // Marked, not deleted. Removing something from "Continue watching" is a decision that has to
        // reach the household's other device, and a row that is gone can be told to nobody. Reads
        // filter it out, so it disappears from the screen just the same.
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE_PROGRESS SET deleted = 1, dirty = 1, updated_at = ? " +
                "WHERE profile = ? AND title_id = ?",
            arrayOf<Any>(nowMillis, profile.id, titleId)
        )
    }

    /** What this person was last watching this title in, or null if they never said. */
    fun tracks(profile: Profile, titleId: Long): TrackChoice? =
        helper.readableDatabase.rawQuery(
            "SELECT audio, subtitle FROM $TABLE_TRACK WHERE profile = ? AND title_id = ?",
            arrayOf(profile.id.toString(), titleId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            TrackChoice(audio = cursor.textOrNull(0), subtitle = cursor.textOrNull(1))
        }

    /** Written every time the player reports its position, which is often enough and cheap enough. */
    fun saveTracks(profile: Profile, titleId: Long, choice: TrackChoice) {
        if (choice.isEmpty) return
        helper.writableDatabase.replace(
            TABLE_TRACK,
            null,
            ContentValues().apply {
                put("profile", profile.id)
                put("title_id", titleId)
                put("audio", choice.audio)
                put("subtitle", choice.subtitle)
            }
        )
    }

    /**
     * Saves or removes a title from that person's list.
     *
     * Removing marks the row rather than deleting it: a row that is gone cannot be sent, and the
     * device next door would go on showing it forever. `added_at` is touched only when saving,
     * because it is the list's order; `updated_at` is touched always, because it is what decides
     * who wins when two devices disagree.
     */
    fun setInWatchlist(profile: Profile, titleId: Long, inList: Boolean, nowMillis: Long) {
        val db = helper.writableDatabase
        val previous = db.rawQuery(
            "SELECT added_at FROM $TABLE_WATCHLIST WHERE profile = ? AND title_id = ?",
            arrayOf(profile.id.toString(), titleId.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

        db.insertWithOnConflict(
            TABLE_WATCHLIST,
            null,
            ContentValues().apply {
                put("profile", profile.id)
                put("title_id", titleId)
                put("added_at", if (inList) nowMillis else previous)
                put("updated_at", nowMillis)
                put("dirty", 1)
                put("deleted", if (inList) 0 else 1)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun putDetail(titleId: Long, detail: TitleDetail, nowMillis: Long) =
        writeDetail(titleId, detail, nowMillis, SQLiteDatabase.CONFLICT_REPLACE)

    /**
     * Stores the metadata a series listing already carries, without clobbering a real fetch.
     *
     * `fetched_at` stays 0, which is what [hasDetail] reads: the listing gives plot, cast and a
     * backdrop, but not the episode list, so the detail screen still has a call to make.
     */
    fun putListingDetail(titleId: Long, detail: TitleDetail) =
        writeDetail(titleId, detail, 0L, SQLiteDatabase.CONFLICT_IGNORE)

    private fun writeDetail(titleId: Long, detail: TitleDetail, fetchedAt: Long, conflict: Int) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_DETAIL,
            null,
            ContentValues().apply {
                put("title_id", titleId)
                put("plot", detail.plot)
                put("genre", detail.genre)
                put("cast_list", detail.cast)
                put("director", detail.director)
                put("release_date", detail.releaseDate)
                put("duration", detail.durationSeconds)
                put("tmdb_id", detail.tmdbId)
                put("backdrop_url", detail.backdropUrl)
                put("trailer", detail.trailer)
                put("video_codec", detail.videoCodec)
                put("video_height", detail.videoHeight)
                put("audio_codec", detail.audioCodec)
                put("bitrate", detail.bitrateBps)
                put("fetched_at", fetchedAt)
            },
            conflict
        )
    }

    /**
     * Opens a sync generation.
     *
     * Everything written through the returned session is marked with [stamp]; [SyncSession.sweep]
     * then deletes whatever still carries an older one, which is precisely the set of rows the
     * supplier has withdrawn. The catalogue stays readable throughout — the home screen is never
     * emptied to be refilled.
     */
    fun beginSync(stamp: Long): SyncSession = SyncSession(helper.writableDatabase, stamp)

    fun markSynced(nowMillis: Long) {
        syncedAtMillis = nowMillis
    }

    // ---------------------------------------------------------------------------------- helpers

    /** One `WHERE` fragment and the arguments that go with it, in the order the fragment names them. */
    private class Filter(val sql: String, val args: List<String>)

    /**
     * The three lists as SQL, or as nothing at all when there is nothing to filter.
     *
     * Written as subqueries against `title_category` rather than as joins because two of the three
     * are negations, and a join cannot express "is on no shelf of this list" without turning the
     * row count into something the `LIMIT` no longer applies to.
     */
    private fun Shelves.clauses(): Filter {
        val sql = mutableListOf<String>()
        val args = mutableListOf<String>()
        only?.let { ids ->
            sql += "t.id IN (SELECT title_id FROM $TABLE_TITLE_CATEGORY " +
                "WHERE category_id IN (${placeholders(ids)}))"
            args += ids.map(Long::toString)
        }
        if (never.isNotEmpty()) {
            val veto = "t.id NOT IN (SELECT title_id FROM $TABLE_TITLE_CATEGORY " +
                "WHERE category_id IN (${placeholders(never)}))"
            args += never.map(Long::toString)
            sql += if (spared.isEmpty()) {
                veto
            } else {
                // No `ESCAPE`: every prefix has been through `TitleNaming.fold`, which leaves
                // nothing but letters, digits and single spaces — so neither `%` nor `_` can reach
                // this pattern from the table above.
                args += spared.map { "$it%" }
                "($veto OR " + spared.joinToString(" OR ") { "t.search_name LIKE ?" } + ")"
            }
        }
        if (neverAlone.isNotEmpty()) {
            sql += "EXISTS (SELECT 1 FROM $TABLE_TITLE_CATEGORY x WHERE x.title_id = t.id " +
                "AND x.category_id NOT IN (${placeholders(neverAlone)}))"
            args += neverAlone.map(Long::toString)
        }
        return Filter(sql.joinToString(" AND "), args)
    }

    private fun query(sql: String, args: Array<String>): List<Title> =
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Title(
                            id = cursor.getLong(0),
                            kind = if (cursor.getInt(1) == Kind.Movie.ordinal) Kind.Movie else Kind.Series,
                            name = cursor.getString(2),
                            year = cursor.intOrNull(3),
                            posterUrl = cursor.textOrNull(4),
                            rating = if (cursor.isNull(5)) null else cursor.getDouble(5),
                            addedSeconds = cursor.getLong(6),
                            bestQuality = cursor.intOrNull(7)?.let(::quality)
                        )
                    )
                }
            }
        }

    // ------------------------------------------------------------- el progreso, entre aparatos

    /**
     * How far this device has read the household's shared ledger.
     *
     * A number that only goes up, and the server's rather than ours: asking for "whatever came after
     * 412" is what makes catching up cost one short answer instead of the whole history every time.
     */
    var syncCounter: Long
        get() = helper.readableDatabase
            .rawQuery("SELECT value FROM $TABLE_META WHERE key = ?", arrayOf(KEY_SYNC_COUNTER))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        set(value) {
            helper.writableDatabase.replace(
                TABLE_META,
                null,
                ContentValues().apply {
                    put("key", KEY_SYNC_COUNTER)
                    put("value", value)
                }
            )
        }

    /**
     * What this device has written and has not managed to send yet.
     *
     * It leaves with `merge_key` rather than `title_id` because `title_id` is a number local to this
     * machine: `AUTOINCREMENT` hands it out in whatever order the supplier's listings arrive, so
     * 4711 here and 4711 on the tablet are different films. `merge_key` is what both compute alike.
     *
     * A row whose title is no longer in the catalogue cannot be translated and waits instead: it is
     * not lost, and the moment a catalogue sync brings that title back, it goes out.
     */
    fun pendingProgress(limit: Int): List<PendingProgress> =
        helper.readableDatabase.rawQuery(
            """
            SELECT p.profile, t.merge_key, p.episode_id, p.position_ms, p.duration_ms,
                   p.updated_at, p.deleted, p.title_id
            FROM $TABLE_PROGRESS p JOIN $TABLE_TITLE t ON t.id = p.title_id
            WHERE p.dirty = 1 ORDER BY p.updated_at LIMIT ?
            """,
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingProgress(
                            profileId = cursor.getInt(0),
                            mergeKey = cursor.getString(1),
                            episodeId = cursor.getInt(2),
                            positionMillis = cursor.getLong(3),
                            durationMillis = cursor.getLong(4),
                            updatedAtMillis = cursor.getLong(5),
                            deleted = cursor.getInt(6) != 0,
                            titleId = cursor.getLong(7)
                        )
                    )
                }
            }
        }

    /**
     * Marks as sent what the server accepted.
     *
     * The condition on `updated_at` is what makes this safe while somebody is watching: if the
     * position moved on between sending and confirming, the row stays dirty and is sent again.
     * Without it, that progress would stay on this device alone.
     */
    fun markProgressSynced(rows: List<PendingProgress>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                db.execSQL(
                    "UPDATE $TABLE_PROGRESS SET dirty = 0 " +
                        "WHERE profile = ? AND title_id = ? AND episode_id = ? AND updated_at = ?",
                    arrayOf<Any>(row.profileId, row.titleId, row.episodeId, row.updatedAtMillis)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Writes what the household's other devices have watched.
     *
     * Returns the titles it could not place: `merge_key`s this catalogue does not hold yet, almost
     * always because the first catalogue sync has not finished. The caller uses them to avoid
     * marking that stretch of the ledger as read and to ask for it again later, which is the
     * difference between "not yet" and losing it.
     *
     * The most recent stamp wins, exactly as on the server: not the furthest along. If the furthest
     * along won, starting something again from the beginning would be impossible.
     */
    fun applyRemoteProgress(rows: List<RemoteProgress>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val unresolved = mutableListOf<String>()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val titleId = titleIdForMergeKey(db, row.mergeKey)
                if (titleId == null) {
                    unresolved += row.mergeKey
                    return@forEach
                }
                val local = db.rawQuery(
                    "SELECT updated_at FROM $TABLE_PROGRESS " +
                        "WHERE profile = ? AND title_id = ? AND episode_id = ?",
                    arrayOf(
                        row.profileId.toString(), titleId.toString(), row.episodeId.toString()
                    )
                ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
                if (local >= row.updatedAtMillis) return@forEach

                db.insertWithOnConflict(
                    TABLE_PROGRESS,
                    null,
                    ContentValues().apply {
                        put("profile", row.profileId)
                        put("title_id", titleId)
                        put("episode_id", row.episodeId)
                        put("position_ms", row.positionMillis)
                        put("duration_ms", row.durationMillis)
                        put("updated_at", row.updatedAtMillis)
                        // It came from the server: it is already there, nothing to send.
                        put("dirty", 0)
                        put("deleted", if (row.deleted) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return unresolved
    }

    /**
     * What this device has added to or removed from "My list" and has not managed to send yet.
     *
     * Same as [pendingProgress], and for the same reason: it leaves with `merge_key`, because
     * `title_id` is a number local to this machine. A row whose title is no longer in the catalogue
     * waits instead of being lost.
     */
    fun pendingWatchlist(limit: Int): List<PendingListEntry> =
        helper.readableDatabase.rawQuery(
            """
            SELECT w.profile, t.merge_key, w.updated_at, w.added_at, w.deleted, w.title_id
            FROM $TABLE_WATCHLIST w JOIN $TABLE_TITLE t ON t.id = w.title_id
            WHERE w.dirty = 1 ORDER BY w.updated_at LIMIT ?
            """,
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PendingListEntry(
                            profileId = cursor.getInt(0),
                            mergeKey = cursor.getString(1),
                            updatedAtMillis = cursor.getLong(2),
                            addedAtMillis = cursor.getLong(3),
                            deleted = cursor.getInt(4) != 0,
                            titleId = cursor.getLong(5)
                        )
                    )
                }
            }
        }

    /**
     * Marks as sent what the server accepted.
     *
     * The condition on `updated_at` does the same as in [markProgressSynced]: if somebody touched
     * that title again between sending and confirming, the row stays dirty and is sent once more.
     */
    fun markWatchlistSynced(rows: List<PendingListEntry>) {
        if (rows.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                db.execSQL(
                    "UPDATE $TABLE_WATCHLIST SET dirty = 0 " +
                        "WHERE profile = ? AND title_id = ? AND updated_at = ?",
                    arrayOf<Any>(row.profileId, row.titleId, row.updatedAtMillis)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Writes what the household's other devices have added or removed.
     *
     * Returns the titles it could not place, exactly as [applyRemoteProgress] does and for the same
     * reason: so that "not yet" does not turn into "lost".
     */
    fun applyRemoteWatchlist(rows: List<RemoteListEntry>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val unresolved = mutableListOf<String>()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val titleId = titleIdForMergeKey(db, row.mergeKey)
                if (titleId == null) {
                    unresolved += row.mergeKey
                    return@forEach
                }
                val local = db.rawQuery(
                    "SELECT updated_at FROM $TABLE_WATCHLIST WHERE profile = ? AND title_id = ?",
                    arrayOf(row.profileId.toString(), titleId.toString())
                ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
                if (local >= row.updatedAtMillis) return@forEach

                db.insertWithOnConflict(
                    TABLE_WATCHLIST,
                    null,
                    ContentValues().apply {
                        put("profile", row.profileId)
                        put("title_id", titleId)
                        put("added_at", row.addedAtMillis)
                        put("updated_at", row.updatedAtMillis)
                        // It came from the server: it is already there, nothing to send.
                        put("dirty", 0)
                        put("deleted", if (row.deleted) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return unresolved
    }

    private fun titleIdForMergeKey(db: SQLiteDatabase, mergeKey: String): Long? =
        db.rawQuery(
            "SELECT id FROM $TABLE_TITLE WHERE merge_key = ?", arrayOf(mergeKey)
        ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private companion object {
        const val PREFS_NAME = "videoclub"
        /** A row of [TABLE_META], not a preference: see [syncedAtMillis]. */
        const val KEY_SYNCED_AT = "synced_at"
        const val KEY_SYNC_COUNTER = "sync_counter"
        const val KEY_LAST_PROFILE = "last_profile"

        /**
         * The seven columns every list of titles reads, in the order [query] expects. The quality
         * aggregate is a subquery rather than a join so that a title with no source row yet — which
         * can happen mid-sync — still comes back instead of vanishing.
         */
        const val TITLE_SELECT =
            "SELECT t.id, t.kind, t.name, t.year, t.poster_url, t.rating, t.added, " +
                "(SELECT MIN(s.quality) FROM $TABLE_SOURCE s WHERE s.title_id = t.id) " +
                "FROM $TABLE_TITLE t"

        fun placeholders(ids: List<Long>): String = ids.joinToString(",") { "?" }

        /** `""` stays `""`; anything else gets the keyword it needs in front of it. */
        fun String.prefixedWith(keyword: String): String = if (isEmpty()) this else keyword + this

        fun quality(ordinal: Int): Quality =
            Quality.entries.getOrElse(ordinal) { Quality.Hd }

        fun Cursor.textOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
        fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)
        fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    }
}

/**
 * One run of the catalogue sync, holding its compiled statements open.
 *
 * The sync executes roughly 700,000 statements — one title lookup, one source and one category link
 * for each of 232,000 listings. Compiling each of those once instead of once per row is the
 * difference between seconds and minutes, and the in-memory [ids] map removes the title lookup
 * entirely after the first time a work is seen.
 */
class SyncSession internal constructor(
    private val db: SQLiteDatabase,
    private val stamp: Long
) {
    /** `merge_key` to local row id, for every work this run has already filed. ~33,000 entries. */
    private val ids = HashMap<String, Long>(40_000)

    private val insertTitle: SQLiteStatement = db.compileStatement(
        "INSERT OR IGNORE INTO $TABLE_TITLE " +
            "(kind, merge_key, name, search_name, year, poster_url, rating, added, stamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )
    private val touchTitle: SQLiteStatement = db.compileStatement(
        // A work seen again in a later category may bring a poster or a rating the first listing
        // lacked, and `added` should end up as the newest of all its listings.
        "UPDATE $TABLE_TITLE SET stamp = ?, " +
            "poster_url = COALESCE(poster_url, ?), rating = COALESCE(rating, ?), " +
            "added = MAX(added, ?) WHERE merge_key = ?"
    )
    private val selectId: SQLiteStatement =
        db.compileStatement("SELECT id FROM $TABLE_TITLE WHERE merge_key = ?")
    private val insertSource: SQLiteStatement = db.compileStatement(
        "INSERT OR REPLACE INTO $TABLE_SOURCE (title_id, remote_id, quality, container, stamp) " +
            "VALUES (?, ?, ?, ?, ?)"
    )
    private val insertLink: SQLiteStatement = db.compileStatement(
        "INSERT OR REPLACE INTO $TABLE_TITLE_CATEGORY (category_id, title_id, position, stamp) " +
            "VALUES (?, ?, ?, ?)"
    )

    fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        try {
            return body().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Inserts or refreshes a category and returns its local row id. */
    fun putCategory(kind: Kind, remote: RemoteCategory, position: Int): Long {
        db.insertWithOnConflict(
            TABLE_CATEGORY,
            null,
            ContentValues().apply {
                put("kind", kind.ordinal)
                put("remote_id", remote.remoteId)
                put("name", remote.name)
                put("position", position)
                put("stamp", stamp)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        db.execSQL(
            "UPDATE $TABLE_CATEGORY SET name = ?, position = ?, stamp = ? WHERE kind = ? AND remote_id = ?",
            arrayOf<Any>(remote.name, position, stamp, kind.ordinal, remote.remoteId)
        )
        return db.rawQuery(
            "SELECT id FROM $TABLE_CATEGORY WHERE kind = ? AND remote_id = ?",
            arrayOf(kind.ordinal.toString(), remote.remoteId)
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    }

    /**
     * Files one catalogue listing: creates the work if this is the first sighting, records this
     * encode as one of its sources, and links it to the category being read.
     */
    fun putListing(kind: Kind, categoryId: Long, listing: Listing, position: Int): Long {
        val parsed = TitleNaming.parse(listing.rawName)
        val key = TitleNaming.mergeKey(kind, parsed.name, parsed.year)

        val titleId = ids.getOrPut(key) {
            insertTitle.run {
                clearBindings()
                bindLong(1, kind.ordinal.toLong())
                bindString(2, key)
                bindString(3, parsed.name)
                bindString(4, TitleNaming.fold(parsed.name))
                parsed.year?.let { bindLong(5, it.toLong()) } ?: bindNull(5)
                listing.posterUrl?.let { bindString(6, it) } ?: bindNull(6)
                listing.rating?.let { bindDouble(7, it) } ?: bindNull(7)
                bindLong(8, listing.addedSeconds)
                bindLong(9, stamp)
                executeInsert()
            }
            selectId.run {
                clearBindings()
                bindString(1, key)
                simpleQueryForLong()
            }
        }

        touchTitle.run {
            clearBindings()
            bindLong(1, stamp)
            listing.posterUrl?.let { bindString(2, it) } ?: bindNull(2)
            listing.rating?.let { bindDouble(3, it) } ?: bindNull(3)
            bindLong(4, listing.addedSeconds)
            bindString(5, key)
            executeUpdateDelete()
        }

        insertSource.run {
            clearBindings()
            bindLong(1, titleId)
            bindLong(2, listing.remoteId.toLong())
            bindLong(3, parsed.quality.ordinal.toLong())
            bindString(4, listing.container)
            bindLong(5, stamp)
            executeInsert()
        }

        insertLink.run {
            clearBindings()
            bindLong(1, categoryId)
            bindLong(2, titleId)
            bindLong(3, position.toLong())
            bindLong(4, stamp)
            executeInsert()
        }

        return titleId
    }

    /** Deletes everything the supplier no longer publishes, then closes the statements. */
    fun sweep() {
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM $TABLE_TITLE_CATEGORY WHERE stamp <> ?", arrayOf(stamp))
            db.execSQL("DELETE FROM $TABLE_SOURCE WHERE stamp <> ?", arrayOf(stamp))
            db.execSQL("DELETE FROM $TABLE_CATEGORY WHERE stamp <> ?", arrayOf(stamp))
            db.execSQL("DELETE FROM $TABLE_TITLE WHERE stamp <> ?", arrayOf(stamp))
            db.execSQL("DELETE FROM $TABLE_DETAIL WHERE title_id NOT IN (SELECT id FROM $TABLE_TITLE)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        close()
    }

    fun close() {
        listOf(insertTitle, touchTitle, selectId, insertSource, insertLink).forEach(SQLiteStatement::close)
    }
}
