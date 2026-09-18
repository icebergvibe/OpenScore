package org.openscore.model

import kotlinx.datetime.LocalDate

/** Enough to show a player in an event line or a lineup. */
public data class PlayerRef(
    val leagueId: String,
    /** League-native player id. */
    val id: String,
    /** Display name, normalized for presentation; providers give the best data they have. */
    var name: String,
    val jerseyNumber: Int? = null,
    /** League-native position code (`C`, `LW`, `D`, `G`, `GK`, `MF` …). Not normalised across sports. */
    val position: String? = null,
    val headshotUrl: String? = null,
) {
    init {
        name = PlayerNames.display(name)
    }
}

/**
 * Provider-independent helpers for turning a feed's person-name fields into a display name.
 *
 * A name's order is deliberately left alone unless a provider explicitly identifies an
 * all-caps leading family name.  Inferring order from arbitrary names is unsafe across locales.
 */
public object PlayerNames {
    /** Formats an already assembled name. */
    public fun display(value: String, leadingCapsAreFamilyName: Boolean = false): String {
        val parts = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (parts.isEmpty()) return ""

        val reordered = if (leadingCapsAreFamilyName) {
            val familyEnd = parts.indexOfFirst { !it.isAllCapsWord() }
            if (familyEnd in 1 until parts.size) parts.drop(familyEnd) + parts.take(familyEnd) else parts
        } else {
            parts
        }
        return reordered.joinToString(" ", transform = ::titleCaseAllCaps)
    }

    /** Prefers separately supplied given and family names over a compact fallback. */
    public fun fromParts(firstName: String?, lastName: String?, fallback: String = ""): String =
        listOfNotNull(firstName?.trim()?.takeIf(String::isNotEmpty), lastName?.trim()?.takeIf(String::isNotEmpty))
            .joinToString(" ")
            .ifEmpty { fallback }
            .let(::display)

    private fun String.isAllCapsWord(): Boolean = any(Char::isLetter) && filter(Char::isLetter).all(Char::isUpperCase)

    private fun titleCaseAllCaps(word: String): String {
        // Initials such as `J.P.` already have intentional casing.
        if (!word.isAllCapsWord() || word.contains('.')) return word
        return word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

public data class Player(
    val ref: PlayerRef,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val birthPlace: String? = null,
    /** ISO 3166-1 alpha-3 where the league uses it (the NHL does), otherwise as given. */
    val nationality: String? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null,
    /** Handedness where the sport has one: `L`/`R` for skaters (shoots) and goalies (catches). */
    val handedness: String? = null,
    /** Id of the player's current team in the same league, if known. */
    val teamId: String? = null,
    val active: Boolean? = null,
) {
    val leagueId: String get() = ref.leagueId
    val id: String get() = ref.id
    val name: String get() = ref.name
}
