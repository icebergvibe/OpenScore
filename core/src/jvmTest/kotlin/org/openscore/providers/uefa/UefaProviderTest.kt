package org.openscore.providers.uefa

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.StageKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.providers.football.FootballPeriods
import org.openscore.testing.SampleFetcher
import org.openscore.testing.UefaSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class UefaProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse(UefaSamples.NOW)
    }
    private val fetcher = UefaSamples.register(SampleFetcher())
    private val ucl = ChampionsLeagueProvider(fetcher, clock = fixedClock)
    private val uel = EuropaLeagueProvider(fetcher, clock = fixedClock)
    private val uecl = ConferenceLeagueProvider(fetcher, clock = fixedClock)
    private val unl = NationsLeagueProvider(fetcher, clock = fixedClock)
    private val mapper = UefaMapper("ucl")

    private fun sample(name: String): String {
        val text = SampleFetcher.samplesDir("football", "uefa").resolve(name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "competitions" to ListSerializer(UefaCompetition.serializer()),
            "matches." to ListSerializer(UefaMatch.serializer()),
            "match." to UefaMatch.serializer(),
            "match-events" to ListSerializer(UefaEvent.serializer()),
            "match-lineups" to UefaLineups.serializer(),
            "team-statistics" to ListSerializer(UefaTeamStatistics.serializer()),
            "livescore" to ListSerializer(UefaLivescore.serializer()),
            "standings" to ListSerializer(UefaStandings.serializer()),
            "teams" to ListSerializer(UefaTeam.serializer()),
            "players" to ListSerializer(UefaPerson.serializer()),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "uefa").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            if (file.name == "match.not-found.json") continue
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 30, "typed=$typed")
    }

    @Test
    fun capabilities() {
        assertTrue(ucl.supports(Capability.CLOCK_RUNNING_FLAG))
        assertTrue(ucl.supports(Capability.INTERMISSION_STATE))
        assertTrue(ucl.supports(Capability.EVENT_COORDINATES))
        assertFalse(ucl.supports(Capability.ROSTER), "no squad endpoint")
        assertFalse(ucl.supports(Capability.LIVE_PUSH))
        assertEquals(2027, ucl.currentSeason())
        assertEquals(setOf("ucl", "uel", "uecl"), setOf(ucl.league.id, uel.league.id, uecl.league.id))
    }

    @Test
    fun scoreboardOfAFinishedMatchday() = runTest {
        val games = ucl.gamesOn(LocalDate.parse(UefaSamples.DAY))
        assertEquals(6, games.size)
        assertEquals(games.sortedBy { it.startTime }, games)
        assertTrue(games.all { it.state == GameState.FINAL && it.leagueId == "ucl" && it.seasonId == "2027" })
        val rma = games.first { it.id == UefaSamples.FINAL_MATCH_ID }
        assertEquals("Real Madrid", rma.home.name)
        assertEquals("RMA", rma.home.abbreviation)
        assertEquals("Inter", rma.away.name)
        assertEquals(Score(2, 1), rma.score)
        assertEquals(Instant.parse("2026-09-08T19:00:00Z"), rma.startTime)
        assertEquals("League Phase", rma.competition)
        assertEquals(StageKind.REGULAR, rma.stage)
        assertEquals(GameEnding.REGULATION, rma.ending)
        assertNull(rma.clock)
        assertNull(rma.events, "the list call does not load the timeline")
        // Period scores from playerEvents.scorers: Mbappé 14', Valverde 23' (1H, home), Carlos Augusto 77' (2H, away).
        assertEquals(listOf(2 to 0, 0 to 1), rma.periodScores.map { it.home to it.away })
        assertEquals("Estadio Santiago Bernabéu", rma.venue)
        assertTrue(rma.home.logoUrl!!.contains("240x240"))
    }

    @Test
    fun upcomingMatchdayHasNoScores() = runTest {
        val games = uel.gamesOn(LocalDate.parse(UefaSamples.PRE_DAY))
        assertEquals(9, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null && it.periodScores.isEmpty() && it.leagueId == "uel" })
        assertEquals("Omonia", games.first().home.name)
        assertEquals(18, uecl.gamesOn(LocalDate.parse(UefaSamples.UECL_DAY)).size)
        assertEquals(emptyList(), ucl.gamesOn(LocalDate.parse(UefaSamples.EMPTY_DAY)))
    }

    @Test
    fun finishedGameWithTimelineAndStats() = runTest {
        val g = ucl.game(UefaSamples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(2, 1), g.score)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals("FINISHED", g.rawState)
        val events = assertNotNull(g.events)
        assertEquals(38, events.size)
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: Long.MAX_VALUE })), events)

        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        val first = goals[0]
        assertEquals(FootballEventType.GOAL, first.type)
        assertEquals("Kylian Mbappé", first.players.single().name)
        assertEquals(Score(1, 0), first.score, "running score, not the feed's final totalScore")
        assertEquals(13.minutes + 31.seconds, first.time.elapsed)
        assertEquals("14'", first.time.label)
        assertEquals(FootballPeriods.FIRST_HALF, first.period)
        assertNotNull(first.coordinates)
        assertEquals(Score(2, 1), goals[2].score)
        assertEquals(FootballPeriods.SECOND_HALF, goals[2].period)
        assertEquals(g.away.id, goals[2].team!!.id)
        assertIs<FootballGoalDetails>(first.details)
        assertNull(first.details.assist, "MAIN carries no ASSIST events")

        val sub = events.first { it.type == FootballEventType.SUBSTITUTION }
        val details = assertIs<SubstitutionDetails>(sub.details)
        assertEquals("John Stones", details.playerOn!!.name)
        assertEquals("Benjamin Pavard", details.playerOff!!.name)
        assertEquals("half_time", details.reason)
        assertEquals(FootballPeriods.SECOND_HALF, sub.period)

        assertEquals(4, events.count { it.type == FootballEventType.YELLOW_CARD })
        assertEquals(2, events.count { it.type == FootballEventType.PERIOD_START })
        assertEquals(2, events.count { it.type == FootballEventType.PERIOD_END })
        val ht = events.first { it.type == FootballEventType.PERIOD_END }
        assertEquals("45'+2", ht.time.label)
        assertEquals(46.minutes + 24.seconds, ht.time.elapsed)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertEquals(15, events.count { it.rawType == "CORNER" })
        assertEquals("+1 min added", events.first { it.rawType == "INJURY_TIME" }.description)

        assertEquals(listOf(2 to 0, 0 to 1), g.periodScores.map { it.home to it.away })
        assertEquals("39", g.stats["possession"]!!.home)
        assertEquals("61", g.stats["possession"]!!.away)
        assertEquals("16", g.stats["shots"]!!.home)
        assertEquals("11", g.stats["corners"]!!.away)
        assertEquals("117.049", g.stats["distanceKm"]!!.away)
        assertEquals(15, g.stats.size)
    }

    @Test
    fun upcomingGame() = runTest {
        val g = uel.game(UefaSamples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.clock)
        assertEquals(emptyList(), g.events, "UPCOMING: the timeline is known to be empty, not unloaded")
        assertTrue(g.stats.isEmpty())
        assertEquals("Omonia", g.home.name)
        assertEquals("Celta", g.away.name)
        assertEquals(emptyList(), uel.lineups(UefaSamples.PRE_MATCH_ID))
        assertFalse(fetcher.requests.any { it.contains("/events") || it.contains("team-statistics") }, "no timeline/stats calls before kick-off")
    }

    @Test
    fun preGameOnceLineupsArePublished() {
        val m = OpenScoreJson.decodeFromString(UefaMatch.serializer(), sample("match.pre.json")).copy(lineupStatus = "TACTICAL_AVAILABLE")
        assertEquals(GameState.PRE_GAME, mapper.gameState(m))
    }

    @Test
    fun finalDecidedOnPenalties() = runTest {
        val g = ucl.game(UefaSamples.PENALTIES_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(1, 1), g.score, "shoot-out goals are not part of the score")
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(StageKind.PLAYOFF, g.stage)
        assertEquals("Final", g.competition)
        val events = assertNotNull(g.events)
        val kicks = events.filter { it.type == FootballEventType.SHOOTOUT_ATTEMPT }
        assertEquals(10, kicks.size)
        assertTrue(kicks.all { it.period == FootballPeriods.PENALTIES })
        val misses = kicks.filter { !(it.details as ShootoutAttemptDetails).scored }
        assertEquals(listOf("Eberechi Eze", "Nuno Mendes", "Gabriel"), misses.map { it.players.single().name })
        val staffCard = events.first { it.type == FootballEventType.YELLOW_CARD && it.players.single().position == "COACH" }
        assertEquals("Mikel Arteta", staffCard.players.single().name)
        assertEquals("48233", staffCard.players.single().id)
        assertEquals(listOf("wide", "saved", "wide"), misses.map { (it.details as ShootoutAttemptDetails).shotType })
        assertEquals("Paris", kicks.first().team!!.name)
        val penaltyGoal = events.first { it.type == FootballEventType.PENALTY_GOAL }
        assertEquals(GoalKind.PENALTY, (penaltyGoal.details as FootballGoalDetails).kind)
        assertEquals(Score(1, 1), penaltyGoal.score)
        assertEquals(5, events.count { it.type == FootballEventType.PERIOD_START })
        assertEquals(listOf(FootballPeriods.FIRST_HALF, FootballPeriods.SECOND_HALF, FootballPeriods.EXTRA_FIRST, FootballPeriods.EXTRA_SECOND, FootballPeriods.PENALTIES), events.filter { it.type == FootballEventType.PERIOD_START }.map { it.period })
        assertEquals(listOf(0 to 1, 1 to 0, 0 to 0, 0 to 0), g.periodScores.map { it.home to it.away }, "Gyökeres 6', Ramos pen 65', goalless extra time")
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertEquals(FootballPeriods.PENALTIES, events.last().period)
    }

    @Test
    fun extraTimeAndTwoLegs() = runTest {
        val et = ucl.game(UefaSamples.EXTRA_TIME_MATCH_ID)
        assertEquals(Score(3, 2), et.score, "score.total includes extra time")
        assertEquals(GameEnding.OVERTIME, et.ending)
        assertEquals(4, et.periodScores.size)
        assertEquals(3 to 2, et.periodScores.sumOf { it.home } to et.periodScores.sumOf { it.away })
        assertEquals(StageKind.PLAYOFF, et.stage)

        val leg = ucl.game(UefaSamples.TWO_LEGS_MATCH_ID)
        assertEquals(Score(1, 2), leg.score, "aggregate is not the match score")
        assertEquals(GameEnding.REGULATION, leg.ending)
        assertEquals(StageKind.OTHER, leg.stage, "qualifying")
        assertEquals("First qualifying round", leg.competition)
    }

    @Test
    fun ownGoalCreditsTheOpponent() = runTest {
        val m = OpenScoreJson.decodeFromString(UefaMatch.serializer(), sample("match.final.json"))
            .copy(id = UefaSamples.OWN_GOAL_MATCH_ID, homeTeam = UefaTeam("2605572", internationalName = "L. Red Imps"), awayTeam = UefaTeam("63401", internationalName = "Inter Escaldes"))
        val raw = OpenScoreJson.decodeFromString(ListSerializer(UefaEvent.serializer()), sample("match-events.main.own-goal.json"))
        val goals = mapper.events(m, raw).filter { it.type.isGoal }
        assertEquals(listOf(FootballEventType.PENALTY_GOAL, FootballEventType.GOAL, FootballEventType.OWN_GOAL, FootballEventType.GOAL), goals.map { it.type })
        val own = goals[2]
        assertEquals("63401", own.team!!.id, "own goal by a Red Imps player counts for Inter Escaldes")
        assertEquals("Christian Rutjens", own.players.single().name)
        assertEquals(Score(2, 1), own.score)
        assertEquals("45'+2", own.time.label)
        assertEquals(Score(3, 1), goals.last().score)
    }

    @Test
    fun eventTimes() {
        assertEquals(13.minutes + 31.seconds, mapper.eventTime(FootballPeriods.FIRST_HALF, UefaEventTime(14, 31)).elapsed)
        assertEquals(0.seconds, mapper.eventTime(FootballPeriods.FIRST_HALF, UefaEventTime(1, 0)).elapsed)
        assertEquals(46.minutes + 10.seconds, mapper.eventTime(FootballPeriods.FIRST_HALF, UefaEventTime(45, 10, 2)).elapsed)
        assertEquals("45'+2", mapper.eventTime(FootballPeriods.FIRST_HALF, UefaEventTime(45, 10, 2)).label)
        assertEquals(0.seconds, mapper.eventTime(FootballPeriods.SECOND_HALF, UefaEventTime(46, 0)).elapsed)
        assertEquals(31.minutes + 2.seconds, mapper.eventTime(FootballPeriods.SECOND_HALF, UefaEventTime(77, 2)).elapsed)
        assertEquals("77'", mapper.eventTime(FootballPeriods.SECOND_HALF, UefaEventTime(77, 2)).label)
        assertEquals(0.seconds, mapper.eventTime(FootballPeriods.EXTRA_FIRST, UefaEventTime(91, 0)).elapsed)
        assertNull(mapper.eventTime(FootballPeriods.PENALTIES, null).elapsed)
    }

    @Test
    fun liveClockFromPhaseTimestamps() {
        // Simulate a live match from the finished one: second half in progress, 20 min after its START_PHASE.
        val m = OpenScoreJson.decodeFromString(UefaMatch.serializer(), sample("match.final.json")).copy(status = "LIVE", phase = "SECOND_HALF", fullTimeAt = null)
        val raw = OpenScoreJson.decodeFromString(ListSerializer(UefaEvent.serializer()), sample("match-events.main.final.json"))
            .filter { it.timestamp!! <= "2026-09-08T20:26:04.005Z" && it.type != "FULL_TIME" }
        val now = Instant.parse("2026-09-08T20:26:04.005Z") // START_PHASE SECOND_HALF was 20:06:04.005Z
        val g = mapper.game(m, raw, null, now)
        assertEquals(GameState.LIVE, g.state)
        val clock = assertNotNull(g.clock)
        assertEquals(FootballPeriods.SECOND_HALF, clock.period)
        assertEquals(20.minutes, clock.time.elapsed)
        assertEquals("66'", clock.time.label)
        assertEquals(true, clock.running)
        assertEquals(listOf(2 to 0, 0 to 0), g.periodScores.map { it.home to it.away })

        // Half-time: the feed still says FIRST_HALF but END_PHASE has been logged.
        val ht = m.copy(phase = "FIRST_HALF")
        val htRaw = raw.filter { it.timestamp!! <= "2026-09-08T19:50:00Z" }
        val h = mapper.game(ht, htRaw, null, Instant.parse("2026-09-08T19:55:00Z"))
        assertEquals(GameState.INTERMISSION, h.state)
        assertEquals(false, h.clock!!.running)
        assertEquals(46 * 60 + 24, h.clock.time.elapsed!!.inWholeSeconds)
        assertEquals("45'+2", h.clock.time.label)

        // Explicit break phase.
        assertEquals(GameState.INTERMISSION, mapper.gameState(m.copy(phase = "HALF_TIME_BREAK")))

        // No events (list call): minute-level clock.
        val list = mapper.game(m.copy(minute = UefaMinute(67, null)), null, null, now)
        assertEquals("67'", list.clock!!.time.label)
        assertEquals(22.minutes, list.clock.time.elapsed)
        assertEquals(true, list.clock.running)
    }

    @Test
    fun lineups() = runTest {
        val lineups = ucl.lineups(UefaSamples.FINAL_MATCH_ID)
        assertEquals(2, lineups.size)
        val home = lineups[0]
        assertEquals("Real Madrid", home.team.name)
        assertEquals("José Mourinho", home.headCoach)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        val gk = home.groups.first().players.first()
        assertEquals("Thibaut Courtois", gk.name)
        assertEquals(1, gk.jerseyNumber)
        assertEquals("GK", gk.position)
        assertEquals("Cristian Chivu", lineups[1].headCoach)
        assertNull(home.formation)
    }

    @Test
    fun standings() = runTest {
        val table = ucl.standings()
        assertEquals("2027", table.seasonId)
        assertEquals("league", table.grouping)
        assertEquals(1, table.groups.size)
        assertEquals("League Phase", table.groups.single().label)
        assertEquals(36, table.rows.size)
        val top = table.rows.first()
        assertEquals("Paris", top.team.name)
        assertEquals(1, top.rank)
        assertEquals(3, top.points)
        assertEquals(1, top.played)
        assertEquals("132.0", top.extra["coefficient"])
        assertEquals("Bodø/Glimt", table.rows.last().team.name)
        assertEquals(36, uel.standings().rows.size)
        assertEquals("2026", ucl.standings("2026").seasonId)
    }

    @Test
    fun teamAndPlayer() = runTest {
        val team = ucl.team(UefaSamples.TEAM_ID)
        assertEquals("Real Madrid", team.name)
        assertEquals("RMA", team.ref.abbreviation)
        assertEquals("ESP", team.country)
        assertTrue(team.logoDarkUrl!!.contains("700x700"))
        val p = ucl.player(UefaSamples.PLAYER_ID)
        assertEquals("Kylian Mbappé", p.name)
        assertEquals("Kylian", p.firstName)
        assertEquals(LocalDate(1998, 12, 20), p.birthDate)
        assertEquals("FRA", p.nationality)
        assertEquals(178, p.heightCm)
        assertEquals("FW", p.ref.position)
        assertEquals(10, p.ref.jerseyNumber)
        assertEquals(UefaSamples.TEAM_ID, p.teamId)
        assertFailsWith<UnsupportedCapabilityException> { ucl.roster(UefaSamples.TEAM_ID) }
        assertFailsWith<NotFoundException> { ucl.game("1") }
    }

    @Test
    fun liveFlowEndsOnAFinishedGame() = runTest {
        val emitted = ucl.live(UefaSamples.FINAL_MATCH_ID).toList()
        assertEquals(1, emitted.size)
        assertEquals(GameState.FINAL, emitted.single().state)
        assertTrue(fetcher.requests.any { it.endsWith("/livescore") })
    }

    // ---- Nations League (competition 2014, national teams) --------------------------------

    @Test
    fun nationsLeagueCapabilities() = runTest {
        assertEquals("unl", unl.league.id)
        assertTrue(unl.supports(Capability.GAMES_BY_DATE))
        assertTrue(unl.supports(Capability.STANDINGS))
        assertTrue(unl.supports(Capability.TEAM_SCHEDULE))
        assertTrue(unl.supports(Capability.LIVE_UPDATES))
        assertFalse(unl.supports(Capability.ROSTER), "national squads are not in the crosswalk")
        assertFailsWith<UnsupportedCapabilityException> { unl.roster(UefaSamples.UNL_TEAM_ID) }
    }

    /** The competition is biennial: its seasons are odd end years, and an edition runs into the next spring. */
    @Test
    fun nationsLeagueSeasonsAreOddEndYears() {
        fun seasonAt(instant: String): Int =
            NationsLeagueProvider(fetcher, clock = object : Clock { override fun now(): Instant = Instant.parse(instant) }).currentSeason()
        assertEquals(2027, seasonAt("2026-09-24T12:00:00Z"), "opening matchday")
        assertEquals(2027, seasonAt("2027-06-30T12:00:00Z"), "the finals")
        assertEquals(2027, seasonAt("2027-08-01T12:00:00Z"), "July would otherwise roll to 2028")
        assertEquals(2027, seasonAt("2028-03-26T12:00:00Z"), "the play-offs belong to the 2027 edition")
        assertEquals(2029, seasonAt("2028-09-01T12:00:00Z"), "the next edition")
        // The club competitions keep the plain rule.
        assertEquals(2027, ucl.currentSeason())
    }

    @Test
    fun nationsLeagueMatchday() = runTest {
        val games = unl.gamesOn(LocalDate.parse(UefaSamples.UNL_DAY))
        assertEquals(8, games.size)
        assertTrue(games.all { it.leagueId == "unl" && it.seasonId == "2027" && it.state == GameState.SCHEDULED })
        val and = games.first { it.id == UefaSamples.UNL_PRE_MATCH_ID }
        assertEquals("Andorra", and.home.name)
        assertEquals("Malta", and.away.name)
        assertEquals(Instant.parse("2026-09-24T16:00:00Z"), and.startTime)
        assertEquals(StageKind.REGULAR, and.stage)
        // The round, not the group, names the section: a matchday must stay one block in the feed.
        assertEquals("League phase", and.competition)
        assertTrue(games.all { it.competition == "League phase" })
        // National teams are not clubs, and their crest is the country's flag.
        assertNull(and.home.clubId)
        assertNull(and.away.clubId)
        assertTrue(and.away.logoUrl!!.contains("/flags/"))
        assertEquals("MLT", and.away.abbreviation)
        assertEquals(emptyList(), unl.gamesOn(LocalDate.parse(UefaSamples.EMPTY_DAY)))
    }

    @Test
    fun nationsLeagueFinalDecidedOnPenalties() = runTest {
        val game = unl.game(UefaSamples.UNL_PENALTIES_MATCH_ID)
        assertEquals(GameState.FINAL, game.state)
        assertEquals("Portugal", game.home.name)
        assertEquals("Spain", game.away.name)
        assertEquals(Score(2, 2), game.score)
        assertEquals(GameEnding.SHOOTOUT, game.ending)
        assertEquals("Final", game.competition)
        assertEquals(StageKind.PLAYOFF, game.stage)
        val events = assertNotNull(game.events)
        assertTrue(events.any { it.type == FootballEventType.GOAL })
        assertTrue(events.any { it.type == FootballEventType.SHOOTOUT_ATTEMPT }, "the shoot-out kicks are mapped")
        val lineups = unl.lineups(UefaSamples.UNL_PENALTIES_MATCH_ID)
        assertEquals(2, lineups.size)
        assertTrue(lineups.all { it.players.isNotEmpty() })
    }

    /** Fourteen groups over four tiers; only the tier tells one table from another's neighbour. */
    @Test
    fun nationsLeagueStandingsCarryTheirTier() = runTest {
        val table = unl.standings()
        assertEquals("2027", table.seasonId)
        assertEquals("group", table.grouping)
        assertEquals(14, table.groups.size)
        assertEquals("League A · Group A1", table.groups.first().label)
        assertEquals("League D · Group D2", table.groups.last().label)
        assertEquals(54, table.rows.size)
        assertTrue(table.rows.all { it.team.clubId == null })
        assertEquals("France", table.groups.first().rows.first().team.name)
    }

    @Test
    fun nationsLeagueTeamAndSchedule() = runTest {
        val malta = unl.team(UefaSamples.UNL_TEAM_ID)
        assertEquals("Malta", malta.name)
        assertEquals("MLT", malta.ref.abbreviation)
        assertEquals("MLT", malta.country)
        assertNull(malta.ref.clubId)
        val season = unl.teamSchedule(UefaSamples.UNL_TEAM_ID, LocalDate(2026, 7, 1), LocalDate(2027, 6, 30))
        assertEquals(4, season.size, "a three-team group plays four matches")
        assertTrue(season.all { it.home.id == UefaSamples.UNL_TEAM_ID || it.away.id == UefaSamples.UNL_TEAM_ID })
        assertEquals(season.sortedBy { it.startTime }, season)
    }
}
