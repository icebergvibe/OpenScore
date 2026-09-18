package org.openscore.app

import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Period
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.TeamRef
import kotlin.time.Duration
import kotlin.time.Instant

object Fixtures {
    fun team(leagueId: String, id: String, name: String = id) = TeamRef(leagueId, id, name, abbreviation = id.take(3).uppercase())

    fun game(
        leagueId: String = "premier-league",
        id: String = "1",
        state: GameState = GameState.SCHEDULED,
        score: Score? = null,
        clock: Clock? = null,
        competition: String? = null,
        home: TeamRef = team(leagueId, "home-$id"),
        away: TeamRef = team(leagueId, "away-$id"),
        startTime: Instant = Instant.parse("2026-09-13T14:00:00Z"),
    ) = Game(
        leagueId = leagueId, id = id, startTime = startTime, home = home, away = away,
        state = state, score = score, clock = clock, competition = competition,
    )

    fun period(number: Int, label: String, type: PeriodType = PeriodType.REGULATION) = Period(number, type, label)

    fun clock(period: Period, elapsed: Duration? = null, remaining: Duration? = null, label: String? = null) =
        Clock(GameTime(period, elapsed, remaining, label), running = true)
}
