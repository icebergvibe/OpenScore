package org.openscore.providers.football

import org.openscore.model.GameTime
import org.openscore.model.Period
import org.openscore.model.PeriodType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Football period conventions shared by every football provider: 1H/2H are regulation,
 * ET1/ET2 overtime, PENS the shoot-out. Match minutes are cumulative (`67'`), so each
 * period has a minute offset; [GameTime.elapsed] is time within the period and
 * [GameTime.label] the conventional cumulative minute.
 */
public object FootballPeriods {
    public val FIRST_HALF: Period = Period(1, PeriodType.REGULATION, "1H")
    public val SECOND_HALF: Period = Period(2, PeriodType.REGULATION, "2H")
    public val EXTRA_FIRST: Period = Period(3, PeriodType.OVERTIME, "ET1")
    public val EXTRA_SECOND: Period = Period(4, PeriodType.OVERTIME, "ET2")
    public val PENALTIES: Period = Period(5, PeriodType.SHOOTOUT, "PENS")

    /** Regulation minutes that precede the period. */
    public fun offsetMinutes(period: Period): Int = when (period.number) {
        1 -> 0
        2 -> 45
        3 -> 90
        4 -> 105
        else -> 120
    }

    /** Nominal length; football periods run past it with stoppage time. */
    public fun length(period: Period): Duration? = when (period.number) {
        1, 2 -> 45.minutes
        3, 4 -> 15.minutes
        else -> null
    }

    /** Period a cumulative match minute falls in (without extra time → 2H after 45). */
    public fun periodForMinute(minute: Int, extraTime: Boolean = false): Period = when {
        minute <= 45 -> FIRST_HALF
        minute <= 90 || !extraTime -> SECOND_HALF
        minute <= 105 -> EXTRA_FIRST
        else -> EXTRA_SECOND
    }

    /** `67'` / `90'+4` from a cumulative minute and optional stoppage minute. */
    public fun label(minute: Int, stoppage: Int? = null): String =
        if (stoppage != null && stoppage > 0) "$minute'+$stoppage" else "$minute'"

    /** Time from a cumulative minute (and stoppage) in a known period. */
    public fun timeAtMinute(period: Period, minute: Int, stoppage: Int? = null, extraSeconds: Int = 0): GameTime {
        val within = ((minute - offsetMinutes(period)).coerceAtLeast(0) + (stoppage ?: 0)).minutes + extraSeconds.seconds
        return GameTime(period, elapsed = within, remaining = null, label = label(minute, stoppage))
    }

    /**
     * Time from a cumulative match minute that may run past the period's nominal end
     * (`95` in the second half → `90'+5`), with optional seconds within that minute.
     */
    public fun fromCumulative(period: Period, minute: Int, seconds: Int = 0, floorMinutes: Boolean = false): GameTime {
        val end = offsetMinutes(period) + (length(period)?.inWholeMinutes?.toInt() ?: Int.MAX_VALUE)
        val time = if (minute > end) timeAtMinute(period, end, minute - end, seconds) else timeAtMinute(period, minute, null, seconds)
        if (!floorMinutes) return time
        // Feeds that give the elapsed floor minute (`1:17`): the conventional label is the minute in progress.
        val shown = minute + 1
        val label = if (shown > end) label(end, shown - end) else label(shown)
        return time.copy(label = label)
    }

    /** Time from seconds elapsed in a period: the label is the conventional minute in progress. */
    public fun timeInPeriod(period: Period, elapsed: Duration): GameTime {
        val base = offsetMinutes(period)
        val len = length(period)
        val minuteInProgress = elapsed.inWholeMinutes.toInt() + 1
        val label = if (len != null && elapsed >= len) {
            val nominal = base + len.inWholeMinutes.toInt()
            label(nominal, (elapsed - len).inWholeMinutes.toInt() + 1)
        } else {
            label(base + minuteInProgress)
        }
        return GameTime(period, elapsed = elapsed, remaining = null, label = label)
    }

    /**
     * Parses `"52'"`, `"45' +1"`, `"90'+4"`, `"45+2"`, `"67"` → (minute, stoppage).
     */
    public fun parseMinute(text: String?): Pair<Int, Int?>? {
        if (text == null) return null
        val m = MINUTE.find(text.trim()) ?: return null
        val minute = m.groupValues[1].toIntOrNull() ?: return null
        val stoppage = m.groupValues[2].toIntOrNull()
        return minute to stoppage
    }

    private val MINUTE = Regex("""^(\d+)'?\s*(?:\+\s*(\d+))?'?$""")
}
