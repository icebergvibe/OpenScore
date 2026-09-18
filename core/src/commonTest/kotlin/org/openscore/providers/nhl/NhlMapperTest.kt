package org.openscore.providers.nhl

import org.openscore.model.GameState
import org.openscore.model.PeriodType
import org.openscore.model.hockey.Strength
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NhlMapperTest {

    @Test
    fun periodLabels() {
        assertEquals("2", NhlMapper.period(NhlPeriodDescriptor(2, "REG", 3)).label)
        assertEquals("OT", NhlMapper.period(NhlPeriodDescriptor(4, "OT", 3)).label)
        assertEquals("2OT", NhlMapper.period(NhlPeriodDescriptor(5, "OT", 3)).label)
        assertEquals("3OT", NhlMapper.period(NhlPeriodDescriptor(6, "OT", 3)).label)
        val so = NhlMapper.period(NhlPeriodDescriptor(5, "SO", 3))
        assertEquals("SO", so.label)
        assertEquals(PeriodType.SHOOTOUT, so.type)
    }

    @Test
    fun clockParsing() {
        assertEquals(13.minutes + 38.seconds, NhlMapper.clockDuration("13:38"))
        assertEquals(0.seconds, NhlMapper.clockDuration("00:00"))
        assertNull(NhlMapper.clockDuration("bogus"))
        assertNull(NhlMapper.clockDuration(null))
    }

    @Test
    fun strengthFromSituationCode() {
        assertEquals(Strength.EV to false, NhlMapper.strength("1551", scoringTeamIsHome = true))
        assertEquals(Strength.PP to false, NhlMapper.strength("1541", scoringTeamIsHome = false), "away 5 v home 4")
        assertEquals(Strength.SH to false, NhlMapper.strength("1541", scoringTeamIsHome = true))
        assertEquals(Strength.EV to true, NhlMapper.strength("1560", scoringTeamIsHome = false), "home pulled goalie, away scores EN at even strength")
        assertEquals(Strength.SH to true, NhlMapper.strength("1460", scoringTeamIsHome = false), "away short-handed into empty net")
        assertEquals(null to null, NhlMapper.strength(null, true))
        assertEquals(null to null, NhlMapper.strength("15", true))
    }

    @Test
    fun gameStates() {
        assertEquals(GameState.SCHEDULED, NhlMapper.gameState("FUT", "OK", null))
        assertEquals(GameState.PRE_GAME, NhlMapper.gameState("PRE", "OK", null))
        assertEquals(GameState.LIVE, NhlMapper.gameState("LIVE", "OK", NhlClock(running = true)))
        assertEquals(GameState.LIVE, NhlMapper.gameState("CRIT", "OK", NhlClock(running = true)))
        assertEquals(GameState.INTERMISSION, NhlMapper.gameState("LIVE", "OK", NhlClock(inIntermission = true)))
        assertEquals(GameState.FINAL, NhlMapper.gameState("OVER", "OK", null))
        assertEquals(GameState.FINAL, NhlMapper.gameState("OFF", "OK", null))
        assertEquals(GameState.POSTPONED, NhlMapper.gameState("FUT", "PPD", null))
        assertEquals(GameState.CANCELLED, NhlMapper.gameState("FUT", "CNCL", null))
        assertEquals(GameState.SUSPENDED, NhlMapper.gameState("LIVE", "SUSP", null))
        assertEquals(GameState.UNKNOWN, NhlMapper.gameState("???", "OK", null))
    }
}
