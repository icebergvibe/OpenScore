package org.openscore.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.nav.MatchKey
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.Lineup
import org.openscore.provider.Capability
import org.openscore.provider.runCatchingUnlessCancelled

data class MatchState(
    /** Null only while a match opened by id alone (a notification, a restored stack) is being found. */
    val game: Game?,
    val events: List<GameEvent>? = null,
    val lineups: List<Lineup>? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * A match screen's state, kept for as long as the screen is on the back stack: opening a team
 * page from it and coming back shows the match as it was, with no request and no spinner.
 * The card's game is shown at once; the provider's fuller `game()` (events where cheap),
 * `events()` and `lineups()` land as they arrive.
 */
class MatchViewModel(private val repository: ScoresRepository, private val key: MatchKey, seed: Game?) : ViewModel() {

    private val mutableState = MutableStateFlow(MatchState(game = seed))
    val state: StateFlow<MatchState> = mutableState.asStateFlow()
    private var load: Job? = null

    private val canEvents = repository.supports(key.leagueId, Capability.EVENTS)
    private val canLineups = repository.supports(key.leagueId, Capability.LINEUPS)

    init { refresh() }

    fun refresh() {
        if (load?.isActive == true) return
        load = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = null) }
            try {
                if (state.value.game == null) {
                    val date = runCatching { LocalDate.parse(key.date) }.getOrNull()
                    val found = date?.let { runCatchingUnlessCancelled { repository.find(key.leagueId, key.gameId, it) }.getOrNull() }
                    if (found == null) {
                        mutableState.update { it.copy(error = "Could not find this game") }
                        return@launch
                    }
                    mutableState.update { it.copy(game = found, events = found.events) }
                }
                coroutineScope {
                    val detail = async { runCatchingUnlessCancelled { repository.game(key.leagueId, key.gameId) } }
                    val lineups = async { if (canLineups) runCatchingUnlessCancelled { repository.lineups(key.leagueId, key.gameId) }.getOrNull() else null }
                    detail.await()
                        .onSuccess { g -> mutableState.update { it.copy(game = g, events = g.events ?: it.events) } }
                        .onFailure { e -> mutableState.update { it.copy(error = e.message) } }
                    if (state.value.events == null && canEvents) {
                        runCatchingUnlessCancelled { repository.events(key.leagueId, key.gameId) }.onSuccess { ev -> mutableState.update { it.copy(events = ev) } }
                    }
                    lineups.await()?.let { lu -> mutableState.update { it.copy(lineups = lu) } }
                }
            } finally {
                mutableState.update { it.copy(loading = false) }
            }
        }
    }

    /**
     * Follows the core's live flow while the caller's scope is active — the screen runs this
     * only while it is resumed, so a match under a team page or a backgrounded app is not
     * polled. Waits for the first load: a game that has finished is never followed.
     */
    suspend fun followLive() {
        state.first { !it.loading }
        val game = state.value.game ?: return
        if (!game.state.isLive && game.state != GameState.PRE_GAME) return
        if (!repository.supports(key.leagueId, Capability.LIVE_UPDATES)) return
        // A live stream that fails leaves the last state on screen rather than an error.
        runCatchingUnlessCancelled {
            repository.live(key.leagueId, key.gameId).collect { g ->
                val before = state.value.game
                val changed = before == null || g.score != before.score || g.state != before.state
                mutableState.update { it.copy(game = g, events = g.events ?: it.events) }
                if (g.events == null && changed && canEvents) {
                    runCatchingUnlessCancelled { repository.events(key.leagueId, key.gameId) }.onSuccess { ev -> mutableState.update { it.copy(events = ev) } }
                }
            }
        }
    }
}
