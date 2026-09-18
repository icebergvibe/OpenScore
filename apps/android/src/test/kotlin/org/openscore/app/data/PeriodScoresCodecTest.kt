package org.openscore.app.data

import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PeriodType
import kotlin.test.Test
import kotlin.test.assertEquals

class PeriodScoresCodecTest {
    private val hockeyFinalInOvertime = listOf(
        PeriodScore(Period(1, PeriodType.REGULATION, "1"), 0, 2),
        PeriodScore(Period(2, PeriodType.REGULATION, "2"), 0, 1),
        PeriodScore(Period(3, PeriodType.REGULATION, "3"), 1, 1),
        PeriodScore(Period(4, PeriodType.OVERTIME, "OT"), 1, 0),
    )

    @Test
    fun roundTripsEverySegment() {
        val text = PeriodScoresCodec.encode(hockeyFinalInOvertime)
        assertEquals("1|REGULATION|1|0|2;2|REGULATION|2|0|1;3|REGULATION|3|1|1;4|OVERTIME|OT|1|0", text)
        assertEquals(hockeyFinalInOvertime, PeriodScoresCodec.decode(text))
        assertEquals(emptyList(), PeriodScoresCodec.decode(PeriodScoresCodec.encode(emptyList())))
    }

    @Test
    fun aSegmentThatDoesNotParseIsDroppedAndAnUnknownTypeKept() {
        val decoded = PeriodScoresCodec.decode("1|REGULATION|1|0|2;garbage;9|SOMETHING_NEW|Top 9|x|1;5|SHOOTOUT|SO|1|0")
        assertEquals(
            listOf(PeriodScore(Period(1, PeriodType.REGULATION, "1"), 0, 2), PeriodScore(Period(5, PeriodType.SHOOTOUT, "SO"), 1, 0)),
            decoded,
        )
        assertEquals(PeriodType.UNKNOWN, PeriodScoresCodec.decode("7|SOMETHING_NEW|X|0|0").single().period.type)
    }
}
