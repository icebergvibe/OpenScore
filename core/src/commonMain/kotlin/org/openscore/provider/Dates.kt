package org.openscore.provider

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Lenient ISO-8601 instant parsing for feeds that write offsets as `+0000` or omit seconds.
 *
 * Reach for [instant] only where a captured sample shows one of those forms - today LaLiga and
 * Bundesliga (compact offsets) and the UFC (times with no seconds). Everywhere else a plain
 * `Instant.parse` is the right call, and the difference is deliberate: a feed documented under
 * `apis/` as writing strict ISO-8601 that suddenly does not is a change worth hearing about,
 * and blanket leniency would absorb it silently. Nothing else catches it either - the api-health
 * checks compare key structure, not value formats.
 *
 * Note that [instant] throws just as `Instant.parse` does. It is lenient about the shape of a
 * timestamp, not about its absence: choosing it does not make a mapper tolerant of a bad row.
 * That is [instantOrNull]'s job, and a caller that uses it has to decide what a game with no
 * start time even means - which is why the mappers, where `Game.startTime` is required, throw.
 */
public object Dates {
    private val COMPACT_OFFSET = Regex("""([+-]\d{2})(\d{2})$""")
    /** `T21:30Z` / `T21:30+02:00`: a time with no seconds (the UFC feed). */
    private val NO_SECONDS = Regex("""T(\d{2}:\d{2})(?=Z$|[+-]\d{2}:\d{2}$)""")

    public fun instant(text: String): Instant {
        var t = text.trim()
        if (!t.endsWith("Z")) t = COMPACT_OFFSET.replace(t) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        t = NO_SECONDS.replace(t) { "T${it.groupValues[1]}:00" }
        return Instant.parse(t)
    }

    public fun instantOrNull(text: String?): Instant? = text?.let { runCatching { instant(it) }.getOrNull() }

    /**
     * The calendar date at the front of [text] (`2001-05-14`, `2001-05-14T00:00:00Z`, …), or null
     * when there is none — how every feed writes a birth date, with or without a time part.
     */
    public fun localDateOrNull(text: String?): LocalDate? =
        text?.trim()?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
}
