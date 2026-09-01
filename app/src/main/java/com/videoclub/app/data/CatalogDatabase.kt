package com.videoclub.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The catalogue on disk.
 *
 * Raw SQLite rather than Room, for the same reason there is no dependency-injection framework: the
 * schema is six tables that fit on one screen, and an annotation processor would cost more build
 * time than it saves typing.
 *
 * There is no full-text index either, and that is a consequence of the merge. The supplier
 * publishes 232,000 listings, but they are only ~33,000 distinct works — each one repeated across
 * some forty categories and two to four encodes. A `LIKE` scan over 33,000 short folded names is a
 * few milliseconds, which is well inside the time it takes somebody to type the next letter.
 *
 * Everything here except [TABLE_PROGRESS] and [TABLE_WATCHLIST] is a cache of somebody else's
 * catalogue and can be thrown away and fetched again. Those two are the only rows the user wrote,
 * and they are also the reason [onUpgrade] is careful: they hold local row ids, which a rebuild of
 * the catalogue would quietly reissue to other films.
 */
class CatalogDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // The catalogue sync is one long run of small writes. WAL keeps reads on the home screen
        // from blocking behind it, so the app stays usable while it fills.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
    }

    /**
     * One step per version, and only the steps that are needed.
     *
     * This used to drop every table and re-download, on the grounds that the catalogue is a cache of
     * somebody else's data. It is — but the two tables the *user* wrote point into it by local row
     * id, and those ids come from `AUTOINCREMENT` as the sync walks the supplier's listings. Rebuild
     * the catalogue and every id is reissued in whatever order that day's listings happen to arrive,
     * which turns a resume row into ten films nobody was watching. Nothing pointed this out for as
     * long as there was only one version.
     *
     * So a step now touches only what it changed. A future change to a catalogue table can still
     * drop that table and let the sync refill it — that is safe for [TABLE_CATEGORY] and
     * [TABLE_TITLE_CATEGORY], and never safe for [TABLE_TITLE] while anything references its ids.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) addProfileToUserTables(db)
        // Deliberately not carried over from preferences: the old value describes a catalogue that
        // this file may no longer contain, so the first launch after the upgrade downloads again.
        if (oldVersion < 3) db.execSQL(CREATE_META)
        if (oldVersion < 4) db.execSQL(CREATE_TRACK)
        if (oldVersion < 5) addSyncColumns(db)
        if (oldVersion < 6) addListSyncColumns(db)
    }

    /**
     * Version 5: two columns on `progress` so that it can be synchronised.
     *
     * `dirty` marks what this device has written and has not managed to send yet. Existing rows are
     * set to 1: they predate synchronisation, so the server does not know them, and losing them
     * would mean losing everybody's "Continue watching" on the day the app is updated.
     *
     * `deleted` is the tombstone. Removing something from "Continue watching" is a decision and has
     * to reach the other device; a deleted row cannot be sent, so it is marked and filtered on read.
     */
    private fun addSyncColumns(db: SQLiteDatabase) {
        // Checked rather than assumed: [CREATE_PROGRESS] already carries them, and the version 2
        // migration rebuilds the table with it, so a jump from 1 to 5 arrives here with the columns
        // already in place and a bare `ALTER` would fail. It is the same care [onUpgrade] takes with
        // the user's tables, for the same reason: both paths have to end at the same table a fresh
        // installation gets.
        val existing = db.rawQuery("PRAGMA table_info($TABLE_PROGRESS)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("dirty" !in existing) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
        }
        if ("deleted" !in existing) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        // Everything already there predates synchronisation, so the server does not know it.
        // Without this line, updating the app would lose the whole household's "Continue watching".
        db.execSQL("UPDATE $TABLE_PROGRESS SET dirty = 1")
    }

    /**
     * Version 2: `profile` at the front of both user tables, and of both primary keys.
     *
     * SQLite cannot add a column to a primary key, so each table is built again and poured across.
     * The `INSERT` does not mention `profile`, which is the point: everything watched before there
     * were profiles takes the column's default and becomes the first profile's history.
     */
    private fun addProfileToUserTables(db: SQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS $INDEX_PROGRESS_RECENT")
        db.execSQL("ALTER TABLE $TABLE_PROGRESS RENAME TO ${TABLE_PROGRESS}_old")
        db.execSQL(CREATE_PROGRESS)
        db.execSQL(CREATE_INDEX_PROGRESS_RECENT)
        db.execSQL(
            // Sin `dirty` ni `deleted`: esta migración corre sobre una tabla de la versión 1, que
            // no las tenía, y la tabla que crea las trae con su valor por defecto. La v5 se aplica
            // después y pone `dirty` a 1 en todo, que es lo que hace falta aquí también.
            "INSERT INTO $TABLE_PROGRESS (title_id, episode_id, position_ms, duration_ms, updated_at) " +
                "SELECT title_id, episode_id, position_ms, duration_ms, updated_at " +
                "FROM ${TABLE_PROGRESS}_old"
        )
        db.execSQL("DROP TABLE ${TABLE_PROGRESS}_old")

        db.execSQL("ALTER TABLE $TABLE_WATCHLIST RENAME TO ${TABLE_WATCHLIST}_old")
        db.execSQL(CREATE_WATCHLIST)
        db.execSQL(
            "INSERT INTO $TABLE_WATCHLIST (title_id, added_at) " +
                "SELECT title_id, added_at FROM ${TABLE_WATCHLIST}_old"
        )
        db.execSQL("DROP TABLE ${TABLE_WATCHLIST}_old")
    }

    /**
     * Versión 6: las mismas tres columnas en `watchlist`, y por las mismas razones.
     *
     * Todo lo que ya había sale sucio: son filas de antes de que «Mi lista» se sincronizara, el
     * servidor no las conoce, y el aparato que actualice primero es el que las sube. Su `updated_at`
     * se toma de `added_at` en vez de del reloj de ahora, para que una lista guardada hace meses no
     * gane a una decisión que otro aparato tomó ayer.
     */
    private fun addListSyncColumns(db: SQLiteDatabase) {
        // Comprobado y no dado por hecho, igual que en [addSyncColumns]: [CREATE_WATCHLIST] ya las
        // trae, y la migración de la versión 2 reconstruye la tabla con él.
        val existing = db.rawQuery("PRAGMA table_info($TABLE_WATCHLIST)", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        if ("updated_at" !in existing) {
            db.execSQL("ALTER TABLE $TABLE_WATCHLIST ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
        }
        if ("dirty" !in existing) {
            db.execSQL("ALTER TABLE $TABLE_WATCHLIST ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
        }
        if ("deleted" !in existing) {
            db.execSQL("ALTER TABLE $TABLE_WATCHLIST ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("UPDATE $TABLE_WATCHLIST SET updated_at = added_at, dirty = 1")
    }

    companion object {
        const val DATABASE_NAME = "catalogo.db"
        // 2: `profile` on the two user tables.
        // 3: `meta`, which is where the sync timestamp moved.
        // 4: `track`, the audio and the subtitles each person last watched a title in.
        // 5: `dirty` y `deleted` en `progress`, para que el progreso viaje entre aparatos.
        // 6: lo mismo en `watchlist`, más `updated_at`, para que «Mi lista» viaje igual.
        const val DATABASE_VERSION = 6

        const val TABLE_TITLE = "title"
        const val TABLE_SOURCE = "source"
        const val TABLE_CATEGORY = "category"
        const val TABLE_TITLE_CATEGORY = "title_category"
        const val TABLE_DETAIL = "detail"
        const val TABLE_PROGRESS = "progress"
        const val TABLE_WATCHLIST = "watchlist"
        const val TABLE_META = "meta"
        const val TABLE_TRACK = "track"

        const val INDEX_PROGRESS_RECENT = "idx_progress_recent"

        /**
         * The user's own rows. `episode_id` is 0 for a film.
         *
         * `profile` leads the primary key because it leads every query: one household, one
         * catalogue, but two people who are at different episodes of the same series. It is
         * `DEFAULT 0` so that everything watched before profiles existed belongs to the first of
         * them — see [addProfileToUserTables], which relies on exactly that.
         *
         * Declared apart from [SCHEMA] because the version 2 migration builds these two tables
         * again, and a migration that repeats the `CREATE` by hand is a migration that drifts from
         * what a fresh install gets.
         */
        private val CREATE_PROGRESS = """
            CREATE TABLE $TABLE_PROGRESS (
                profile     INTEGER NOT NULL DEFAULT 0,
                title_id    INTEGER NOT NULL,
                episode_id  INTEGER NOT NULL DEFAULT 0,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL,
                dirty       INTEGER NOT NULL DEFAULT 0,
                deleted     INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (profile, title_id, episode_id)
            )
            """

        private val CREATE_INDEX_PROGRESS_RECENT =
            "CREATE INDEX $INDEX_PROGRESS_RECENT ON $TABLE_PROGRESS (profile, updated_at DESC)"

        /**
         * Anything the app needs to remember *about* the catalogue. One row so far: when the last
         * complete sync finished.
         *
         * It is here rather than in preferences so that it cannot outlive the rows it describes.
         */
        private val CREATE_META = """
            CREATE TABLE $TABLE_META (
                key   TEXT PRIMARY KEY,
                value INTEGER NOT NULL
            )
            """

        /**
         * Which audio and which subtitles a person watches a title in.
         *
         * Per title and not per episode, because that is how the choice is actually made: somebody
         * who watches a series in its original language with subtitles does not decide that again
         * at the start of episode two. Per profile, because the other half of the sofa may well
         * disagree.
         *
         * `subtitle` is a language, or the empty string for subtitles deliberately turned off, or
         * NULL for never having said. The three are different: off is an answer, and re-enabling
         * subtitles on somebody who switched them off is exactly the annoyance this table exists to
         * prevent.
         */
        private val CREATE_TRACK = """
            CREATE TABLE $TABLE_TRACK (
                profile  INTEGER NOT NULL DEFAULT 0,
                title_id INTEGER NOT NULL,
                audio    TEXT,
                subtitle TEXT,
                PRIMARY KEY (profile, title_id)
            )
            """

        /**
         * Lo que cada persona ha guardado para después.
         *
         * `added_at` es cuándo se guardó y es lo que ordena la lista en pantalla. `updated_at` es
         * otra cosa: cuándo cambió la fila, quitar incluido, y es lo que decide quién gana cuando
         * dos aparatos de la casa discrepan. Confundirlos haría que quitar algo no pudiera ganarle
         * a haberlo guardado.
         *
         * `profile` encabeza la clave por lo mismo que en [CREATE_PROGRESS]: una casa, un catálogo,
         * y una lista distinta por persona.
         */
        private val CREATE_WATCHLIST = """
            CREATE TABLE $TABLE_WATCHLIST (
                profile    INTEGER NOT NULL DEFAULT 0,
                title_id   INTEGER NOT NULL,
                added_at   INTEGER NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT 0,
                dirty      INTEGER NOT NULL DEFAULT 0,
                deleted    INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (profile, title_id)
            )
            """

        private val SCHEMA = listOf(
            // `merge_key` is what folds sixty listings of Blade Runner 2049 into one row. `stamp` is
            // the sync generation that last saw this row: anything left with an older stamp when a
            // sync finishes has been withdrawn by the supplier and is deleted.
            """
            CREATE TABLE $TABLE_TITLE (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                kind         INTEGER NOT NULL,
                merge_key    TEXT    NOT NULL UNIQUE,
                name         TEXT    NOT NULL,
                search_name  TEXT    NOT NULL,
                year         INTEGER,
                poster_url   TEXT,
                rating       REAL,
                added        INTEGER NOT NULL DEFAULT 0,
                stamp        INTEGER NOT NULL DEFAULT 0
            )
            """,
            "CREATE INDEX idx_title_recent ON $TABLE_TITLE (kind, added DESC)",
            "CREATE INDEX idx_title_search ON $TABLE_TITLE (search_name)",

            // One row per encode: the 4K, the HD60 and the plain HD are three `remote_id`s.
            """
            CREATE TABLE $TABLE_SOURCE (
                title_id  INTEGER NOT NULL,
                remote_id INTEGER NOT NULL,
                quality   INTEGER NOT NULL,
                container TEXT    NOT NULL,
                stamp     INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (title_id, remote_id)
            )
            """,

            """
            CREATE TABLE $TABLE_CATEGORY (
                id        INTEGER PRIMARY KEY AUTOINCREMENT,
                kind      INTEGER NOT NULL,
                remote_id TEXT    NOT NULL,
                name      TEXT    NOT NULL,
                position  INTEGER NOT NULL,
                stamp     INTEGER NOT NULL DEFAULT 0,
                UNIQUE (kind, remote_id)
            )
            """,

            """
            CREATE TABLE $TABLE_TITLE_CATEGORY (
                category_id INTEGER NOT NULL,
                title_id    INTEGER NOT NULL,
                position    INTEGER NOT NULL,
                stamp       INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (category_id, title_id)
            )
            """,
            "CREATE INDEX idx_title_category_title ON $TABLE_TITLE_CATEGORY (title_id)",

            // Filled lazily, one title at a time, and then kept. Series get theirs free with the
            // listing; films cost one request each, which is why they are cached rather than refetched.
            """
            CREATE TABLE $TABLE_DETAIL (
                title_id     INTEGER PRIMARY KEY,
                plot         TEXT,
                genre        TEXT,
                cast_list    TEXT,
                director     TEXT,
                release_date TEXT,
                duration     INTEGER,
                tmdb_id      TEXT,
                backdrop_url TEXT,
                trailer      TEXT,
                video_codec  TEXT,
                video_height INTEGER,
                audio_codec  TEXT,
                bitrate      INTEGER,
                fetched_at   INTEGER NOT NULL DEFAULT 0
            )
            """,

            CREATE_PROGRESS,
            CREATE_INDEX_PROGRESS_RECENT,
            CREATE_WATCHLIST,
            CREATE_META,
            CREATE_TRACK
        )
    }
}
