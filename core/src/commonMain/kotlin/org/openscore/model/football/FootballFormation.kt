package org.openscore.model.football

/**
 * The canonical core representation of a football formation: digits only, one digit per line.
 *
 * Upstreams variously send `433`, `4-3-3`, or a tactical description such as
 * `3-4-2-1 Double-10`. Keeping just the leading formation prevents provider punctuation and
 * commentary leaking into consumers, which can render it consistently as `4-3-3`.
 */
public fun footballFormation(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val chain = FORMATION_CHAIN.find(raw)?.value
    val digits = (chain ?: raw.takeWhile(Char::isDigit)).filter(Char::isDigit)
    return digits.takeIf { it.length >= 3 }
}

/** A formation ready for user-facing scoreboards and line-up screens, e.g. `4-2-3-1`. */
public fun footballFormationLabel(value: String?): String? =
    footballFormation(value)?.toList()?.joinToString("-")

private val FORMATION_CHAIN = Regex("\\d+(?:\\s*[-–—/]\\s*\\d+)+")
