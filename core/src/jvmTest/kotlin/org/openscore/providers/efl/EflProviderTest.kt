package org.openscore.providers.efl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.StageKind
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.providers.football.FootballPeriods
import org.openscore.testing.EflSamples
import org.openscore.testing.SampleFetcher
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
import kotlin.time.Instant

class EflProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse(EflSamples.NOW)
    }
    private val fetcher = EflSamples.register(SampleFetcher())
    private val championship = ChampionshipProvider(fetcher, clock = fixedClock)
    private val cup = CarabaoCupProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "matches-date" to EflList.serializer(EflMatchRow.serializer()),
            "matches-season" to EflList.serializer(EflMatchRow.serializer()),
            "matches-team" to EflList.serializer(EflMatchRow.serializer()),
            "match." to EflDocument.serializer(EflMatch.serializer()),
            "stats-match" to EflList.serializer(EflMatchStats.serializer()),
            "league-tables.json" to EflList.serializer(EflTableRow.serializer()),
            "teams" to EflList.serializer(EflTeamEntry.serializer()),
        )
        val errors = setOf("matches-date.empty.json", "match.404.json")
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "efl").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) && file.name !in errors }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 14, "typed=$typed")
    }

    @Test
    fun capabilities() {
        assertTrue(championship.supports(Capability.STANDINGS))
        assertFalse(cup.supports(Capability.STANDINGS), "the cup has no table")
        assertFalse(championship.supports(Capability.ROSTER), "no squad route on this API")
        assertEquals("2026", championship.currentSeason())
        assertEquals("championship", championship.league.id)
        assertEquals("carabao-cup", cup.league.id)
    }

    @Test
    fun cupDay() = runTest {
        val games = cup.gamesOn(LocalDate.parse(EflSamples.CUP_DAY))
        assertEquals(1, games.size)
        val g = games.single()
        assertEquals(EflSamples.FINAL_MATCH_ID, g.id)
        assertEquals("2026-09-17T18:30:00Z", g.startTime.toString(), "UTC without a marker")
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(5, 0), g.score)
        assertEquals("t43", g.home.id, "team id from the crest file name")
        assertEquals("t45", g.away.id)
        assertEquals("Manchester City", g.home.name)
        assertEquals("manchester-city", g.home.clubId, "the crosswalk knows the club by its efl id")
        assertEquals("norwich-city", g.away.clubId)
        assertNull(g.home.abbreviation, "no initials anywhere on this API")
        assertEquals("https://crests.gc.eflservices.co.uk/t43.png", g.home.logoUrl)
        assertEquals(listOf(2 to 0, 3 to 0), g.periodScores.map { it.home to it.away })
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(StageKind.OTHER, g.stage, "cup ties count towards form")
        assertEquals("2026", g.seasonId)
        assertNull(g.events, "the list has no events")
    }

    @Test
    fun championshipDayAndEmptyDay() = runTest {
        val games = championship.gamesOn(LocalDate.parse(EflSamples.CHAMPIONSHIP_DAY))
        assertEquals(9, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null && it.clock == null })
        assertEquals(games.sortedBy { it.startTime }, games)
        val first = games.first()
        assertEquals("2026-09-19T11:30:00Z", first.startTime.toString())
        assertEquals("Cardiff City", first.home.name)
        assertEquals("cardiff-city", first.home.clubId)
        assertEquals(StageKind.REGULAR, first.stage)
        assertFalse(first.startTimeTbd)

        assertEquals(emptyList(), championship.gamesOn(LocalDate.parse(EflSamples.EMPTY_DAY)), "404 \"No matches found\" is an empty day")
        assertEquals(emptyList(), cup.gamesOn(LocalDate.parse(EflSamples.EMPTY_DAY)))
    }

    @Test
    fun finalMatch() = runTest {
        val g = cup.game(EflSamples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(5, 0), g.score)
        assertEquals("Etihad Stadium, Manchester", g.venue)
        assertEquals("Round 4", g.competition)
        assertEquals(StageKind.OTHER, g.stage)
        assertNull(g.clock)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(listOf(2 to 0, 3 to 0), g.periodScores.map { it.home to it.away })
        assertEquals("65.9", g.stats["possession"]!!.home)
        assertEquals("14", g.stats["shots"]!!.home)
        assertEquals("0", g.stats["shotsOnTarget"]!!.away, "`null` on target means none")
        assertEquals(setOf("possession", "shots", "shotsOnTarget", "corners", "fouls"), g.stats.keys)
        assertEquals(2, fetcher.requests.size, "one document plus the stats rows: ${fetcher.requests}")

        val events = assertNotNull(g.events)
        val goals = events.filter { it.type.isGoal }
        assertEquals(5, goals.size)
        assertEquals(listOf(Score(1, 0), Score(2, 0), Score(3, 0), Score(4, 0), Score(5, 0)), goals.map { it.score })
        assertEquals("29'", goals.first().time.label, "the conventional minute for 28:17")
        assertEquals(FootballPeriods.FIRST_HALF, goals.first().period)
        val scorer = assertIs<FootballGoalDetails>(goals.first().details).scorer
        assertEquals("Floyd Samba", scorer?.name, "resolved through the lineups, not the event's stale player object")
        assertEquals("p595480", scorer?.id)
        assertEquals(g.home, goals.first().team)
        val subs = events.filter { it.type == FootballEventType.SUBSTITUTION }
        assertEquals(7, subs.size, "both sides' substitutions")
        assertTrue(subs.all { assertIs<SubstitutionDetails>(it.details).playerOff != null && it.details.playerOn != null })
        assertEquals(FootballPeriods.SECOND_HALF, subs.first().period, "substitutions carry the numeric period")
        assertEquals(events.sortedBy { it.sortOrder }, events, "in time order across both sides")
    }

    @Test
    fun lineups() = runTest {
        val lineups = cup.lineups(EflSamples.FINAL_MATCH_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("t43", home.team.id)
        assertEquals("4231", home.formation)
        val starters = home.groups.first { it.kind == LineupGroupKind.STARTERS }.players
        assertEquals(11, starters.size)
        assertEquals("Gerónimo Rulli", starters.first().name)
        assertEquals("GK", starters.first().position)
        assertEquals(28, starters.first().jerseyNumber)
        val bench = home.groups.first { it.kind == LineupGroupKind.BENCH }.players
        assertEquals(9, bench.size)
        assertEquals("GK", bench.first().position, "the bench's real position, not `Substitute`")
        assertNull(home.headCoach, "no manager on this API")
        assertEquals(1, fetcher.requests.size, "lineups come from the one document")

        assertTrue(cup.lineups(EflSamples.PRE_MATCH_ID).isEmpty())
    }

    @Test
    fun preMatch() = runTest {
        val g = championship.game(EflSamples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock); assertNull(g.ending)
        assertEquals(emptyList(), g.events, "loaded, none yet")
        assertTrue(g.stats.isEmpty())
        assertTrue(g.periodScores.isEmpty())
        assertNull(g.competition, "a league round is not worth a subtitle")
        assertEquals(StageKind.REGULAR, g.stage)
        assertEquals("Ashton Gate Stadium, Bristol", g.venue)
        assertEquals("bristol-city", g.home.clubId)
        assertEquals("watford", g.away.clubId)
        assertEquals(1, fetcher.requests.size, "no stats read before kick-off")
    }

    @Test
    fun shootout() = runTest {
        val g = cup.game(EflSamples.SHOOTOUT_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(3, 3), g.score)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(listOf("1H" to (2 to 2), "2H" to (1 to 1), "PENS" to (7 to 6)), g.periodScores.map { it.period.label to (it.home to it.away) })
        assertEquals("t73", g.home.id, "`matchTeams` is not in home/away order; the sides come from `homeTeamID`/`awayTeamID`")
        val events = assertNotNull(g.events)
        val kicks = events.filter { it.type == FootballEventType.SHOOTOUT_ATTEMPT }
        assertEquals(14, kicks.size)
        assertTrue(kicks.all { it.period == FootballPeriods.PENALTIES })
        assertEquals(listOf("t37", "t73", "t37", "t73"), kicks.take(4).map { it.team?.id }, "the two sides alternate in event order, the away side first")
        val saved = kicks.single { !assertIs<ShootoutAttemptDetails>(it.details).scored }
        assertEquals("saved", assertIs<ShootoutAttemptDetails>(saved.details).shotType)
        assertEquals(6, events.count { it.type == FootballEventType.YELLOW_CARD })
        val card = assertIs<CardDetails>(events.first { it.type == FootballEventType.YELLOW_CARD }.details)
        assertEquals(CardKind.YELLOW, card.card)
        assertEquals("Foul", card.reason)
    }

    @Test
    fun extraTimeAndPlayOffs() = runTest {
        val g = championship.game(EflSamples.EXTRA_TIME_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(2, 1), g.score)
        assertEquals(GameEnding.OVERTIME, g.ending)
        assertEquals(StageKind.PLAYOFF, g.stage)
        assertEquals("Play-off semi-final, 2nd leg", g.competition)
        assertEquals(listOf("1H" to (1 to 1), "2H" to (0 to 0), "ET1" to (0 to 0), "ET2" to (1 to 0)), g.periodScores.map { it.period.label to (it.home to it.away) })
        val events = assertNotNull(g.events)
        val winner = events.filter { it.type.isGoal }.last()
        assertEquals("116'", winner.time.label)
        assertEquals(FootballPeriods.EXTRA_SECOND, winner.period)
        assertEquals("45'+1", events.filter { it.type.isGoal }[1].time.label, "stoppage time in the first half")
        assertTrue(events.filter { it.type == FootballEventType.SUBSTITUTION }.any { it.period == FootballPeriods.EXTRA_SECOND }, "period `4` on a substitution")

        val final = championship.game(EflSamples.PLAYOFF_FINAL_MATCH_ID)
        assertEquals("Play-off final", final.competition)
        assertEquals(StageKind.PLAYOFF, final.stage)
        assertEquals("Hull City", final.home.name)
        assertEquals("Middlesbrough", final.away.name)
        assertEquals(Score(1, 0), final.score, "the two placeholder sides in `matchTeams` are ignored")
        assertEquals("Wembley Stadium, London", final.venue)
        assertEquals(2, championship.lineups(EflSamples.PLAYOFF_FINAL_MATCH_ID).size)
    }

    @Test
    fun standingsAndTeams() = runTest {
        val table = championship.standings()
        assertEquals("2026", table.seasonId)
        assertEquals(24, table.rows.size)
        val leader = table.rows.first()
        assertEquals("West Ham United", leader.team.name)
        assertEquals("t21", leader.team.id)
        assertEquals("west-ham-united", leader.team.clubId)
        assertEquals(14, leader.points)
        assertEquals(11, leader.goalDifference)
        assertEquals("DWWWW", leader.extra["form"], "oldest first")
        assertEquals("1", leader.extra["startingPosition"])
        assertTrue(table.rows.all { it.team.clubId != null }, "every Championship club is in the crosswalk: ${table.rows.filter { it.team.clubId == null }.map { it.team.name }}")
        assertFailsWith<UnsupportedCapabilityException> { cup.standings() }

        val t = championship.team("t41")
        assertEquals("Birmingham City", t.name)
        assertEquals("Birmingham", t.commonName)
        assertEquals("St Andrew's Stadium", t.arena)
        assertEquals("ENG", t.country)
        assertEquals("birmingham-city", t.ref.clubId)
        assertFailsWith<NotFoundException> { championship.team("t43") }
        assertEquals("Manchester City", cup.team("t43").name, "a Premier League club is known to the cup's team list")
    }

    @Test
    fun teamSchedule() = runTest {
        val season = LocalDate(2026, 7, 1)..LocalDate(2027, 6, 30)
        val league = championship.teamSchedule(EflSamples.TEAM_ID, season.start, season.endInclusive)
        val cupTies = cup.teamSchedule(EflSamples.TEAM_ID, season.start, season.endInclusive)
        assertEquals(46, league.size, "the club's league fixtures only")
        assertEquals(1, cupTies.size)
        assertTrue(league.all { it.leagueId == "championship" } && cupTies.all { it.leagueId == "carabao-cup" })
        assertTrue(league.all { it.home.clubId == "wrexham" || it.away.clubId == "wrexham" })
        assertEquals(7, league.count { it.state == GameState.FINAL })
        assertEquals("2026-08-07T19:00:00Z", cupTies.single().startTime.toString(), "the round-one tie")
        assertEquals(league.sortedBy { it.startTime }, league)
        assertEquals(1, fetcher.requests.distinct().size, "both competitions read the same list")
        assertEquals(1, championship.teamSchedule(EflSamples.TEAM_ID, LocalDate(2026, 9, 19), LocalDate(2026, 9, 19)).size)
    }

    @Test
    fun notFound() = runTest {
        assertFailsWith<NotFoundException> { championship.game("g1") }
    }

    private fun liveDoc(name: String): EflRow<EflMatch> =
        OpenScoreJson.decodeFromString(
            EflDocument.serializer(EflMatch.serializer()),
            SampleFetcher.samplesDir("football", "efl").resolve(name).readText(),
        ).data

    /**
     * Bristol City 1-0 Watford (g2647335), 2026-09-18, 601 polls. The detail document carries
     * **two** `period` fields and only one of them moves: `attributes.period` read `PreMatch` in 69
     * of the 70 bodies, flipping straight to `FullTime` at the end, while
     * `attributes.matchDetails.period` walked the whole arc. Reading the outer one would show every
     * EFL match in play as not yet kicked off.
     */
    @Test
    fun theLivePeriodIsTheOneInsideMatchDetails() {
        val mapper = EflMapper("championship", EflMapper.CHAMPIONSHIP)
        for ((file, expected) in listOf(
            "match.live.json" to GameState.LIVE,
            "match.halftime.json" to GameState.INTERMISSION,
            "match.live-second-half.json" to GameState.LIVE,
        )) {
            val doc = liveDoc(file)
            assertEquals("PreMatch", doc.attributes.period, "$file: the outer period never moves")
            assertEquals(expected, mapper.game(doc).state, "$file: the state comes from matchDetails")
        }
    }

    @Test
    fun aLiveFirstHalfAndTheBreakThatFollows() {
        val mapper = EflMapper("championship", EflMapper.CHAMPIONSHIP)
        val live = mapper.game(liveDoc("match.live.json"))
        assertEquals(GameState.LIVE, live.state)
        assertEquals("FirstHalf/31'", live.rawState)
        assertEquals(Score(1, 0), live.score)
        assertEquals("31'", assertNotNull(live.clock).time.label)
        assertEquals(true, live.clock?.running)
        assertEquals(listOf(1 to 0), live.periodScores.map { it.home to it.away })

        val half = mapper.game(liveDoc("match.halftime.json"))
        assertEquals(GameState.INTERMISSION, half.state)
        assertEquals("HalfTime/48'", half.rawState)
        assertEquals("45'+3", assertNotNull(half.clock).time.label, "the feed's own label is \"45' +3'\"")
        assertEquals(false, half.clock?.running)
        // `halfScore` is still null here, so the first half has to come from the goal rows.
        assertNull(liveDoc("match.halftime.json").attributes.matchTeams.first().halfScore)
        assertEquals(listOf(1 to 0), half.periodScores.map { it.home to it.away })
    }

    /** `matchTime` runs to 48 in the break and restarts at 45, so it is not monotonic. */
    @Test
    fun theSecondHalfRestartsTheMinuteAt45() {
        val mapper = EflMapper("championship", EflMapper.CHAMPIONSHIP)
        assertEquals(48, liveDoc("match.halftime.json").attributes.matchDetails?.matchTime)
        val g = mapper.game(liveDoc("match.live-second-half.json"))
        assertEquals("SecondHalf/93'", g.rawState)
        assertEquals("90'+3", assertNotNull(g.clock).time.label)
        assertEquals(listOf(1 to 0, 0 to 0), g.periodScores.map { it.home to it.away })
        val events = assertNotNull(g.events)
        assertEquals(1, events.count { it.type.isGoal })
        assertEquals(7, events.count { it.type == FootballEventType.YELLOW_CARD })
        assertEquals(9, events.count { it.type == FootballEventType.SUBSTITUTION })
    }

    /**
     * `matchDetails` and the line-ups both appear about 50 minutes before kick-off, while `period`
     * is still `PreMatch` - so a null `matchDetails` means "not close to kick-off", not "not
     * started", and a published line-up is not a sign that play has begun.
     */
    @Test
    fun matchDetailsAndLineupsAppearBeforeKickOff() {
        val doc = liveDoc("match.pre-matchday.json")
        val details = assertNotNull(doc.attributes.matchDetails, "published 50 min before kick-off")
        assertEquals("PreMatch", details.period)
        assertEquals(0, details.matchTime)
        assertEquals(11, doc.attributes.matchTeams.first().players?.start?.size)
        val g = EflMapper("championship", EflMapper.CHAMPIONSHIP).game(doc)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.clock)
    }

    @Test
    fun states() {
        val mapper = EflMapper("championship", EflMapper.CHAMPIONSHIP)
        assertEquals(GameState.SCHEDULED, mapper.gameState("PreMatch", null, null))
        assertEquals(GameState.SCHEDULED, mapper.gameState(null, null, null))
        assertEquals(GameState.LIVE, mapper.gameState("FirstHalf", null, 12))
        assertEquals(GameState.INTERMISSION, mapper.gameState("HalfTime", null, 45))
        assertEquals(GameState.LIVE, mapper.gameState("ShootOut", null, 95))
        assertEquals(GameState.INTERMISSION, mapper.gameState("FullTimePens", null, 95))
        assertEquals(GameState.FINAL, mapper.gameState("FullTime", "NormalResult", 93))
        assertEquals(GameState.POSTPONED, mapper.gameState("PreMatch", "Postponed", null))
        assertEquals(GameState.POSTPONED, mapper.gameState("Postponed", null, null))
        assertEquals(GameState.LIVE, mapper.gameState("SomethingNew", null, 30), "an unforeseen period with a running minute is still a live match")
        assertEquals(GameState.UNKNOWN, mapper.gameState("SomethingNew", null, null))
        assertEquals(FootballPeriods.SECOND_HALF, mapper.currentPeriod("FullTimePens"), "the cup goes straight to penalties")
        assertEquals(FootballPeriods.EXTRA_SECOND, mapper.currentPeriod("FullTimePens", extraTime = true))
        assertEquals("t43", mapper.teamIdFromCrest("https://crests.gc.eflservices.co.uk/t43.png"))
        assertNull(mapper.teamIdFromCrest(null))
    }
}
