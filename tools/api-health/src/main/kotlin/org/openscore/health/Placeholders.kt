package org.openscore.health

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter

/**
 * `{…}` substitutions in check paths, URLs, queries and header values:
 *
 * - `{today}`, `{today+3}`, `{today-1}` — a UTC date, ISO by default
 * - `{today|yyyyMMdd}` — any `DateTimeFormatter` pattern; `{today|epoch}` = seconds at 00:00 UTC
 * - `{year}` — the current UTC year
 * - `{secret.name}` — resolved by [SecretResolver] (LaLiga's page-embedded keys)
 *
 * Only these names are recognised; any other `{…}` (a GraphQL selection set, say) is left
 * verbatim. A known placeholder that cannot be resolved throws [UnresolvedPlaceholder] so the
 * check is reported as skipped rather than run with a bogus URL.
 */
public class Placeholders(
    private val today: LocalDate,
    private val secrets: SecretResolver = SecretResolver.NONE,
) {
    private val pattern = Regex("\\{(today|year|secret)(?:\\.([a-zA-Z0-9_.]+))?([+-]\\d+)?(?:\\|([^}]+))?}")

    public suspend fun resolve(text: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in pattern.findAll(text)) {
            out.append(text, last, m.range.first)
            out.append(value(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], m.value))
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    private suspend fun value(name: String, secret: String, offset: String, format: String, raw: String): String = when (name) {
        "today" -> format(today.plus(offset.toIntOrZero(), DateTimeUnit.DAY), format)
        "year" -> today.year.toString()
        else -> secrets.get(secret) ?: throw UnresolvedPlaceholder(raw, "secret '$secret' not available")
    }

    private fun format(date: LocalDate, format: String): String = when (format) {
        "" -> date.toString()
        "epoch" -> date.atStartOfDayIn(TimeZone.UTC).epochSeconds.toString()
        else -> DateTimeFormatter.ofPattern(format).format(date.toJavaLocalDate())
    }

    private fun String.toIntOrZero(): Int = if (isEmpty()) 0 else toInt()
}

public class UnresolvedPlaceholder(public val placeholder: String, reason: String) : RuntimeException("$placeholder: $reason")

/** Values that cannot live in `health.json` because they rotate — looked up at run time. */
public fun interface SecretResolver {
    public suspend fun get(name: String): String?

    public companion object {
        public val NONE: SecretResolver = SecretResolver { null }
    }
}
