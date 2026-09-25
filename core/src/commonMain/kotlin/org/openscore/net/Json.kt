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

/**
 * For writing a request body, which is the opposite problem from reading a response: every
 * field the upstream expects has to be there, and a Kotlin default is exactly the field most
 * likely to be left out. [OpenScoreJson] would drop `{"scope":"team","page":1,…}` down to the
 * one value the caller passed, and HockeyAllsvenskan answers a body missing `scheduledDateTime`
 * with a `400` and one missing `phase` with an empty list and no error at all.
 *
 * Only [QueryFetcher] callers need this; a GET puts its parameters in the URL.
 */
public val OpenScoreRequestJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
}
