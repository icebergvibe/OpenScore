package org.openscore.app.data

import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PeriodType

/**
 * [PeriodScore]s as one text column: `number|type|label|home|away` per segment, `;` between
 * segments — `1|REGULATION|1|0|2;2|REGULATION|2|0|1;4|OVERTIME|OT|1|0`. Labels are what a
 * scoreboard prints (`1`, `OT`, `SO`, `1H`, `Top 9`) and never carry either separator. A
 * segment that does not parse is dropped rather than failing the whole row.
 */
object PeriodScoresCodec {
    private const val SEGMENT = ';'
    private const val FIELD = '|'

    fun encode(scores: List<PeriodScore>): String = scores.joinToString(SEGMENT.toString()) {
        listOf(it.period.number, it.period.type.name, it.period.label, it.home, it.away).joinToString(FIELD.toString())
    }

    fun decode(text: String): List<PeriodScore> {
        if (text.isEmpty()) return emptyList()
        return text.split(SEGMENT).mapNotNull { segment ->
            val fields = segment.split(FIELD)
            if (fields.size != 5) return@mapNotNull null
            val number = fields[0].toIntOrNull() ?: return@mapNotNull null
            val type = PeriodType.entries.firstOrNull { it.name == fields[1] } ?: PeriodType.UNKNOWN
            val home = fields[3].toIntOrNull() ?: return@mapNotNull null
            val away = fields[4].toIntOrNull() ?: return@mapNotNull null
            PeriodScore(Period(number, type, fields[2]), home, away)
        }
    }
}
