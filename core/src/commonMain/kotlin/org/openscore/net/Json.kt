package org.openscore.net

import kotlinx.serialization.json.Json

/**
 * Lenient parser shared by all providers: unknown keys are ignored (feeds add fields all
 * the time), and explicit `null` falls back to the DTO default so partial payloads
 * (pre-game shapes) parse.
 */
public val OpenScoreJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
