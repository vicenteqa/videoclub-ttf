package com.videoclub.app.ui

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.videoclub.app.Container
import com.videoclub.app.Startup
import com.videoclub.app.data.ContinueEntry
import com.videoclub.app.data.Episode
import com.videoclub.app.data.HomeRow
import com.videoclub.app.data.InProgress
import com.videoclub.app.data.Kind
import com.videoclub.app.data.Profile
import com.videoclub.app.data.Progress
import com.videoclub.app.data.Quality
import com.videoclub.app.data.Source
import com.videoclub.app.data.Suggestion
import com.videoclub.app.data.SyncState
import com.videoclub.app.data.Title
import com.videoclub.app.data.TitleDetail
import com.videoclub.app.data.TrackChoice
import com.videoclub.app.data.WatchReporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The six things the bar across the top can be showing. [Home] is where the app opens.
 *
 * [Live] is the odd one out and is not a shelf at all: choosing it leaves the browsing world for a
 * full-screen picture. It is in this enum anyway because the strip is where it is chosen from, and
 * a destination that is reached from the strip but is not one of its entries would need a second
 * mechanism to do exactly what this one already does.
 */
enum class Tab(val kind: Kind?) {
    Home(null),
    Movies(Kind.Movie),
    Series(Kind.Series),
    MyList(null),
    Live(null),
    Search(null)
}

/** Where the app is. A list rather than a graph: this app is three screens deep at the very most. */
sealed interface Screen {
    data class Browse(val tab: Tab) : Screen
    data class Grid(val categoryIds: List<Long>, val heading: String) : Screen
    /**
     * [episodeKey] is which episode the page should open *on*, and only `Seguir viendo` sets it.
     *
     * A film's page is the film. A series' page is fifty episodes, and arriving at the top of it
     * from a card that said `T2 E4` means finding T2 E4 again by hand.
     */
    data class Detail(val titleId: Long, val episodeKey: Int? = null) : Screen
    data class Play(val request: PlayRequest) : Screen

    /**
     * Live television: one picture, and a channel list over it.
     *
     * It carries no arguments because it has none to carry. Which channel is on is a fact about the
     * house rather than about this visit to the screen — it survives the app being closed — so it
     * lives in the channel store and not here.
     */
    data object Live : Screen

    /** The three settings screens: a menu of two, and the two it opens. */
}

/** One playable file, with the name of the encode it is, for when the player has to say so. */
@Immutable
data class PlayCopy(val url: String, val label: String)

/**
 * Everything the player needs, resolved before the screen opens so it can start immediately.
 *
 * [copies] is every encode of this exact film or episode, the chosen one first. The player needs the
 * whole list rather than one URL because a copy can be undecodable on the device in hand — part of
 * this catalogue's 4K is Dolby Vision, and a phone with no Dolby Vision decoder cannot be talked into
 * one — and the useful answer to that is the next copy down, not an error message.
 */
@Immutable
data class PlayRequest(
    val titleId: Long,
    val episodeId: Int,
    val copies: List<PlayCopy>,
    val heading: String,
    val subheading: String?,
    val startPositionMillis: Long,
    /** What this viewer was watching it in last time, for the player to ask the file for. */
    val tracks: TrackChoice? = null
)

@Immutable
data class BrowseState(
    val continueWatching: List<InProgress> = emptyList(),
    /** Resolved down to a specific episode, and only ever filled for [Tab.Home]. */
    val continueEntries: List<ContinueEntry> = emptyList(),
    val rows: List<HomeRow> = emptyList(),
    /** `Porque viste …`. Only ever filled for [Tab.Home], and only when there is a history. */
    val suggestions: List<Suggestion> = emptyList(),
    val watchlist: List<Title> = emptyList(),
    val recentlyAdded: List<Title> = emptyList(),
    val loading: Boolean = true
)

/** What the poster menu is about: one title, and whether `Mi lista` already has it. */
@Immutable
data class TitleMenuState(val title: Title, val inList: Boolean)

@Immutable
data class DetailState(
    val title: Title,
    val detail: TitleDetail? = null,
    val episodes: List<Episode> = emptyList(),
    val selected: Source? = null,
    val inWatchlist: Boolean = false,
    val progress: Progress? = null,
    val loading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MainViewModel(private val container: Container) : ViewModel() {

    private val catalog = container.catalog

    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Browse(Tab.Home)))
    val screen: StateFlow<Screen> = _stack
        .map { it.last() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Screen.Browse(Tab.Home))

    val tab: StateFlow<Tab> = _stack
        .map { stack -> stack.filterIsInstance<Screen.Browse>().lastOrNull()?.tab ?: Tab.Home }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Tab.Home)

    val syncState: StateFlow<SyncState> = catalog.syncState
    val deviceProfile = container.deviceProfile

    /** Everybody this device knows about. The chooser, the chip and the editor all read it. */
    val profiles: StateFlow<List<Profile>> = catalog.profiles

    /**
     * The account, as a value that can change while the app runs.
     *
     * It used to be a `val` read once at startup, which was right when the only way to change it was
     * to build another APK. Now an empty one is not a dead end but the first screen the app opens on.
     */
    /** What the app may draw yet: the container is still asking the server whose television this is. */
    val startup: StateFlow<Startup> = container.startup

    /**
     * Who is watching, or null while the app is asking.
     *
     * Null on the way in, every time: the answer decides whose progress the evening is written to,
     * and guessing it wrong is only discovered later, from the other person's home screen. The
     * repository already holds whoever watched last, which is what the question opens on and what
     * the rows quietly pre-load behind it.
     */
    private val _viewer = MutableStateFlow<Profile?>(null)
    val viewer: StateFlow<Profile?> = _viewer.asStateFlow()

    /** Where the cursor starts in the question: whoever watched last on this device. */
    val suggestedViewer: Profile get() = catalog.profile

    init {
        // One person in the house is not a question. The chooser exists to stop an evening being
        // written into somebody else's history, and with nobody else there is nowhere for it to go.
        //
        // Sigue a la lista en lugar de leerla una vez, y eso es lo que arregla un fallo que se veía
        // al arrancar: cuando se construye esto, el documento de la casa todavía no ha llegado del
        // servidor, así que la lista es la de relleno — una sola persona llamada «Casa» — y leerla
        // aquí significaba dar por contestada la pregunta con alguien que no existe. La consecuencia
        // en pantalla era una «C» en el círculo del perfil hasta que alguien lo tocaba.
        //
        // Por eso espera a [Startup.Ready]: es el punto en el que el contenedor ya ha adoptado el
        // documento, y hasta entonces no hay nada que decidir.
        viewModelScope.launch {
            container.startup
                .combine(catalog.profiles) { startup, people -> startup to people }
                .collect { (startup, people) ->
                    if (startup != Startup.Ready) return@collect
                    val chosen = _viewer.value
                    _viewer.value = when {
                        // A quien ya está viendo no se le vuelve a preguntar; si la casa ha dejado
                        // de tenerle, sí, porque su historial se ha ido con él.
                        chosen != null -> people.firstOrNull { it.id == chosen.id }
                        people.size == 1 -> people.first()
                        else -> null
                    }
                }
        }
    }

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private val _grid = MutableStateFlow<List<Title>>(emptyList())
    val grid: StateFlow<List<Title>> = _grid.asStateFlow()

    /** The title a long press is asking about, and whether it is already in the list. */
    private val _menu = MutableStateFlow<TitleMenuState?>(null)
    val menu: StateFlow<TitleMenuState?> = _menu.asStateFlow()

    /** The `Seguir viendo` card a long press is asking to forget, if any. */
    private val _forget = MutableStateFlow<ContinueEntry?>(null)
    val forget: StateFlow<ContinueEntry?> = _forget.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Results follow the typing with a short pause.
     *
     * The search itself is a millisecond — a `LIKE` over 33,000 folded names — so the debounce is
     * not there to spare the database. It is there so the list stops flickering through three
     * unrelated result sets while somebody types `blade`.
     */
    val results: StateFlow<List<Title>> = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .flatMapLatest { text -> flow { emit(catalog.search(text)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var detailJob: Job? = null
    private var browseJob: Job? = null

    init {
        // A sync writes rows in batches and bumps the revision after each one, which is what makes
        // a fresh install fill in front of the user rather than after three minutes of nothing.
        viewModelScope.launch {
            catalog.revision.collect { reloadBrowse() }
        }
    }

    // ---------------------------------------------------------------------------------- profiles

    /**
     * Answers the question at the door.
     *
     * Back to the top of the home screen, not to wherever the last person was: the whole app under
     * here is now somebody else's — their history, their list, their half-finished series — and a
     * detail page opened by one of them showing the other one's resume button would be wrong in the
     * one place it matters.
     */
    fun chooseViewer(next: Profile, toHome: Boolean = true) {
        // Setting this bumps the catalogue revision, which is what reloads the rows below.
        catalog.profile = next
        _viewer.value = next
        if (toHome) _stack.value = listOf(Screen.Browse(Tab.Home))
    }

    /**
     * The chip in the strip: straight to the next person, no question asked.
     *
     * The question belongs at the door and nowhere else. Once the app is open the chip is already
     * showing whose evening this is, in a letter, at the top of every screen — so re-asking a
     * question whose answer is on display is a screen nobody reads and two presses instead of one.
     * Three profiles in a ring, and a wrong one is undone by pressing it again.
     *
     * It goes back to `Inicio`, like the question at the door does. This used to keep whatever tab
     * you were on, on the grounds that somebody comparing two people's `Mi lista` should not be
     * thrown back for their trouble — but a shelf of somebody else's films under a tab you chose as
     * yourself reads as the app having lost the thread. `Inicio` is the one page that is entirely
     * about the person, so it is the honest place to arrive as a different one.
     */
    fun switchViewer() {
        val people = catalog.profiles.value
        if (people.size < 2) return
        val next = people[(people.indexOfFirst { it.id == catalog.profile.id } + 1) % people.size]
        chooseViewer(next)
    }

    // ------------------------------------------------------------------------------------ settings

    /** The long press on the profile chip lands here; the menu's two rows land in [open]. */

    fun open(screen: Screen) = push(screen)

    // ------------------------------------------------------------------------------- navigation

    fun selectTab(next: Tab) {
        // The television is not a sixth shelf, it is a different thing behind the same strip:
        // choosing it leaves the browsing world for a full-screen picture. The stack is set to
        // `Inicio` and then the picture, so that Back out of the channel list lands on the home
        // screen — which is what was asked for, and the only landing that is right whichever tab
        // the viewer happened to be on when they pressed TV.
        if (next == Tab.Live) {
            _stack.value = listOf(Screen.Browse(Tab.Home), Screen.Live)
            return
        }
        _stack.value = listOf(Screen.Browse(next))
        reloadBrowse()
    }

    fun openTitle(titleId: Long, episodeKey: Int? = null) {
        push(Screen.Detail(titleId, episodeKey))
        loadDetail(titleId)
    }

    /** Opens a whole row as a grid. Several categories, because a row is a merge of them. */
    fun openRow(row: HomeRow) {
        if (row.categoryIds.isEmpty()) return
        push(Screen.Grid(row.categoryIds, row.heading))
        viewModelScope.launch { _grid.value = catalog.titlesInCategories(row.categoryIds, GRID_LIMIT) }
    }

    /** True when it handled the press. False means the app has nowhere left to go and should exit. */
    fun back(): Boolean {
        // Nothing special for the question at the door: it is the first screen there is, so Back on
        // it leaves the app exactly as Back on the home screen does.
        val stack = _stack.value
        if (stack.size <= 1) return false
        _stack.value = stack.dropLast(1)
        // Coming back from the player, the thing that changed is where the viewer got to. Whichever
        // screen is underneath is showing that, so it has to be re-read.
        when (_stack.value.last()) {
            is Screen.Browse -> reloadBrowse()
            is Screen.Detail -> refreshProgress()
            else -> Unit
        }
        return true
    }

    private fun push(screen: Screen) {
        _stack.update { it + screen }
    }

    // ----------------------------------------------------------------------------------- content

    fun setQuery(text: String) {
        _query.value = text
    }

    /**
     * Opens the little menu over a poster.
     *
     * The membership is read before the menu appears rather than guessed from whatever row the
     * poster came out of: a title can be on screen in four places at once, and only one of them is
     * `Mi lista`. A menu that offers to add something already in the list is worse than no menu.
     */
    fun openMenu(title: Title) {
        viewModelScope.launch {
            _menu.value = TitleMenuState(title, catalog.isInWatchlist(title.id))
        }
    }

    fun closeMenu() {
        _menu.value = null
    }

    /** The one thing on the menu. Closes it: the answer is the row redrawing behind it. */
    fun toggleMenuWatchlist() {
        val open = _menu.value ?: return
        catalog.setInWatchlist(open.title.id, !open.inList)
        _menu.value = null
        // The detail page may be underneath and showing the other answer on its own button.
        _detail.update { if (it?.title?.id == open.title.id) it.copy(inWatchlist = !open.inList) else it }
    }

    fun retrySync() = catalog.refresh(System.currentTimeMillis())

    /**
     * Re-reads whatever the browsing screen is showing, and only ever once at a time.
     *
     * Both halves of that matter, and both were learned on a television.
     *
     * **Once at a time.** A full catalogue sync bumps the revision after every batch — a couple of
     * hundred times over a few minutes — and each bump lands here. Left alone, that is a couple of
     * hundred overlapping reads of nineteen rows apiece, all competing with the sync's own writes
     * for the same database. Cancelling the one in flight costs nothing, because its answer was
     * about to be thrown away by the next one anyway.
     *
     * **Not while a film is playing.** Playback saves its position every ten seconds and each save
     * bumps the revision too. Re-reading nineteen rows nobody is looking at, on the processor that
     * is decoding the film, is pure stutter. [back] re-reads on the way out, which is the only
     * moment the answer can have changed.
     */
    private fun reloadBrowse() {
        // Neither of the two screens that are playing something. The live section is on the list
        // for the same reason the player is: a channel holds a decoder and a socket, and nineteen
        // rows nobody is looking at are nineteen rows read on the processor drawing the picture.
        val top = _stack.value.last()
        if (top is Screen.Play || top is Screen.Live) return
        // Nor while the catalogue is being built. A sync bumps the revision after every batch — a
        // couple of hundred times over a few minutes — and each bump used to land here and re-read
        // nineteen rows, on the same database the sync was writing to and for a screen that is now
        // showing a progress ring rather than rows. Not doing it is both the reason the shelves
        // stop shuffling under the cursor and a few hundred reads the sync gets to keep. The
        // revision bumped when it finishes lands after `Running`, so the rows are read exactly
        // once, when there is finally a catalogue to read.
        if (catalog.syncState.value is SyncState.Running) return
        // Said before the read starts, and it matters most when the tab has just changed: what is
        // on screen at this instant is the *previous* tab's answer, and the previous tab's answer
        // to "which rows are there" is often none at all. `Películas` read that as an empty
        // catalogue and announced that nothing had been downloaded, for the tenth of a second it
        // took the real answer to arrive.
        _browse.update { it.copy(loading = true) }
        val current = tab.value
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _browse.value = when (current) {
                // Nothing on this screen is per-kind: what you left half-watched is what you left
                // half-watched, and a home screen that made you pick a tab first would be a menu.
                Tab.Home -> BrowseState(
                    continueEntries = catalog.continueRow(),
                    suggestions = catalog.suggestions(),
                    watchlist = catalog.watchlist(),
                    recentlyAdded = catalog.recentlyAdded(),
                    loading = false
                )

                Tab.Movies, Tab.Series -> BrowseState(
                    continueWatching = catalog.continueWatching()
                        .filter { it.title.kind == current.kind },
                    rows = catalog.home(requireNotNull(current.kind)),
                    loading = false
                )

                Tab.MyList -> BrowseState(watchlist = catalog.watchlist(), loading = false)
                Tab.Search -> BrowseState(loading = false)

                // Unreachable: `selectTab` never puts a `Browse(Live)` on the stack, because live
                // television is not a browsing screen. Answered rather than thrown, since an
                // exhaustive `when` is the only thing that would notice a future mistake here.
                Tab.Live -> BrowseState(loading = false)
            }
        }
    }

    private fun loadDetail(titleId: Long) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val title = catalog.title(titleId) ?: return@launch
            _detail.value = DetailState(
                title = title,
                selected = title.bestSource,
                inWatchlist = catalog.isInWatchlist(titleId),
                // The last thing watched of this title, film or episode alike — that is what the
                // resume button offers.
                progress = catalog.lastProgress(titleId)
            )

            // Whether every copy is really the same film costs a request per copy, so the screen
            // is drawn with all of them and narrowed a moment later. Nothing flickers that matters:
            // what gets dropped is a copy nobody had selected, since the picker opens on the best
            // encode and the best encode is almost never the one that turns out to be a different
            // picture.
            val agreed = catalog.agreeingSources(title)
            val shown = if (agreed.size == title.sources.size) title else title.copy(sources = agreed)
            _detail.update { current ->
                if (current?.title?.id != title.id) current
                else current.copy(
                    title = shown,
                    // The picker cannot stay on a copy that is no longer offered.
                    selected = current.selected?.takeIf { it in agreed } ?: shown.bestSource
                )
            }

            // The plot and the codec report cost a request; the screen is already drawn without
            // them, so they arrive as an update rather than as a reason to show a spinner.
            val detail = catalog.detail(shown)
            _detail.update { it?.copy(detail = detail, loading = title.kind == Kind.Series) }

            if (title.kind == Kind.Series) {
                val episodes = catalog.episodes(shown)
                _detail.update { it?.copy(episodes = episodes, loading = false) }
            }
        }
    }

    private fun refreshProgress() {
        val titleId = _detail.value?.title?.id ?: return
        viewModelScope.launch {
            val progress = catalog.lastProgress(titleId)
            _detail.update { if (it?.title?.id == titleId) it.copy(progress = progress) else it }
        }
    }

    fun selectSource(source: Source) {
        _detail.update { it?.copy(selected = source) }
    }

    fun toggleWatchlist() {
        val state = _detail.value ?: return
        val next = !state.inWatchlist
        _detail.value = state.copy(inWatchlist = next)
        // Empuja la decisión en cuanto se toma, igual que hace el reproductor con la posición. Sin
        // esto la lista sólo viajaría al volver al primer plano, y guardar algo en el móvil para
        // verlo en la tele es justo la cosa que se hace de pie y sin volver a abrir la app.
        //
        // Después de que la fila esté escrita, no a la vez: el guardado va a otro hilo, y una
        // sincronización lanzada en paralelo sale sin nada que mandar.
        viewModelScope.launch {
            catalog.setInWatchlist(state.title.id, next).join()
            container.progressSync.request()
        }
    }

    /**
     * Asks whether to take a card off `Seguir viendo`, rather than doing it.
     *
     * The question used to be a dialog owned by the row itself. It is state here now for the same
     * reason the poster menu is: both are drawn by [OverlayMenu] at the top of the app, where there
     * is a whole screen to darken and nothing that a recycled lazy row can take away mid-question.
     */
    fun askForget(entry: ContinueEntry) {
        _forget.value = entry
    }

    fun cancelForget() {
        _forget.value = null
    }

    /** Takes the card off. The bumped revision is what redraws the row without it. */
    fun confirmForget() {
        val entry = _forget.value ?: return
        _forget.value = null
        catalog.forgetProgress(entry.title.id)
    }

    // ---------------------------------------------------------------------------------- playback

    fun playCurrent(resume: Boolean) {
        val state = _detail.value ?: return
        val source = state.selected ?: state.title.bestSource ?: return
        if (state.title.kind == Kind.Series) {
            val episode = nextEpisode(state, resume) ?: return
            playEpisode(episode, resume = episode.key == state.progress?.episodeId)
            return
        }
        viewModelScope.launch {
            push(
                Screen.Play(
                    movieRequest(
                        title = state.title,
                        source = source,
                        startMillis = if (resume) state.progress?.positionMillis ?: 0L else 0L,
                        tracks = catalog.tracks(state.title.id)
                    )
                )
            )
        }
    }

    /**
     * Which episode `Reproducir` starts, which is not always the one the progress row names.
     *
     * Three cases, and the third is the one that matters: an episode left half-watched is resumed,
     * an episode watched to the end hands over to the one after it, and a series never started
     * begins at the beginning. The next episode is the next one *there is* rather than the next
     * number — seasons end, and the supplier's copy of a series is missing the odd episode in the
     * middle.
     *
     * This lived on the home screen's card until the card stopped playing things directly. Without
     * it, finishing an episode and pressing `Reproducir` sent you back to the first episode of the
     * first season, which is the sort of thing that happens the evening after a finale.
     */
    private fun nextEpisode(state: DetailState, resume: Boolean): Episode? {
        val progress = state.progress?.takeIf { resume }
        return when {
            progress == null -> state.episodes.firstOrNull()
            !progress.isFinished ->
                state.episodes.firstOrNull { it.key == progress.episodeId }
                    ?: state.episodes.firstOrNull()

            else -> state.episodes
                .filter { it.key > progress.episodeId }
                .minByOrNull { it.key }
                ?: state.episodes.firstOrNull()
        }
    }

    fun playEpisode(episode: Episode, resume: Boolean = true) {
        val state = _detail.value ?: return
        viewModelScope.launch {
            val progress = if (resume) catalog.progress(state.title.id, episode.key) else null
            val request = episodeRequest(
                title = state.title,
                episode = episode,
                // The chips at the top of the page choose the encode for the whole series.
                quality = state.selected?.quality,
                startMillis = progress?.positionMillis ?: 0L,
                // Of the series, not of the episode: nobody re-picks a language at episode two.
                tracks = catalog.tracks(state.title.id)
            ) ?: return@launch
            push(Screen.Play(request))
        }
    }

    private fun movieRequest(
        title: Title,
        source: Source,
        startMillis: Long,
        tracks: TrackChoice? = null
    ) = PlayRequest(
        titleId = title.id,
        episodeId = 0,
        copies = title.sources
            .sortedBy { it != source }
            .map { PlayCopy(catalog.movieUrl(it), it.quality.label) },
        heading = title.name,
        subheading = title.year?.toString(),
        startPositionMillis = startMillis,
        tracks = tracks
    )

    /** Null when the episode has no copy at all, which the merge upstream should already prevent. */
    private fun episodeRequest(
        title: Title,
        episode: Episode,
        quality: Quality?,
        startMillis: Long,
        tracks: TrackChoice? = null
    ): PlayRequest? {
        // An episode the chosen copy does not carry falls back to the best copy that does.
        val source = episode.sourceFor(quality) ?: return null
        return PlayRequest(
            titleId = title.id,
            episodeId = episode.key,
            copies = episode.sources
                .sortedBy { it != source }
                .map { PlayCopy(catalog.episodeUrl(it), it.quality.label) },
            heading = title.name,
            subheading = "T${episode.season}:E${episode.number}  ·  ${episode.title}",
            startPositionMillis = startMillis,
            tracks = tracks
        )
    }

    /**
     * Everything the player has to say, on a timer and once more on the way out.
     *
     * The tracks are written even when the duration is not known yet, because they are known from
     * the first frame and the duration sometimes never arrives.
     */
    fun savePosition(
        request: PlayRequest,
        positionMillis: Long,
        durationMillis: Long,
        tracks: TrackChoice
    ) {
        catalog.saveTracks(request.titleId, tracks)
        reportIfSettled(request, positionMillis)
        // Empuja lo que se acaba de guardar. Si hay una vuelta en marcha, ésta se descarta: el
        // guardado siguiente —esto corre en bucle mientras se reproduce— la vuelve a pedir.
        container.progressSync.request()
        if (durationMillis <= 0) return
        catalog.saveProgress(request.titleId, request.episodeId, positionMillis, durationMillis)
    }

    /**
     * Le dice al panel qué hay puesto, una vez que llevar puesto un rato.
     *
     * El umbral se mide sobre lo *reproducido en esta sesión*, no sobre la posición: un título que
     * se retoma en el minuto cuarenta empieza ya por encima de cualquier umbral absoluto, y abrirlo
     * tres segundos para ver de qué iba acabaría contando como haberlo visto.
     *
     * De las series se manda el nombre de la serie y no el del episodio. El panel agrupa por lo que
     * recibe y cuenta repeticiones, así que «catorce veces» junto a una serie dice algo; catorce
     * filas separadas por un episodio no dicen nada.
     */
    private fun reportIfSettled(request: PlayRequest, positionMillis: Long) {
        val key = "${request.titleId}:${request.episodeId}"
        if (key != settledKey) {
            settledKey = key
            settledFromMillis = positionMillis
            return
        }
        if (positionMillis - settledFromMillis < SETTLE_MS) return
        Log.i(TAG, "Puesto un rato: se informa de «${request.heading}»")
        container.reporter.settledOn(
            request.heading,
            // Un episodio siempre trae su identificador; una película va con cero. Es la misma
            // distinción que usa el progreso guardado, así que no hay dos criterios que puedan
            // separarse con el tiempo.
            if (request.episodeId != 0) WatchReporter.Kind.Series else WatchReporter.Kind.Film
        )
    }

    /** Qué se está contando como puesto, y desde qué posición empezó a contar. */
    private var settledKey: String? = null
    private var settledFromMillis: Long = 0

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 220L

        /**
         * Cuánto hay que ver de algo para que cuente como visto.
         *
         * El mismo minuto y pico que usa SimpleTV con los canales, y por lo mismo: separar lo que
         * alguien ha puesto de lo que alguien ha ojeado.
         */
        private const val SETTLE_MS = 60_000L

        private const val TAG = "MainViewModel"
        private const val GRID_LIMIT = 300

        fun factory(container: Container): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
