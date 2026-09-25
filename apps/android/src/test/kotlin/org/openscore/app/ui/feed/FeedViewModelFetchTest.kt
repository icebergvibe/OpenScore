package org.openscore.app.ui.feed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.openscore.OpenScore
import org.openscore.app.data.ScoresRepository
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.concurrent.Executors
import kotlin.time.Instant

/**
 * How [FeedViewModel] coalesces overlapping fetches of the same feed and day. The rule is not
 * "one at a time": a reader's pull and a background poll do not ask for the same thing, and the
 * pull used to be the one that lost.
 *
 * Real dispatchers rather than a test scheduler, because [ScoresRepository] moves its work to
 * [Dispatchers.Default] and a virtual clock cannot see it. The provider hands back a token as
 * each read begins, so the test waits on the work itself instead of on a duration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelFetchTest {

    private val day = LocalDate.parse("2026-09-24")
    private val now = Instant.parse("2026-09-24T18:00:00Z")

    /**
     * One thread, because that is what `Dispatchers.Main` is. [FeedViewModel] keeps its held
     * days and its in-flight jobs in plain maps and is confined to Main for exactly that reason;
     * a test that hands Main a thread pool invents a data race the app does not have, and then
     * fails intermittently on it.
     */
    private val ui: CoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "ui") }.asCoroutineDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(ui)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Enters the view model the way the screen does: on Main. */
    private suspend fun onUi(block: () -> Unit) = withContext(ui) { block() }

    /** Announces each read on [started] and, while held, blocks until released. */
    private class Counting : BaseLeagueProvider() {
        override val league = League("shl", Sport.HOCKEY, "SHL", "SE", TimeZone.of("Europe/Stockholm"))
        override val capabilities = setOf(Capability.GAMES_BY_DATE)
        val started = Channel<Unit>(Channel.UNLIMITED)
        var hold: CompletableDeferred<Unit>? = null

        @Volatile
        var reads = 0
            private set

        override suspend fun gamesOn(date: LocalDate): List<Game> {
            reads++
            started.send(Unit)
            hold?.await()
            return listOf(
                Game(
                    leagueId = league.id, id = "g1", startTime = Instant.parse("2026-09-24T17:00:00Z"),
                    home = TeamRef(league.id, "h", "Home"), away = TeamRef(league.id, "a", "Away"),
                    state = GameState.LIVE,
                ),
            )
        }
    }

    private fun viewModel(provider: Counting) =
        FeedViewModel(ScoresRepository(OpenScore(listOf(provider), umbrellas = emptyMap())))

    private fun spec() = FeedSpec(FeedKind.GAMES, Sport.HOCKEY, listOf("shl"))

    /**
     * Opens the feed and waits until the day is on screen *and* its fetch has finished. Waiting
     * only for [DayState.Loaded] is not enough: the day is written from inside the job, which
     * stays active a moment longer, and a request arriving in that window coalesces into it -
     * correctly, but it makes a test that expected a fresh read flaky rather than failing.
     */
    private suspend fun FeedViewModel.openAndSettle(s: FeedSpec, provider: Counting) {
        onUi { openFeed(s, day) }
        withTimeout(TIMEOUT) { provider.started.receive() }
        withTimeout(TIMEOUT) { days(s).first { it[day] is DayState.Loaded } }
        withTimeout(TIMEOUT) { inFlight.first { it == 0 } }
    }

    @Test
    fun aReadersPullIsNotSwallowedByAPollAlreadyRunning(): Unit = runBlocking {
        val provider = Counting()
        val vm = viewModel(provider)
        val s = spec()
        vm.openAndSettle(s, provider)

        // A poll starts and is left hanging, as a slow host would leave it.
        provider.hold = CompletableDeferred()
        onUi { vm.refreshLive(day, isToday = true, now = now) }
        withTimeout(TIMEOUT) { provider.started.receive() }

        // The reader pulls while that poll is still out. The poll would have been served from
        // the store; the pull is the request that bypasses it, so it must reach the network.
        // Before the fix this token never arrived and the pull did nothing at all.
        onUi { vm.refreshDay(day) }
        withTimeout(TIMEOUT) { provider.started.receive() }
        assertEquals(3, provider.reads)

        provider.hold?.complete(Unit)
        withTimeout(TIMEOUT) { vm.inFlight.first { it == 0 } }
    }

    @Test
    fun twoPollsOfTheSameLeaguesStillCoalesce(): Unit = runBlocking {
        val provider = Counting()
        val vm = viewModel(provider)
        val s = spec()
        vm.openAndSettle(s, provider)

        provider.hold = CompletableDeferred()
        onUi { vm.refreshLive(day, isToday = true, now = now) }
        withTimeout(TIMEOUT) { provider.started.receive() }

        // The second poll asks for nothing the first will not bring back, so it is dropped.
        onUi { vm.refreshLive(day, isToday = true, now = now) }
        assertNull(withTimeoutOrNull(QUIET) { provider.started.receive() }, "the 60 s poll must not stack on itself")
        assertEquals(2, provider.reads)

        provider.hold?.complete(Unit)
        withTimeout(TIMEOUT) { vm.inFlight.first { it == 0 } }
    }

    @Test
    fun aSupersededFetchTakesItsInFlightCountWithIt(): Unit = runBlocking {
        val provider = Counting()
        val vm = viewModel(provider)
        val s = spec()
        vm.openAndSettle(s, provider)

        provider.hold = CompletableDeferred()
        onUi { vm.refreshLive(day, isToday = true, now = now) }
        withTimeout(TIMEOUT) { provider.started.receive() }
        onUi { vm.refreshDay(day) }
        withTimeout(TIMEOUT) { provider.started.receive() }

        provider.hold?.complete(Unit)
        // The superseded job was cancelled. Its count has to come back down with it, or the
        // feed's spinner never stops turning.
        withTimeout(TIMEOUT) { vm.inFlight.first { it == 0 } }
    }

    @Test
    fun aFeedEvictedWhileFetchingLeavesNothingInFlight(): Unit = runBlocking {
        val provider = Counting()
        val vm = viewModel(provider)
        provider.hold = CompletableDeferred()

        onUi { vm.openFeed(spec(), day) }
        withTimeout(TIMEOUT) { provider.started.receive() }
        // Seven more feeds push the first out of the six the view model keeps, cancelling its
        // work part-way through.
        repeat(7) { n ->
            onUi { vm.openFeed(FeedSpec(FeedKind.GAMES, Sport.HOCKEY, listOf("shl", "x$n")), day) }
            withTimeout(TIMEOUT) { provider.started.receive() }
        }

        provider.hold?.complete(Unit)
        withTimeout(TIMEOUT) { vm.inFlight.first { it == 0 } }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        /** Long enough that a request that was going to be made would have been. */
        const val QUIET = 300L
    }
}
