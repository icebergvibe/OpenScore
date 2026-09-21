package org.openscore.app.alerts

import org.openscore.app.Fixtures
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.PeriodScore
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.combat.FightMethod
import org.openscore.model.combat.FightOutcome
import org.openscore.model.combat.FightResult
import org.openscore.model.combat.FightSituation
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.Strength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AlertRulesTest {

    private val kickoff = Instant.parse("2026-09-13T14:00:00Z")
    private val kickoffMs = kickoff.toEpochMilliseconds()
    private val hourBefore = kickoffMs - 60 * 60_000L
    private val all = AlertSettings(kickoff = true, results = true, started = true, goals = true, cards = true, breaks = true, leadMinutes = 15, keys = setOf("x"))

    private fun scheduled() = Fixtures.game(state = GameState.SCHEDULED, startTime = kickoff)

    private fun live(home: Int, away: Int, minute: Int = 30, period: Int = 1, state: GameState = GameState.LIVE) = Fixtures.game(
        state = state,
        score = Score(home, away),
        clock = Fixtures.clock(Fixtures.period(period, if (period == 1) "1H" else "2H"), elapsed = (minute - (if (period == 2) 45 else 0)).minutes),
        startTime = kickoff,
    )

    private fun row(kind: AlertKind, seeded: Boolean = false, seenScore: String? = null, seen: Set<String> = emptySet()) =
        PendingAlert("premier-league", "1", "2026-09-13", kind, kickoffMs, kickoffMs, "home-1", "away-1", "Premier League", seeded, seenScore, seen)

    private fun goal(id: String, scorer: String, minute: Int, order: Int, type: FootballEventType = FootballEventType.GOAL, kind: GoalKind = GoalKind.OPEN_PLAY) = GameEvent(
        id = id, type = type, rawType = "G", time = GameTime(Fixtures.period(1, "1H"), elapsed = minute.minutes),
        team = Fixtures.team("premier-league", "home-1"), details = FootballGoalDetails(PlayerRef("premier-league", "p$id", scorer), kind = kind), sortOrder = order,
    )

    private fun card(id: String, player: String, kind: CardKind = CardKind.YELLOW, team: String? = null) = GameEvent(
        id = id, type = if (kind == CardKind.RED) FootballEventType.RED_CARD else FootballEventType.YELLOW_CARD, rawType = "C",
        time = GameTime(Fixtures.period(1, "1H"), elapsed = 20.minutes, label = "20'"), team = team?.let { Fixtures.team("premier-league", it) },
        details = CardDetails(PlayerRef("premier-league", "p$id", player), kind),
    )

    @Test
    fun `a fight queues its start and its result, and nothing round by round`() {
        val van = Fixtures.team("ufc", "3989").copy(name = "Joshua Van")
        val pantoja = Fixtures.team("ufc", "2858").copy(name = "Alexandre Pantoja")
        val bout = FightSituation(scheduledRounds = 5, cardSegment = "Main", cardPosition = 1)
        val fight = Fixtures.game(leagueId = "ufc", id = "1335-13017", competition = "UFC 331: Van vs. Pantoja 2", home = van, away = pantoja, startTime = kickoff).copy(situation = bout)
        val rows = AlertRules.alertsFor(fight, Sport.MMA, "UFC", "2026-09-19", all, hourBefore)
        assertEquals(setOf(AlertKind.KICKOFF, AlertKind.STARTED, AlertKind.RESULT), rows.map { it.kind }.toSet())
        assertEquals("UFC 331: Van vs. Pantoja 2 · Main card", rows.first().competition)
        assertEquals(kickoffMs + 240 * 60_000L, rows.first { it.kind == AlertKind.RESULT }.dueAt)

        val won = fight.copy(state = GameState.FINAL, situation = bout.copy(result = FightResult(winner = van, method = FightMethod.SUBMISSION, methodLabel = "Submission", round = 2, time = "3:14")))
        assertEquals("Joshua Van wins by submission in round 2", AlertRules.finalHeadline(won, Sport.MMA))
        val decision = won.copy(situation = bout.copy(result = FightResult(winner = pantoja, method = FightMethod.DECISION, methodLabel = "Decision - Split", round = 5, time = "5:00")))
        assertEquals("Alexandre Pantoja wins by split decision", AlertRules.finalHeadline(decision, Sport.MMA))
        val draw = won.copy(situation = bout.copy(result = FightResult(winner = null, method = FightMethod.DECISION, methodLabel = "Decision - Majority", round = 3, time = "5:00", homeOutcome = FightOutcome.DRAW, awayOutcome = FightOutcome.DRAW)))
        assertEquals("Draw", AlertRules.finalHeadline(draw, Sport.MMA))
        val result = AlertRules.pollResult(rows.first { it.kind == AlertKind.RESULT }, Sport.MMA, won, kickoffMs + 60 * 60_000L)
        assertEquals("Joshua Van wins by submission in round 2 · UFC 331: Van vs. Pantoja 2 · Main card", result.posts.single().text)
    }

    @Test
    fun `a followed game an hour out queues every kind that is on, each at its own time`() {
        val rows = AlertRules.alertsFor(scheduled(), Sport.FOOTBALL, "Premier League", "2026-09-13", all, hourBefore)
        assertEquals(setOf(AlertKind.KICKOFF, AlertKind.STARTED, AlertKind.RESULT, AlertKind.LIVE, AlertKind.BREAK), rows.map { it.kind }.toSet())
        assertEquals(kickoffMs - 15 * 60_000L, rows.first { it.kind == AlertKind.KICKOFF }.dueAt)
        assertEquals(kickoffMs, rows.first { it.kind == AlertKind.STARTED }.dueAt)
        assertEquals(kickoffMs, rows.first { it.kind == AlertKind.LIVE }.dueAt)
        assertEquals(kickoffMs + 115 * 60_000L, rows.first { it.kind == AlertKind.RESULT }.dueAt)
        assertEquals("premier-league/1|KICKOFF", rows.first().id)
    }

    @Test
    fun `an overdue pre-game reminder is dropped once kickoff has passed`() {
        val reminder = row(AlertKind.KICKOFF).copy(dueAt = kickoffMs - 10 * 60_000L)
        assertNotNull(AlertRules.kickoffPost(reminder, kickoffMs - 1))
        assertNull(AlertRules.kickoffPost(reminder, kickoffMs))
        assertNull(AlertRules.kickoffPost(reminder, kickoffMs + 30 * 60_000L))
    }

    @Test
    fun `a game already under way gets no reminder or start watch, and its live watches are due at once`() {
        val now = kickoffMs + 30 * 60_000L
        val rows = AlertRules.alertsFor(live(1, 0), Sport.FOOTBALL, "Premier League", "2026-09-13", all, now)
        assertEquals(setOf(AlertKind.RESULT, AlertKind.LIVE, AlertKind.BREAK), rows.map { it.kind }.toSet())
        assertEquals(now, rows.first { it.kind == AlertKind.LIVE }.dueAt)
    }

    @Test
    fun `a finished game queues nothing, and kinds that are off queue nothing`() {
        assertTrue(AlertRules.alertsFor(Fixtures.game(state = GameState.FINAL, startTime = kickoff), Sport.FOOTBALL, "PL", "2026-09-13", all, hourBefore).isEmpty())
        val only = AlertSettings(kickoff = false, results = true, keys = setOf("x"))
        assertEquals(listOf(AlertKind.RESULT), AlertRules.alertsFor(scheduled(), Sport.FOOTBALL, "PL", "2026-09-13", only, hourBefore).map { it.kind })
    }

    @Test
    fun `the start watch waits for the feed and then says so once`() {
        val waiting = AlertRules.pollStart(row(AlertKind.STARTED), Sport.FOOTBALL, scheduled(), kickoffMs)
        assertNotNull(waiting.next)
        assertTrue(waiting.posts.isEmpty())
        assertEquals(kickoffMs + AlertRules.LIVE_POLL_MINUTES * 60_000L, waiting.next.dueAt)

        val started = AlertRules.pollStart(row(AlertKind.STARTED), Sport.FOOTBALL, live(0, 0, minute = 1), kickoffMs + 60_000L)
        assertNull(started.next)
        assertEquals("Under way · Premier League", started.posts.single().text)
        assertEquals("home-1 v away-1", started.posts.single().title)
    }

    @Test
    fun `a live watch seeds on its first look and only announces what moves afterwards`() {
        val now = kickoffMs + 30 * 60_000L
        val seeded = AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, live(1, 0), listOf(card("c1", "Rice")), all, canEvents = true, now = now)
        assertTrue(seeded.posts.isEmpty(), "the first look is silent")
        val next = seeded.next!!
        assertTrue(next.seeded)
        assertEquals("1-0", next.seenScore)
        assertEquals(setOf("c1"), next.seen)

        // Nothing moved: nothing said.
        val quiet = AlertRules.pollLive(next, Sport.FOOTBALL, live(1, 0), listOf(card("c1", "Rice")), all, canEvents = true, now = now + 180_000L)
        assertTrue(quiet.posts.isEmpty())

        // A goal and a card since: one expanded update, so a delayed poll buzzes once while the
        // row still records both.
        val events = listOf(card("c1", "Rice"), goal("g2", "Saka", 41, 2), card("c2", "Palmer", CardKind.RED))
        val busy = AlertRules.pollLive(next, Sport.FOOTBALL, live(2, 0, minute = 42), events, all, canEvents = true, now = now + 360_000L)
        val update = busy.posts.single()
        assertEquals("home-1 2 - 0 away-1", update.title)
        assertEquals("⚽ Goal for home-1 · Saka 41'\n🟥 Red card · Palmer 20'\nPremier League", update.text)
        assertEquals("2-0", busy.next!!.seenScore)
        assertEquals(setOf("c1", "c2"), busy.next.seen)
    }

    @Test
    fun `a goal with no events to hand names the side that scored`() {
        val seeded = row(AlertKind.LIVE, seeded = true, seenScore = "0-0")
        val goalsOnly = all.copy(cards = false)
        val out = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(0, 1), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for away-1 · Premier League", out.posts.single().text)
        // Two found in one delayed poll are one expanded update, not two simultaneous buzzes.
        val two = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(2, 0), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for home-1\n⚽ Goal for home-1\nPremier League", two.posts.single().text)
        // One each: the expanded update still attributes one line to each side.
        val each = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(1, 1), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for home-1\n⚽ Goal for away-1\nPremier League", each.posts.single().text)
    }

    @Test
    fun `an own goal or a penalty is marked, since the scorer's name alone reads as the wrong side`() {
        val seeded = row(AlertKind.LIVE, seeded = true, seenScore = "0-0")
        val goalsOnly = all.copy(cards = false)
        val og = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(1, 0, minute = 41), listOf(goal("g1", "Gabriel", 41, 1, type = FootballEventType.OWN_GOAL, kind = GoalKind.OWN_GOAL)), goalsOnly, canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for home-1 · Gabriel (og) 41' · Premier League", og.posts.single().text)
        val pen = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(1, 0, minute = 41), listOf(goal("g1", "Saka", 41, 1, type = FootballEventType.PENALTY_GOAL, kind = GoalKind.PENALTY)), goalsOnly, canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for home-1 · Saka (pen) 41' · Premier League", pen.posts.single().text)
        // The mark follows the event type even when the details did not say.
        val typed = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(1, 0, minute = 41), listOf(goal("g1", "Gabriel", 41, 1, type = FootballEventType.OWN_GOAL)), goalsOnly, canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("⚽ Goal for home-1 · Gabriel (og) 41' · Premier League", typed.posts.single().text)
    }

    @Test
    fun `a hockey goal carries its strength`() {
        val nhl = PendingAlert("nhl", "1", "2026-09-13", AlertKind.LIVE, kickoffMs, kickoffMs, "home-1", "away-1", "NHL", seeded = true, seenScore = "0-0")
        val game = Fixtures.game(leagueId = "nhl", state = GameState.LIVE, score = Score(1, 0), startTime = kickoff, clock = Fixtures.clock(Fixtures.period(2, "2"), remaining = 7.minutes))
        val goal = GameEvent(
            id = "g1", type = HockeyEventType.GOAL, rawType = "goal", time = GameTime(Fixtures.period(2, "2"), elapsed = 12.minutes + 34.seconds),
            team = Fixtures.team("nhl", "home-1"), details = GoalDetails(PlayerRef("nhl", "p1", "Matthews"), strength = Strength.PP), sortOrder = 1,
        )
        val out = AlertRules.pollLive(nhl, Sport.HOCKEY, game, listOf(goal), all.copy(cards = false), canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("🏒 Goal for home-1 · Matthews (PP) 12:34 · NHL", out.posts.single().text)
    }

    @Test
    fun `a hockey penalty is announced only when it is a major or worse`() {
        val nhl = PendingAlert("nhl", "1", "2026-09-13", AlertKind.LIVE, kickoffMs, kickoffMs, "home-1", "away-1", "NHL", seeded = true, seenScore = "0-0")
        val game = Fixtures.game(leagueId = "nhl", state = GameState.LIVE, score = Score(0, 0), startTime = kickoff, clock = Fixtures.clock(Fixtures.period(2, "2"), remaining = 7.minutes))
        fun penalty(id: String, who: String, minutes: Int?) = GameEvent(
            id = id, type = HockeyEventType.PENALTY, rawType = "penalty", time = GameTime(Fixtures.period(2, "2"), elapsed = 12.minutes + 34.seconds),
            team = Fixtures.team("nhl", "home-1"), details = PenaltyDetails(PlayerRef("nhl", "p$id", who), minutes = minutes), sortOrder = id.toInt(),
        )
        val events = listOf(penalty("1", "Marner", 2), penalty("2", "Reaves", 5), penalty("3", "Rielly", 10), penalty("4", "Tavares", null))
        val out = AlertRules.pollLive(nhl, Sport.HOCKEY, game, events, all, canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("⏱ 5 min penalty to home-1 · Reaves 12:34\n⏱ 10 min penalty to home-1 · Rielly 12:34\nNHL", out.posts.single().text)
        // The minors are not remembered either: nothing to compare them against later.
        assertEquals(setOf("2", "3"), out.next!!.seen)
    }

    @Test
    fun `baseball counts runs per side rather than posting a goal per run`() {
        val mlb = PendingAlert("mlb", "1", "2026-09-13", AlertKind.LIVE, kickoffMs, kickoffMs, "home-1", "away-1", "MLB", seeded = true, seenScore = "0-0")
        fun at(home: Int, away: Int) = Fixtures.game(leagueId = "mlb", state = GameState.LIVE, score = Score(home, away), startTime = kickoff)
        val goalsOnly = all.copy(cards = false)
        val one = AlertRules.pollLive(mlb, Sport.BASEBALL, at(0, 1), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚾ Run for away-1 · MLB", one.posts.single().text)
        val slam = AlertRules.pollLive(mlb, Sport.BASEBALL, at(4, 0), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚾ 4 runs for home-1 · MLB", slam.posts.single().text)
        val both = AlertRules.pollLive(mlb, Sport.BASEBALL, at(2, 1), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("⚾ 2 runs for home-1\n⚾ Run for away-1\nMLB", both.posts.single().text)
        val down = AlertRules.pollLive(mlb.copy(seenScore = "2-0"), Sport.BASEBALL, at(1, 0), null, goalsOnly, canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("🚫 Score corrected · MLB", down.posts.single().text)
    }

    @Test
    fun `a card names the team when the event says whose it was`() {
        val seeded = row(AlertKind.LIVE, seeded = true, seenScore = "1-0")
        val out = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(1, 0), listOf(card("c1", "Rice", team = "home-1"), card("c2", "Palmer", CardKind.RED, team = "away-1")), all, canEvents = true, now = kickoffMs + 600_000L)
        assertEquals("🟨 Yellow card for home-1 · Rice 20'\n🟥 Red card for away-1 · Palmer 20'\nPremier League", out.posts.single().text)
    }

    @Test
    fun `a score that goes down is an overturned goal, not a goal`() {
        val seeded = row(AlertKind.LIVE, seeded = true, seenScore = "1-0")
        val out = AlertRules.pollLive(seeded, Sport.FOOTBALL, live(0, 0), null, all.copy(cards = false), canEvents = false, now = kickoffMs + 600_000L)
        assertEquals("🚫 Goal overturned · Premier League", out.posts.single().text)
    }

    @Test
    fun `a card watch whose events could not be read stays unseeded rather than swallowing the first half`() {
        val out = AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, live(1, 0), null, all, canEvents = true, now = kickoffMs + 600_000L)
        assertFalse(out.next!!.seeded)
        // But a league with no events at all seeds on the score alone.
        val seeded = AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, live(1, 0), null, all, canEvents = false, now = kickoffMs + 600_000L)
        assertTrue(seeded.next!!.seeded)
    }

    @Test
    fun `events are asked for every poll with cards on, and once per goal with only goals on`() {
        val seeded = row(AlertKind.LIVE, seeded = true, seenScore = "0-0")
        assertTrue(AlertRules.needsEvents(seeded, live(0, 0), all, canEvents = true))
        val goalsOnly = all.copy(cards = false)
        assertFalse(AlertRules.needsEvents(seeded, live(0, 0), goalsOnly, canEvents = true))
        assertTrue(AlertRules.needsEvents(seeded, live(1, 0), goalsOnly, canEvents = true))
        assertFalse(AlertRules.needsEvents(seeded, live(1, 0), goalsOnly, canEvents = false))
        assertFalse(AlertRules.needsEvents(row(AlertKind.LIVE), live(1, 0), goalsOnly, canEvents = true), "an unseeded row has nothing to compare against")
    }

    @Test
    fun `the live watch ends when the game does, announcing a goal that landed with the whistle`() {
        val out = AlertRules.pollLive(row(AlertKind.LIVE, seeded = true, seenScore = "1-0"), Sport.FOOTBALL, live(1, 0, state = GameState.FINAL), null, all, canEvents = false, now = kickoffMs)
        assertNull(out.next)
        assertTrue(out.posts.isEmpty())
        val late = AlertRules.pollLive(row(AlertKind.LIVE, seeded = true, seenScore = "1-0"), Sport.FOOTBALL, live(2, 0, state = GameState.FINAL), null, all, canEvents = false, now = kickoffMs)
        assertNull(late.next)
        assertEquals("⚽ Goal for home-1 · Premier League", late.posts.single().text)
        assertNull(AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, null, null, all, canEvents = false, now = kickoffMs + 116 * 60_000L).next, "gone from the listing after the usual length")
        assertNotNull(AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, null, null, all, canEvents = false, now = kickoffMs + 10 * 60_000L).next, "a late kick-off is not yet listed")
    }

    @Test
    fun `half time is announced once and a break already in progress at the first look is not`() {
        val ht = live(1, 1, minute = 45, state = GameState.INTERMISSION)
        val now = kickoffMs + 46 * 60_000L
        val seeded = AlertRules.pollBreak(row(AlertKind.BREAK), Sport.FOOTBALL, ht, now)
        assertTrue(seeded.posts.isEmpty())
        assertTrue(seeded.next!!.seeded)

        val fresh = AlertRules.pollBreak(row(AlertKind.BREAK, seeded = true), Sport.FOOTBALL, ht, now)
        val post = fresh.posts.single()
        assertEquals("home-1 1 - 1 away-1", post.title)
        assertEquals("Half time · Premier League", post.text)
        assertTrue(fresh.next!!.seen.isNotEmpty())

        val again = AlertRules.pollBreak(fresh.next, Sport.FOOTBALL, ht, now + 180_000L)
        assertTrue(again.posts.isEmpty(), "the same break is not announced twice")
    }

    @Test
    fun `football tells its breaks apart by the half the clock says has just finished`() {
        fun paused(period: Int, label: String) = Fixtures.game(state = GameState.INTERMISSION, score = Score(1, 1), startTime = kickoff, clock = Fixtures.clock(Fixtures.period(period, label)))
        assertEquals("Half time", AlertRules.breakHeadline(paused(1, "1H"), Sport.FOOTBALL))
        assertEquals("End of 90 minutes", AlertRules.breakHeadline(paused(2, "2H"), Sport.FOOTBALL))
        assertEquals("Half time in extra time", AlertRules.breakHeadline(paused(3, "ET1"), Sport.FOOTBALL))
        assertEquals("End of extra time", AlertRules.breakHeadline(paused(4, "ET2"), Sport.FOOTBALL))
        assertEquals("Half time", AlertRules.breakHeadline(Fixtures.game(state = GameState.INTERMISSION, startTime = kickoff), Sport.FOOTBALL), "no clock: the one break every match has")
    }

    @Test
    fun `hockey breaks count the periods scored`() {
        val game = Fixtures.game(leagueId = "nhl", state = GameState.INTERMISSION, score = Score(2, 1), startTime = kickoff,
            clock = Fixtures.clock(Fixtures.period(2, "2"))).copy(periodScores = listOf(PeriodScore(Fixtures.period(1, "1"), 1, 0), PeriodScore(Fixtures.period(2, "2"), 1, 1)))
        assertEquals("End of period 2", AlertRules.breakHeadline(game, Sport.HOCKEY))
    }

    @Test
    fun `the result fires on full time and otherwise backs off by the clock`() {
        val done = live(2, 1, state = GameState.FINAL).copy(ending = GameEnding.REGULATION)
        val out = AlertRules.pollResult(row(AlertKind.RESULT), Sport.FOOTBALL, done, kickoffMs + 110 * 60_000L)
        assertNull(out.next)
        assertEquals("home-1 2 - 1 away-1", out.posts.single().title)
        assertEquals("Full time · Premier League", out.posts.single().text)

        // 70th minute: twenty to go plus stoppage.
        assertEquals(24, AlertRules.retryMinutes(live(0, 0, minute = 70, period = 2), Sport.FOOTBALL))
        // Half time: the second half plus stoppage, capped.
        assertEquals(30, AlertRules.retryMinutes(live(0, 0, minute = 45, state = GameState.INTERMISSION), Sport.FOOTBALL))
        // Deep in stoppage time: only the allowance is left.
        assertEquals(4, AlertRules.retryMinutes(live(0, 0, minute = 93, period = 2), Sport.FOOTBALL))
        assertEquals(10, AlertRules.retryMinutes(null, Sport.FOOTBALL))

        val retry = AlertRules.pollResult(row(AlertKind.RESULT), Sport.FOOTBALL, live(0, 0, minute = 70, period = 2), kickoffMs)
        assertEquals(kickoffMs + 24 * 60_000L, retry.next!!.dueAt)
        assertTrue(retry.posts.isEmpty())
    }

    @Test
    fun `a game called off is said once under one id whichever watch finds it, and a lost one is given up on`() {
        val postponed = Fixtures.game(state = GameState.POSTPONED, startTime = kickoff)
        val result = AlertRules.pollResult(row(AlertKind.RESULT), Sport.FOOTBALL, postponed, kickoffMs)
        assertNull(result.next)
        assertEquals("home-1 v away-1", result.posts.single().title)
        assertEquals("Postponed · Premier League", result.posts.single().text)
        assertEquals("premier-league/1|CALLED_OFF", result.posts.single().id)
        assertEquals(AlertKind.KICKOFF, result.posts.single().kind, "lands in the reminder channel, being the reminder's news undone")

        val start = AlertRules.pollStart(row(AlertKind.STARTED), Sport.FOOTBALL, postponed, kickoffMs)
        assertNull(start.next)
        assertEquals(result.posts.single().id, start.posts.single().id)
        assertEquals(result.posts.single().id, AlertRules.pollBreak(row(AlertKind.BREAK), Sport.FOOTBALL, postponed, kickoffMs).posts.single().id)
        assertEquals(result.posts.single().id, AlertRules.pollLive(row(AlertKind.LIVE), Sport.FOOTBALL, postponed, null, all, canEvents = false, now = kickoffMs).posts.single().id)

        val abandoned = Fixtures.game(state = GameState.CANCELLED, score = Score(1, 0), startTime = kickoff)
        assertEquals("Cancelled · Premier League", AlertRules.calledOffPost(row(AlertKind.KICKOFF), abandoned)!!.text)
        assertEquals("home-1 1 - 0 away-1", AlertRules.calledOffPost(row(AlertKind.KICKOFF), abandoned)!!.title)
        assertNull(AlertRules.calledOffPost(row(AlertKind.KICKOFF), live(1, 0, state = GameState.FINAL)), "a finished game is a result, not a cancellation")

        assertNull(AlertRules.pollResult(row(AlertKind.RESULT), Sport.FOOTBALL, null, kickoffMs + AlertRules.GIVE_UP_MS + 1).next)
        assertNotNull(AlertRules.pollResult(row(AlertKind.RESULT), Sport.FOOTBALL, null, kickoffMs + 60_000L).next)
    }

    @Test
    fun `settings know which queued kinds they still want`() {
        val s = AlertSettings(kickoff = false, goals = true, keys = setOf("x"))
        assertFalse(s.wants(AlertKind.KICKOFF))
        assertTrue(s.wants(AlertKind.LIVE))
        assertFalse(s.copy(goals = false).wants(AlertKind.LIVE))
        assertTrue(s.active)
        assertFalse(s.copy(keys = emptySet()).active)
        assertFalse(AlertSettings(kickoff = false, results = false, keys = setOf("x")).active)
    }
}
