package org.openscore.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class LeagueReport(
    val league: String,
    val name: String,
    val readme: String,
    val results: List<CheckResult>,
) {
    val passed: Int get() = results.count { it.status == Status.PASS }
    val warned: Int get() = results.count { it.status == Status.WARN }
    val failed: Int get() = results.count { it.status == Status.FAIL }
    val skipped: Int get() = results.count { it.status == Status.SKIP }

    /** The league's verdict: broken if any endpoint fails, drifting if only shapes changed. */
    val status: Status
        get() = when {
            failed > 0 -> Status.FAIL
            warned > 0 -> Status.WARN
            passed > 0 -> Status.PASS
            else -> Status.SKIP
        }
}

@Serializable
public data class HealthReport(
    val generatedAt: String,
    val leagues: List<LeagueReport>,
) {
    val results: List<CheckResult> get() = leagues.flatMap { it.results }
    val failed: Int get() = leagues.sumOf { it.failed }
    val warned: Int get() = leagues.sumOf { it.warned }
}

public object Report {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    public fun mark(status: Status): String = when (status) {
        Status.PASS -> "✅"
        Status.WARN -> "⚠️"
        Status.FAIL -> "❌"
        Status.SKIP -> "⏭️"
    }

    /** One terminal line per check. */
    public fun line(r: CheckResult): String = buildString {
        append("  ").append(mark(r.status)).append(' ').append(r.name.padEnd(48).take(48))
        append(' ').append((r.httpStatus?.toString() ?: "-").padStart(3))
        append(' ').append((r.millis?.let { "${it} ms" } ?: "").padStart(8))
        if (r.message.isNotEmpty()) append("  ").append(r.message)
        if (r.missing.isNotEmpty()) append("  [").append(r.missing.joinToString(", ")).append(']')
    }

    public fun terminalSummary(report: HealthReport): String = buildString {
        appendLine()
        appendLine("Summary (${report.generatedAt})")
        for (l in report.leagues) {
            appendLine("  ${mark(l.status)} ${l.name.padEnd(28)} ${l.passed} pass, ${l.warned} warn, ${l.failed} fail, ${l.skipped} skip")
        }
        appendLine()
        val leagues = report.leagues
        appendLine("${leagues.count { it.status == Status.PASS }} of ${leagues.size} leagues fully healthy; ${report.failed} failing checks, ${report.warned} shape warnings.")
    }

    public fun toJson(report: HealthReport): String = json.encodeToString(HealthReport.serializer(), report)

    /** Markdown suitable for a GitHub step summary or a committed report. */
    public fun toMarkdown(report: HealthReport): String = buildString {
        appendLine("# OpenScore API health — ${report.generatedAt}")
        appendLine()
        appendLine("| League | Status | Pass | Warn | Fail | Skip |")
        appendLine("|---|---|---:|---:|---:|---:|")
        for (l in report.leagues) {
            appendLine("| [${l.name}](${l.readme}) | ${mark(l.status)} | ${l.passed} | ${l.warned} | ${l.failed} | ${l.skipped} |")
        }
        appendLine()
        appendLine("✅ endpoint answers and matches its sample · ⚠️ answers but the sample has keys the live response lacks (docs or DTOs may need an update) · ❌ no usable response · ⏭️ not run")
        for (l in report.leagues) {
            appendLine()
            appendLine("## ${mark(l.status)} ${l.name}")
            appendLine()
            appendLine("| Check | Status | HTTP | ms | Result |")
            appendLine("|---|---|---:|---:|---|")
            for (r in l.results) {
                val detail = buildString {
                    append(r.message.escape())
                    if (r.missing.isNotEmpty()) append(" — missing: `").append(r.missing.joinToString("`, `")).append('`')
                    if (r.newPaths > 0) append(" (+${r.newPaths} new)")
                }
                val name = if (r.url != null) "[${r.name.escape()}](${r.url})" else r.name.escape()
                appendLine("| $name | ${mark(r.status)} | ${r.httpStatus ?: ""} | ${r.millis ?: ""} | $detail |")
            }
        }
    }

    private fun String.escape(): String = replace("|", "\\|").replace("\n", " ")
}
