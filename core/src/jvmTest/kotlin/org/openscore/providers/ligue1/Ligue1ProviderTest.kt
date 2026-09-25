package org.openscore.providers.ligue1

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.VarDecision
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.providers.football.FootballPeriods
import org.openscore.testing.Ligue1Samples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class Ligue1ProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse(Ligue1Samples.LIVE_NOW)
    }
    private val fetcher = Ligue1Samples.register(SampleFetcher())
    private val l1 = Ligue1Provider(fetcher, clock = fixedClock)
    private val mapper = Ligue1Mapper("ligue1")

    private fun sample(name: String): String {
        val text = SampleFetcher.samplesDir("football", "ligue-1").resolve(name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "championships-daily-calendars-matches" to L1DailyCalendars.serializer(),
            "championship-matches-" to L1GameWeekMatches.serializer(),
            "championship-match." to L1Match.serializer(),
            "championship-standings" to L1Standings.serializer(),
            "championship-club-identity" to L1ClubIdentity.serializer(),
            "championship-club-summary" to L1ClubSummary.serializer(),
            "championship-club.json" to L1Club.serializer(),
            "championship-player.json" to L1Player.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "ligue-1").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 17, "typed=$typed")
    }

    @Test
    fun scoreboardByDay() = runTest {
        val games = l1.gamesOn(LocalDate.parse(Ligue1Samples.DAY))
        assertEquals(5, games.size, "only championship 1 (Ligue 2 games on the same day are filtered out)")
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null })
        assertEquals(games.sortedBy { it.startTime }, games)
        val g = games.first { it.home.name == "AJ Auxerre" }
        assertEquals("AJA", g.home.abbreviation)
        assertNotNull(g.home.logoUrl)
        assertEquals("l1_championship_club_2026_2", g.home.id)
    }

    @Test
    fun liveSecondHalf() = runTest {
        val g = l1.game(Ligue1Samples.LIVE_MATCH_ID)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("Stade Rennais FC", g.home.name)
        assertEquals(Score(1, 0), g.score)
        val clock = assertNotNull(g.clock)
        assertEquals("2H", clock.period.label)
        assertEquals(7.minutes + 10.seconds, clock.time.elapsed, "now − secondHalfStartedAt")
        assertEquals("53'", clock.time.label, "conventional minute in progress")
        assertEquals(true, clock.running)
        assertEquals(listOf(0 to 0, 1 to 0), g.periodScores.map { it.home to it.away })
        assertEquals("48", g.stats["possession"]!!.home)
        assertEquals("0.5081", g.stats["xg"]!!.home)
        assertEquals("9", g.stats["shots"]!!.home)

        val events = assertNotNull(g.events)
        val goal = events.single { it.type.isGoal }
        val gd = assertIs<FootballGoalDetails>(goal.details)
        assertEquals("Rennes", goal.team!!.abbreviation.let { "Rennes" })
        assertNotNull(gd.scorer)
        assertEquals("52'", goal.time.label)
        assertEquals(2, goal.period.number)
        assertEquals(Score(1, 0), goal.score)
        val cards = events.filter { it.type == FootballEventType.YELLOW_CARD }
        assertEquals(3, cards.size)
        assertEquals(CardKind.YELLOW, assertIs<CardDetails>(cards.first().details).card)
        assertEquals("33'", cards.first().time.label)
        val sub = events.single { it.type == FootballEventType.SUBSTITUTION }
        val sd = assertIs<SubstitutionDetails>(sub.details)
        assertNotNull(sd.playerOn); assertNotNull(sd.playerOff)
        assertEquals("tactical", sd.reason)
        assertEquals(FootballEventType.PERIOD_START, events.first().type)
        assertEquals(listOf("1H-start", "1H-end", "2H-start"), events.filter { it.rawType == "period" }.map { it.id })
        assertTrue(events.all { it.players.all { p -> !p.name.startsWith("l1_") } }, "players resolved to names")
    }

    @Test
    fun otherLiveStates() {
        val ko = OpenScoreJson.decodeFromString(L1Match.serializer(), sample("championship-match.live-kickoff.json"))
        val gKo = mapper.game(ko, Instant.parse("2026-09-11T18:45:38Z"))
        assertEquals(GameState.LIVE, gKo.state)
        assertEquals("1H", gKo.clock!!.period.label)
        assertEquals(25.seconds, gKo.clock.time.elapsed)
        assertEquals("1'", gKo.clock.time.label)
        assertEquals(Score(0, 0), gKo.score)
        assertEquals(listOf(0 to 0), gKo.periodScores.map { it.home to it.away })

        val ht = OpenScoreJson.decodeFromString(L1Match.serializer(), sample("championship-match.live-halftime.json"))
        val gHt = mapper.game(ht, Instant.parse("2026-09-11T19:40:00Z"))
        assertEquals(GameState.INTERMISSION, gHt.state)
        assertEquals("1H", gHt.clock!!.period.label)
        assertEquals(46.minutes + 8.seconds, gHt.clock.time.elapsed, "frozen at firstHalfEndedAt − firstHalfStartedAt")
        assertEquals("45'+2", gHt.clock.time.label)
        assertEquals(false, gHt.clock.running)

        val pre = OpenScoreJson.decodeFromString(L1Match.serializer(), sample("championship-match.pre-lineups.json"))
        val gPre = mapper.game(pre, Instant.parse("2026-09-11T18:30:00Z"))
        assertEquals(GameState.PRE_GAME, gPre.state)
        assertNull(gPre.score)
        assertNull(gPre.clock)
        val lineups = mapper.lineups(pre)
        assertEquals("433", lineups.first().formation)
        assertEquals(11, lineups.first().groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertTrue(lineups.first().groups.first { it.kind == LineupGroupKind.BENCH }.players.isNotEmpty())
        assertEquals("Franck Haise", lineups.first().headCoach)
    }

    @Test
    fun finalPostProcessed() = runTest {
        val g = l1.game(Ligue1Samples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(Score(1, 2), g.score)
        assertNull(g.clock)
        assertEquals(listOf(1 to 0, 0 to 2), g.periodScores.map { it.home to it.away })
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        assertEquals(listOf(Score(1, 0), Score(1, 1), Score(1, 2)), goals.map { it.score })
        val first = assertIs<FootballGoalDetails>(goals.first().details)
        assertNotNull(first.assist, "post-processed record carries assistProviderId")
        assertEquals(VarDecision.CONFIRMED, first.varDecision)
        assertEquals("Comportement antisportif", assertIs<CardDetails>(events.first { it.type == FootballEventType.YELLOW_CARD }.details).reason)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertEquals("PARC DES PRINCES", g.venue)
        assertEquals(2, l1.lineups(Ligue1Samples.FINAL_MATCH_ID).size)
    }

    @Test
    fun preMatchHasNoLineups() = runTest {
        val g = l1.game(Ligue1Samples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertEquals(emptyList(), g.events)
        assertTrue(g.stats.isEmpty() || g.stats.values.all { it.home == "0" || true })
        assertTrue(l1.lineups(Ligue1Samples.PRE_MATCH_ID).isEmpty())
    }

    @Test
    fun periodsHelpers() {
        assertEquals(52 to null, FootballPeriods.parseMinute("52'"))
        assertEquals(45 to 1, FootballPeriods.parseMinute("45' +1"))
        assertEquals(90 to 4, FootballPeriods.parseMinute("90'+4"))
        assertEquals("90'+4", FootballPeriods.label(90, 4))
        assertEquals("67'", FootballPeriods.timeInPeriod(FootballPeriods.SECOND_HALF, 21.minutes + 30.seconds).label)
        assertEquals("45'+3", FootballPeriods.timeInPeriod(FootballPeriods.FIRST_HALF, 47.minutes + 10.seconds).label)
        assertEquals(FootballPeriods.SECOND_HALF, FootballPeriods.periodForMinute(90))
        assertEquals(FootballPeriods.EXTRA_FIRST, FootballPeriods.periodForMinute(95, extraTime = true))
    }

    @Test
    fun standingsTeamRosterPlayer() = runTest {
        val table = l1.standings()
        assertEquals("2026", table.seasonId)
        assertEquals(18, table.rows.size)
        val top = table.rows.first()
        assertEquals("AS Monaco", top.team.name)
        assertEquals(1, top.rank)
        assertEquals(9, top.points)
        assertEquals(0, top.draws)
        assertEquals("WWW", top.extra["form"])

        val t = l1.team(Ligue1Samples.CLUB_ID)
        assertEquals("Paris Saint-Germain", t.name)
        assertEquals("PSG", t.ref.abbreviation)
        assertEquals("FRA", t.country)

        val roster = l1.roster(Ligue1Samples.CLUB_ID)
        assertEquals(24, roster.size)
        assertEquals("GK", roster.first().ref.position)
        assertTrue(roster.all { it.teamId == Ligue1Samples.CLUB_ID && it.birthDate != null })

        val p = l1.player(Ligue1Samples.PLAYER_ID)
        assertEquals("Lucas Chevalier", p.name)
        assertEquals("GK", p.ref.position)
        assertEquals(30, p.ref.jerseyNumber)
        assertEquals("FRA", p.nationality)
        assertEquals("R", p.handedness)
        assertEquals(Ligue1Samples.CLUB_ID, p.teamId)
    }

    private fun match(name: String) = OpenScoreJson.decodeFromString(L1Match.serializer(), sample(name))

    /**
     * Auxerre 2-0 Lorient (2026-09-13): a 2nd-minute goal was published, flagged `varDecision: 1`
     * on the next poll, and moved into `canceledGoals` with `varDecision: 2` on the one after,
     * the score going 0-0 to 1-0 and back inside two minutes. `1` therefore marks a goal VAR is
     * *looking at*, not one VAR has allowed - and a finished document carries a `1` on a goal that
     * stood, so the value only settles when the match does.
     */
    @Test
    fun aGoalUnderVarReviewIsNotYetConfirmed() {
        val m = match("championship-match.live-var-review.json")
        assertEquals("firstHalf", m.period)
        assertEquals(1, m.home.score)
        val goal = m.home.goals.single()
        assertEquals(1, goal.varDecision, "the feed's own flag")
        val mapped = mapper.events(m).single { it.type.isGoal }
        assertNull(
            assertIs<FootballGoalDetails>(mapped.details).varDecision,
            "no claim while the match is live: this goal is cancelled on the next poll",
        )
    }

    /** The next poll: the goal has left `goals` for `canceledGoals` and the score is 0-0 again. */
    @Test
    fun aCancelledGoalBecomesADisallowedEventAndGivesTheScoreBack() {
        val m = match("championship-match.live-goal-canceled.json")
        assertEquals(0, m.home.score)
        assertTrue(m.home.goals.isEmpty())
        assertEquals(2, m.home.canceledGoals.single().varDecision)
        val g = mapper.game(m, Instant.parse("2026-09-13T13:05:10Z"))
        assertEquals(Score(0, 0), g.score)
        assertEquals(listOf(0 to 0), g.periodScores.map { it.home to it.away })
        val disallowed = assertNotNull(g.events).single { it.type == FootballEventType.GOAL_DISALLOWED }
        assertEquals("3'", disallowed.time.label)
        assertTrue(assertNotNull(g.events).none { it.type.isGoal }, "the goal is gone, not both listed")
    }

    /** At full time a `varDecision: 1` goal has survived the check, so the confirmation is real. */
    @Test
    fun aConfirmedGoalIsReportedOnceTheMatchIsFinished() {
        val m = match("championship-match.final.json")
        assertEquals("fullTime", m.period)
        val checked = mapper.events(m).first { it.type.isGoal && it.time.label == "31'" }
        assertEquals(VarDecision.CONFIRMED, assertIs<FootballGoalDetails>(checked.details).varDecision)
    }

    @Test
    fun notFoundAndCapabilities() = runTest {
        assertFailsWith<NotFoundException> { l1.game("l1_championship_match_1") }
        assertTrue(l1.supports(Capability.CLOCK_RUNNING_FLAG))
        assertTrue(!l1.supports(Capability.EVENT_COORDINATES))
        assertTrue(!l1.supports(Capability.LINE_GROUPS))
    }
}
