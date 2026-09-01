package com.videoclub.app.data

import android.util.Log
import androidx.compose.runtime.Immutable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the catalogue download is doing, for the one line of UI that reports it. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val progress: SyncProgress) : SyncState
    data object Ready : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * One horizontal row of a browsing tab.
 *
 * [categoryIds] is what the heading opens, and there are several of them because a row is a merge
 * of the supplier's categories — see [RowPlan]. Empty for the rows the app builds itself, whose
 * heading is therefore not a link to anywhere.
 */
@Immutable
data class HomeRow(val heading: String, val categoryIds: List<Long>, val titles: List<Title>)

/**
 * One row of `Porque viste …`: something the profile watched, and what it suggests.
 *
 * The seed travels with the suggestions rather than being folded into a heading string, because a
 * suggestion that says where it came from is a suggestion that can be forgiven when it misses — and
 * because the sentence that says it belongs in `strings.xml` with the rest of the copy.
 */
@Immutable
data class Suggestion(val seed: Title, val titles: List<Title>)

/**
 * The catalogue, as the rest of the app sees it.
 *
 * Reads are suspending calls straight through to SQLite rather than long-lived flows, because the
 * data changes at exactly two moments — a sync writes, or the user marks something watched — and
 * [revision] announces both. Observing thirty rows of posters continuously would cost more than
 * re-reading them on the rare occasion they move.
 */
class CatalogRepository(
    private val store: CatalogStore,
    private val client: VodClient,
    private val sync: CatalogSync,
    private val scope: CoroutineScope
) {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    /** Bumped whenever anything on screen could have changed. Screens re-read when it moves. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * Vuelve a dibujar lo que hay en pantalla porque la base ha cambiado por debajo.
     *
     * Lo llama la sincronización del progreso cuando trae algo de otro aparato de la casa: sin esto
     * el «Seguir viendo» nuevo estaría en SQLite pero la fila de la pantalla seguiría siendo la de
     * antes hasta que algo la despertara por su cuenta.
     */
    fun reload() {
        _revision.update { it + 1 }
    }

    val hasCatalogue: Boolean get() = store.hasCatalogue

    /**
     * Whose history the reads and the writes below belong to.
     *
     * It starts as whoever watched last, but nothing is shown until the app has asked: the point of
     * the question at startup is that a wrong guess here writes one person's evening into the other
     * person's `Seguir viendo`. Setting it bumps [revision], which is what makes every screen
     * re-read itself as the new person.
     */
    var profile: Profile = Profile.DEFAULT.first()
        set(value) {
            if (field == value) return
            field = value
            store.lastProfileId = value.id
            _revision.update { it + 1 }
        }

    private val _profiles = MutableStateFlow(Profile.withInitials(Profile.DEFAULT))

    /** Everybody this device knows about, in the order the chooser draws them. */
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    /**
     * Takes the household as the hosted document describes it.
     *
     * The deletions are the part worth reading. Somebody removed here takes their `Seguir viendo`
     * and their `Mi lista` with them, on purpose: those rows are the person, and leaving them
     * behind would be a stranger's half-watched films waiting for whoever is handed that id next.
     *
     * The list is not edited on the device any more, so this is an adoption rather than a save. A
     * person removed from the panel loses their history here, on every box in the house, the next
     * time each one reads the document. It is not undoable, which is why the panel never hands a
     * retired id back out.
     *
     * An empty list is ignored rather than obeyed. A household with nobody in it is not a state
     * worth reaching from a typo in a text file.
     */
    fun adoptProfiles(described: List<Profile>) {
        val kept = Profile.withInitials(described.map { it.copy(name = it.name.trim()) })
            .takeIf { it.isNotEmpty() }
            ?: return
        if (kept == _profiles.value) return

        val removed = _profiles.value.map { it.id } - kept.map { it.id }.toSet()
        _profiles.value = kept

        // Whoever was watching stays watching if they still exist, and otherwise the first person
        // in the house takes over. Never left pointing at an id that has gone.
        profile = kept.firstOrNull { it.id == profile.id } ?: kept.first()

        if (removed.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                removed.forEach(store::forgetProfile)
            }
        }
    }

    init {
        // The id on disk is the last person who watched here; the list it points into can have been
        // edited since, so it is resolved rather than trusted.
        val stored = _profiles.value
        profile = stored.firstOrNull { it.id == store.lastProfileId } ?: stored.first()
    }

    private var syncJob: Job? = null

    /**
     * The shelf plan of one tab: every category it has, already sorted into the three groups the
     * app reads it by.
     *
     * Cached because reading it is not cheap and because it almost never changes. The query behind
     * it counts the rows of `title_category`, and there are 232,000 of them — a tenth of a second on
     * a television box, paid on every single reload of a browsing tab, to be told the same eighteen
     * genres as last time. Only a sync can add a category or empty one, so only a sync clears this.
     */
    private class Shelving(val all: List<Category>) {
        val byId: Map<Long, Category> = all.associateBy(Category::id)
        val children: List<Category> = all.filter { Genres.isForChildren(it.name) }
        val grownUps: List<Long> = all.filter { Genres.isForGrownUps(it.name) }.map(Category::id)
        val sport: List<Long> = all.filter { Genres.isSport(it.name) }.map(Category::id)
        val television: List<Long> = all.filter { Genres.isTelevision(it.name) }.map(Category::id)
    }

    private val shelvingCache = ConcurrentHashMap<Kind, Shelving>()

    /** Which copies of a film agree on its running time. See [agreeingSources]. */
    private val agreementCache = ConcurrentHashMap<Long, List<Source>>()

    /**
     * Downloads the catalogue if there has never been one, or if the last one is a day old.
     *
     * A day is right for a catalogue that gains a few dozen titles a week and loses almost nothing:
     * shorter would be nine hundred requests to learn nothing, longer and the `ÚLTIMOS ESTRENOS` row
     * would be lying.
     */
    fun refreshIfStale(nowMillis: Long) {
        // An empty catalogue overrides the clock. The timestamp now lives beside the rows it
        // describes, so the two should never disagree, but a future migration that drops a
        // catalogue table and keeps `meta` would bring the disagreement back — and its symptom is
        // an empty app that refuses to fill itself for the rest of the day.
        if (!store.hasCatalogue) {
            refresh(nowMillis)
            return
        }
        val age = nowMillis - store.syncedAtMillis
        if (store.syncedAtMillis != 0L && age < MAX_AGE_MILLIS) return
        refresh(nowMillis)
    }

    fun refresh(nowMillis: Long) {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            _syncState.value = SyncState.Running(SyncProgress(0, 0, ""))
            runCatching {
                sync.run(nowMillis) { progress ->
                    _syncState.value = SyncState.Running(progress)
                    // A batch can be the first titles of a category, which is what makes it a row.
                    shelvingCache.clear()
                    // And it can add a copy of a film that already had two, which changes who the
                    // majority is.
                    agreementCache.clear()
                    // Every batch adds rows. Announcing it here is what makes a fresh install fill
                    // in front of the user instead of staring at nothing for three minutes.
                    _revision.update { it + 1 }
                }
            }.onSuccess { complete ->
                // A partial catalogue is still a usable one — the rows that did arrive are correct,
                // and nothing was deleted. Say so quietly rather than pretending it all worked.
                _syncState.value = if (complete) SyncState.Ready
                else SyncState.Failed("El catálogo se descargó a medias.")
                _revision.update { it + 1 }
            }.onFailure { error ->
                Log.w(TAG, "Catalogue sync failed", error)
                _syncState.value = SyncState.Failed(error.message ?: "Sin conexión con el proveedor.")
                // Whatever arrived before it broke is in the database and is correct. Without this
                // bump nothing asks for it, and a failed first sync leaves an app that says it is
                // empty while holding half a catalogue.
                _revision.update { it + 1 }
            }
        }
    }

    fun acknowledgeSync() {
        if (_syncState.value !is SyncState.Running) _syncState.value = SyncState.Idle
    }

    // ----------------------------------------------------------------------------------- reading

    /**
     * The rows of a browsing tab: what is new, then one row per genre — see [RowPlan].
     *
     * `Novedades` is the app's own row rather than the supplier's `ÚLTIMOS ESTRENOS`, and they are
     * not the same list: one is when a file appeared in the account, the other is what the supplier
     * decided to feature. This one is the honest one, and it is the only row here that is not a
     * genre — hence the empty [HomeRow.categoryIds], which is what marks a heading that opens
     * nothing.
     */
    suspend fun home(kind: Kind): List<HomeRow> = withContext(Dispatchers.IO) {
        val shelving = shelving(kind)
        val newest = HomeRow(
            heading = "Novedades",
            categoryIds = emptyList(),
            titles = store.recentlyAdded(kind, ROW_TITLES, shelves(listOf(kind), forNewArrivals = true))
        )
        val shelves = shelves(listOf(kind), forNewArrivals = false)
        val rows = RowPlan.rows(if (profile.childrenOnly) shelving.children else shelving.all)
            .map { spec ->
                HomeRow(
                    heading = spec.heading,
                    categoryIds = spec.categoryIds,
                    titles = store.titlesInCategories(spec.categoryIds, ROW_TITLES, shelves = shelves)
                )
            }
        (listOf(newest) + rows).filter { it.titles.isNotEmpty() }
    }

    suspend fun allCategories(kind: Kind): List<Category> =
        withContext(Dispatchers.IO) { shelving(kind).all }

    /** Blocking, and called from [Dispatchers.IO] only. See [Shelving]. */
    private fun shelving(kind: Kind): Shelving =
        shelvingCache.getOrPut(kind) { Shelving(store.categories(kind)) }

    /**
     * What the profile in the chair is allowed to be shown.
     *
     * [forNewArrivals] is asked for by the two rows of new things and by nothing else. Neither sport
     * nor television is unsuitable; they are simply not what those rows are for. Fifty-one
     * categories of match recordings and one shelf of chat shows, all uploaded by the hundred every
     * week and every one of them stamped with today's date, had turned `Novedades` into a fixture
     * list with Cuarto Milenio in it. The search box is deliberately left alone, so both are still
     * one search away.
     *
     * The two are kept out differently, and the difference is the point. Sport goes in `neverAlone`,
     * so a boxing film that also sits on the drama shelf stays; television goes in `never`, because
     * El Chiringuito is filed under `REALITY` and `SERIES ESPAÑOLAS` too and would otherwise walk
     * straight back in through them.
     */
    private fun shelves(kinds: List<Kind>, forNewArrivals: Boolean): Shelves {
        val shelving = kinds.map(::shelving)
        val vetoed = buildList {
            if (profile.childrenOnly) addAll(shelving.flatMap { it.grownUps })
            if (forNewArrivals) addAll(shelving.flatMap { it.television })
        }
        return Shelves(
            only = if (profile.childrenOnly) {
                shelving.flatMap { it.children.map(Category::id) }
            } else {
                null
            },
            never = vetoed,
            // The veto is written in the supplier's category names, and they cannot say that
            // Totoro is not Chainsaw Man. See [Genres.CHILD_SAFE_TITLES].
            spared = if (profile.childrenOnly) Genres.CHILD_SAFE_TITLES else emptyList(),
            neverAlone = if (forNewArrivals) shelving.flatMap { it.sport } else emptyList()
        )
    }

    suspend fun titlesInCategories(categoryIds: List<Long>, limit: Int, offset: Int = 0): List<Title> =
        withContext(Dispatchers.IO) {
            store.titlesInCategories(
                categoryIds, limit, offset, shelves(Kind.entries, forNewArrivals = false)
            )
        }

    suspend fun search(query: String): List<Title> = withContext(Dispatchers.IO) {
        store.search(query, SEARCH_RESULTS, shelves(Kind.entries, forNewArrivals = false))
    }

    suspend fun title(id: Long): Title? = withContext(Dispatchers.IO) { store.title(id) }

    suspend fun continueWatching(): List<InProgress> =
        withContext(Dispatchers.IO) { store.continueWatching(profile, ROW_TITLES) }

    /** Films and series together, newest first — the fallback row for a home screen with no history. */
    suspend fun recentlyAdded(): List<Title> = withContext(Dispatchers.IO) {
        store.recentlyAdded(kind = null, limit = ROW_TITLES, shelves = shelves(Kind.entries, forNewArrivals = true))
    }

    /**
     * What to carry on with, resolved down to a specific episode.
     *
     * Each title costs at most one round of [episodes], and only the ones whose last episode is
     * finished pay it — the rest are answered straight from the progress table. They are resolved
     * concurrently, so the screen waits for the slowest series rather than for their sum.
     */
    suspend fun continueRow(): List<ContinueEntry> = withContext(Dispatchers.IO) {
        coroutineScope {
            store.recentProgress(profile, CONTINUE_ENTRIES)
                .map { started -> async { continueEntry(started) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun continueEntry(started: InProgress): ContinueEntry? {
        val (title, progress) = started
        if (!progress.isFinished) {
            return ContinueEntry(
                title = title,
                episodeKey = progress.episodeId,
                // Films store episode 0, and `0 / 1000` would claim season zero.
                season = progress.episodeId.takeIf { it > 0 }?.div(EPISODE_KEY_BASE),
                episodeNumber = progress.episodeId.takeIf { it > 0 }?.rem(EPISODE_KEY_BASE),
                episodeTitle = null,
                startMillis = progress.positionMillis,
                fraction = progress.fraction,
                minutesLeft = ((progress.durationMillis - progress.positionMillis) / 60_000L)
                    .toInt()
                    .takeIf { it > 0 }
            )
        }

        // A film watched to the end is done, and has nothing to offer this screen.
        if (title.kind != Kind.Series) return null

        // The next episode there is, which is not necessarily the next number: seasons end, and a
        // supplier's copy can be missing the odd episode in the middle.
        val next = episodes(title)
            .filter { it.key > progress.episodeId }
            .minByOrNull { it.key }
            ?: return null

        return ContinueEntry(
            title = title,
            episodeKey = next.key,
            season = next.season,
            episodeNumber = next.number,
            episodeTitle = next.title,
            startMillis = 0L,
            fraction = 0f,
            minutesLeft = next.durationSeconds?.div(60)?.takeIf { it > 0 }
        )
    }

    /**
     * `Porque viste …`, at most [SUGGESTION_ROWS] of them.
     *
     * ### Where the suggestions come from
     *
     * Out of the catalogue that is already on the device, and out of nothing else. No account with
     * the supplier is asked, no model is trained, nothing leaves the house: the supplier's own
     * categories turn out to be a rich enough vocabulary on their own, because they are not only
     * genres. Gladiator sits in `RUSSELL CROWE HD`, `JOAQUIN PHOENIX 4K` and `RIDLEY SCOTT HD` as
     * well as in `ACCION HD`, so "another Ridley Scott with Russell Crowe in it" is a query this
     * database can already answer.
     *
     * ### Which categories count
     *
     * The specific ones. Every film is also on `NETFLIX HD`, `PELICULAS HD 2000` and `TOP PELICULAS
     * 4K`, and so is every other film — see [Genres.isSubject] — while a category with four
     * thousand titles in it says little more. So the shelves are sorted by how few titles they hold
     * and the smallest [SUBJECTS] are what the suggestion is built from: an actor, a director, a
     * theme. The count of shared shelves is the ranking, and the rating breaks the ties.
     *
     * ### Which films seed a row
     *
     * The last two the profile got properly into. Something abandoned after four minutes is not an
     * opinion, so [SEED_FRACTION] is the floor, and everything already started is excluded from the
     * answers — a suggestion is for what to watch next, not for what is in `Seguir viendo` already.
     */
    suspend fun suggestions(): List<Suggestion> = withContext(Dispatchers.IO) {
        val watched = store.watchedTitleIds(profile)
        val shelves = shelves(Kind.entries, forNewArrivals = true)

        store.recentProgress(profile, SEEDS_CONSIDERED)
            .filter { it.progress.fraction >= SEED_FRACTION }
            .distinctBy { it.title.id }
            .mapNotNull { started ->
                val subjects = subjectsOf(started.title)
                if (subjects.isEmpty()) return@mapNotNull null
                val titles = store.similarTo(subjects, watched, ROW_TITLES, shelves)
                if (titles.size < SUGGESTIONS_NEEDED) null else Suggestion(started.title, titles)
            }
            .take(SUGGESTION_ROWS)
    }

    /** The smallest handful of shelves this title is on that actually say what it is. */
    private fun subjectsOf(title: Title): List<Long> {
        val known = shelving(title.kind).byId
        return store.categoryIdsOf(title.id)
            .mapNotNull { known[it] }
            .filter { Genres.isSubject(it.name) && it.titleCount in 1..SUBJECT_CEILING }
            .sortedBy { it.titleCount }
            .take(SUBJECTS)
            .map(Category::id)
    }

    suspend fun watchlist(): List<Title> = withContext(Dispatchers.IO) { store.watchlist(profile) }

    suspend fun isInWatchlist(titleId: Long): Boolean =
        withContext(Dispatchers.IO) { store.isInWatchlist(profile, titleId) }

    suspend fun progress(titleId: Long, episodeId: Int = 0): Progress? =
        withContext(Dispatchers.IO) { store.progress(profile, titleId, episodeId) }

    suspend fun lastProgress(titleId: Long): Progress? =
        withContext(Dispatchers.IO) { store.lastProgress(profile, titleId) }

    /**
     * The cached detail if there is one, otherwise one call to the supplier.
     *
     * Films cost a request each and are cached forever after — plot and cast do not change. Series
     * keep whatever the listing gave them until the detail screen asks, at which point the same call
     * that brings the episode list refreshes the rest.
     */
    suspend fun detail(title: Title): TitleDetail? = withContext(Dispatchers.IO) {
        if (store.hasDetail(title.id)) return@withContext store.detail(title.id)

        val source = title.bestSource ?: return@withContext store.detail(title.id)
        val fetched = when (title.kind) {
            Kind.Movie -> client.movieDetail(source.remoteId)
            Kind.Series -> client.seriesDetail(source.remoteId, source.quality)?.detail
        } ?: return@withContext store.detail(title.id)

        store.putDetail(title.id, fetched, System.currentTimeMillis())
        fetched
    }

    /**
     * The copies of a film that are actually that film, best encode first.
     *
     * Films only, and only when there is more than one copy. A series' copies are *expected* to
     * disagree — the supplier's 4K listing of a series is a different, usually smaller selection of
     * episodes rather than a better one, which is exactly what [episodes] merges — and a series has
     * no single running time to compare anyway.
     *
     * Costs one request per copy, which is two or three, and only on a film whose picker was going
     * to be shown. Held in memory for the session rather than written down: the answer is cheap to
     * recompute, a schema for it would have to be migrated, and a detail screen is opened once or
     * twice an evening.
     *
     * See [SourceAgreement] for what "actually that film" means and why a tie decides nothing.
     */
    suspend fun agreeingSources(title: Title): List<Source> = withContext(Dispatchers.IO) {
        if (title.kind != Kind.Movie || title.sources.size < 2) return@withContext title.sources
        agreementCache[title.id]?.let { return@withContext it }

        val fetched = coroutineScope {
            title.sources
                .map { source -> async { source to client.movieDetail(source.remoteId) } }
                .awaitAll()
        }
        val agreed = SourceAgreement.agreeing(fetched.map { (source, detail) ->
            source to detail?.durationSeconds
        })

        // The plot and the codec report of the best copy that survived — never the impostor's, and
        // never overwriting an answer already on disk. Saves [detail] the request it was about to
        // make for the same copy a moment later.
        if (!store.hasDetail(title.id)) {
            fetched.firstOrNull { (source, detail) -> source in agreed && detail != null }
                ?.second
                ?.let { store.putDetail(title.id, it, System.currentTimeMillis()) }
        }

        agreementCache[title.id] = agreed
        agreed
    }

    /**
     * A series' episodes, fetched every time the screen opens.
     *
     * **Every copy is asked, not just the best one.** The supplier's 4K listing of a series is a
     * different series from its ordinary listing rather than a better one — The Bear's 4K entry has
     * seasons 2, 3 and 5 and half of season 2, while the plain entry has all five complete — so
     * reading only the best encode silently loses seasons. In parallel, because two or three
     * requests one after another is the difference between an episode list appearing and an episode
     * list loading.
     *
     * Not cached, deliberately: each call returns in well under a second, and the episode list is
     * the part of a series that actually changes — a cached one is wrong the week a new episode
     * lands, which is the only week anybody looks.
     */
    suspend fun episodes(title: Title): List<Episode> = withContext(Dispatchers.IO) {
        if (title.sources.isEmpty()) return@withContext emptyList()

        val fetched = coroutineScope {
            title.sources
                .map { source -> async { client.seriesDetail(source.remoteId, source.quality) } }
                .awaitAll()
        }

        // Best encode first, so its plot and stills win and its detail is the one kept.
        fetched.firstNotNullOfOrNull { it?.detail }
            ?.let { store.putDetail(title.id, it, System.currentTimeMillis()) }

        mergeEpisodes(fetched.mapNotNull { it?.episodes }).map { episode ->
            episode.copy(
                title = TitleNaming.episodeTitle(
                    rawTitle = episode.title,
                    seriesName = title.name,
                    // The same wording [CatalogJson] uses when an episode has no title at all.
                    fallback = "Episodio ${episode.number}"
                )
            )
        }
    }

    // ----------------------------------------------------------------------------------- writing

    fun saveProgress(titleId: Long, episodeId: Int, positionMillis: Long, durationMillis: Long) {
        // Read now rather than inside the coroutine: this belongs to whoever was watching when the
        // player reported the position, not to whoever the app is showing by the time it lands.
        val writing = profile
        scope.launch(Dispatchers.IO) {
            store.saveProgress(
                writing, titleId, episodeId, positionMillis, durationMillis, System.currentTimeMillis()
            )
            _revision.update { it + 1 }
        }
    }

    /** Takes a title off `Seguir viendo`. It comes back on its own if it is played again. */
    fun forgetProgress(titleId: Long) {
        val writing = profile
        scope.launch(Dispatchers.IO) {
            store.forgetProgress(writing, titleId, System.currentTimeMillis())
            _revision.update { it + 1 }
        }
    }

    /** What to ask the player for when this title opens. Null when nobody ever chose anything. */
    suspend fun tracks(titleId: Long): TrackChoice? =
        withContext(Dispatchers.IO) { store.tracks(profile, titleId) }

    /**
     * Remembers what the player is playing, so the next episode opens the same way.
     *
     * No revision bump: nothing on any screen shows this, and waking every row on the home screen
     * every ten seconds while a film plays is what the position save already had to learn not to do.
     */
    fun saveTracks(titleId: Long, choice: TrackChoice) {
        if (choice.isEmpty) return
        val writing = profile
        scope.launch(Dispatchers.IO) { store.saveTracks(writing, titleId, choice) }
    }

    /**
     * Devuelve el trabajo, no `Unit`: quien llama tiene que poder esperar a que la fila esté escrita
     * antes de pedir una sincronización. Pedirla a ciegas justo después es pedirla antes de que haya
     * nada que mandar, y entonces la decisión no sale de este aparato hasta la vuelta siguiente.
     */
    fun setInWatchlist(titleId: Long, inList: Boolean): Job {
        val writing = profile
        return scope.launch(Dispatchers.IO) {
            store.setInWatchlist(writing, titleId, inList, System.currentTimeMillis())
            _revision.update { it + 1 }
        }
    }

    fun movieUrl(source: Source): String = client.movieUrl(source.remoteId, source.container)

    fun episodeUrl(source: EpisodeSource): String =
        client.episodeUrl(source.remoteId, source.container)

    private companion object {
        const val TAG = "CatalogRepository"
        const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        const val ROW_TITLES = 24
        const val SEARCH_RESULTS = 60

        /**
         * How far back the home screen looks.
         *
         * Smaller than a poster row on purpose: each of these can cost a request, and nobody is
         * carrying on with the twentieth thing they started.
         */
        const val CONTINUE_ENTRIES = 12

        /** [Episode.key] is `season * 1000 + number`. */
        const val EPISODE_KEY_BASE = 1000

        /** Two rows of suggestions. More and the home screen stops being a page and becomes a wall. */
        const val SUGGESTION_ROWS = 2

        /** Looked at, not used: the recent ones that were barely started do not get a row. */
        const val SEEDS_CONSIDERED = 8

        /** A tenth of a film is somebody deciding they did not want it. */
        const val SEED_FRACTION = 0.12f

        /** Below this the row would be three posters wide and look like a mistake. */
        const val SUGGESTIONS_NEEDED = 6

        /** How many shelves of one title the suggestion is built from, smallest first. */
        const val SUBJECTS = 8

        /** A shelf with more titles than this on it is a mood, not a subject. */
        const val SUBJECT_CEILING = 900
    }
}
