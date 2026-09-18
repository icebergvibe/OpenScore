package org.openscore.providers.mlb

import org.openscore.model.GameState
import org.openscore.model.Score
import org.openscore.model.TeamRef
import org.openscore.model.baseball.BaseRunningDetails
import org.openscore.model.baseball.BaseballEventType
import org.openscore.model.baseball.BaseballSubstitutionDetails
import org.openscore.model.baseball.InningHalf
import org.openscore.model.baseball.PlateAppearanceDetails
import org.openscore.net.OpenScoreJson
import org.openscore.testing.SampleFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mapper-level checks on samples the provider does not fetch directly: the full play list and the light linescores. */
class MlbMapperTest {

    private val dir = SampleFetcher.samplesDir("baseball", "mlb")
    private fun <T> read(name: String, s: kotlinx.serialization.DeserializationStrategy<T>): T =
        OpenScoreJson.decodeFromString(s, File(dir, name).readText())

    private val home = TeamRef("mlb", "136", "Seattle Mariners", "SEA")
    private val away = TeamRef("mlb", "140", "Texas Rangers", "TEX")

    @Test
    fun fullGameEvents() {
        val plays = read("playByPlay.final.fields.json", MlbPlays.serializer())
        val events = MlbMapper.events(plays, home, away, scheduledInnings = 9)

        val pas = events.filter { it.details is PlateAppearanceDetails }
        assertEquals(67, pas.size, "one event per plate appearance")
        assertEquals(plays.scoringPlays.map { it.toString() }, pas.filter { it.score != null }.map { it.id })
        val hr = pas.first { it.type == BaseballEventType.HOME_RUN }
        assertEquals("15", hr.id)
        assertEquals(Score(home = 0, away = 2), hr.score)
        assertEquals("Texas Rangers", hr.team?.name)
        val d = assertIs<PlateAppearanceDetails>(hr.details)
        assertEquals(2, d.rbi)
        assertTrue(d.scoringPlay)
        assertEquals(2, d.runners.count { it.scored })
        assertEquals("Joc Pederson", d.batter?.name)
        assertEquals("fly_ball", d.battedBall?.trajectory)

        val goAhead = pas.first { it.id == "49" }
        assertEquals(BaseballEventType.HOME_RUN, goAhead.type)
        assertEquals("Bot 7", goAhead.time.label)
        assertEquals(Score(home = 4, away = 3), goAhead.score)
        assertEquals("Seattle Mariners", goAhead.team?.name)
        val last = pas.last()
        assertEquals(BaseballEventType.FORCE_OUT, last.type)
        assertEquals("Top 9", last.time.label)
        assertEquals(3, assertIs<PlateAppearanceDetails>(last.details).outsAfter)

        assertEquals(
            setOf(
                BaseballEventType.STRIKEOUT, BaseballEventType.FIELD_OUT, BaseballEventType.SINGLE, BaseballEventType.WALK,
                BaseballEventType.HOME_RUN, BaseballEventType.HIT_BY_PITCH, BaseballEventType.SACRIFICE_BUNT, BaseballEventType.RUNNER_OUT,
                BaseballEventType.FIELDERS_CHOICE, BaseballEventType.INTENTIONAL_WALK, BaseballEventType.FORCE_OUT,
            ),
            pas.map { it.type }.toSet(),
        )

        val actions = events.filter { it.details !is PlateAppearanceDetails }
        assertEquals(20, actions.size)
        assertEquals(
            mapOf(BaseballEventType.STOLEN_BASE to 3, BaseballEventType.WILD_PITCH to 2, BaseballEventType.SUBSTITUTION to 15),
            actions.groupingBy { it.type as BaseballEventType }.eachCount(),
            "timeouts, mound visits and advisories are not events",
        )
        val steal = actions.first()
        assertEquals("27.4", steal.id)
        assertEquals("stolen_base_2b", steal.rawType)
        assertEquals("Texas Rangers", steal.team?.name)
        assertEquals("Evan Carter", assertIs<BaseRunningDetails>(steal.details).runner?.name, "name resolved from the play's runner list")
        assertTrue(steal.sortOrder!! < pas.first { it.id == "27" }.sortOrder!!, "an action sorts before the plate appearance it happened in")

        val pitching = actions.first { it.rawType == "pitching_substitution" }
        val sub = assertIs<BaseballSubstitutionDetails>(pitching.details)
        assertEquals("Texas Rangers", pitching.team?.name, "a pitching change belongs to the fielding side")
        assertEquals("pitching", sub.kind)
        assertEquals("P", sub.position)
        assertEquals("Jacob deGrom", sub.replaces)
        assertEquals("Jakob Junis", sub.playerIn?.name, "no player map: name resolved from the description")

        val defensive = actions.first { it.rawType == "defensive_substitution" }
        assertEquals("Cam Cauley", assertIs<BaseballSubstitutionDetails>(defensive.details).replaces)
        val pinch = actions.first { it.rawType == "offensive_substitution" }
        assertEquals("offensive", assertIs<BaseballSubstitutionDetails>(pinch.details).kind)
        assertEquals("Texas Rangers", pinch.team?.name)
        assertEquals("switch", assertIs<BaseballSubstitutionDetails>(actions.first { it.rawType == "defensive_switch" }.details).kind)
    }

    @Test
    fun extraInningsLinescore() {
        val ls = read("linescore.final-extra.json", MlbLinescore.serializer())
        val scores = MlbMapper.periodScores(ls)
        assertEquals(11, scores.size)
        assertEquals(listOf("10", "11"), scores.takeLast(2).map { it.period.label })
        assertEquals(org.openscore.model.PeriodType.OVERTIME, scores.last().period.type)
        assertEquals(org.openscore.model.PeriodType.REGULATION, scores[8].period.type)
    }

    @Test
    fun inningBreakIsIntermission() {
        val ls = read("linescore.live-break.json", MlbLinescore.serializer())
        val status = MlbStatus(codedGameState = "I", detailedState = "In Progress", statusCode = "I")
        val state = MlbMapper.gameState(status, ls)
        assertEquals(GameState.INTERMISSION, state)
        val s = assertNotNull(MlbMapper.situation(state, ls))
        assertEquals(InningHalf.END, s.half)
        assertEquals(6, s.inning)
        assertEquals(3, s.outs)

        val liveLs = read("linescore.live.json", MlbLinescore.serializer())
        assertEquals(GameState.LIVE, MlbMapper.gameState(status, liveLs))
    }

    @Test
    fun statusCodes() {
        fun state(code: String) = MlbMapper.gameState(MlbStatus(codedGameState = code), null)
        assertEquals(GameState.SCHEDULED, state("S"))
        assertEquals(GameState.PRE_GAME, state("P"))
        assertEquals(GameState.LIVE, state("I"))
        assertEquals(GameState.LIVE, state("M"))
        assertEquals(GameState.LIVE, state("N"))
        assertEquals(GameState.SUSPENDED, state("T"))
        assertEquals(GameState.SUSPENDED, state("U"))
        assertEquals(GameState.FINAL, state("O"))
        assertEquals(GameState.FINAL, state("F"))
        assertEquals(GameState.FINAL, state("Q"))
        assertEquals(GameState.POSTPONED, state("D"))
        assertEquals(GameState.CANCELLED, state("C"))
        assertEquals(GameState.UNKNOWN, state("X"))
    }

    @Test
    fun bio() {
        assertEquals(201, MlbMapper.heightCm("6' 7\""))
        assertEquals(183, MlbMapper.heightCm("6' 0\""))
        assertNull(MlbMapper.heightCm("tall"))
        assertEquals("S/R", MlbMapper.handedness("S", "R"))
        assertNull(MlbMapper.handedness(null, null))
    }
}
