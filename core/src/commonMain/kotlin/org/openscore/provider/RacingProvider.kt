package org.openscore.provider

import org.openscore.model.League
import org.openscore.model.motorsport.RacingClassification
import org.openscore.model.motorsport.RacingSeason
import org.openscore.model.motorsport.RacingSessionKind
import org.openscore.model.motorsport.RacingStandings

/**
 * Core boundary for racing sources. A race weekend is not forced into the two-team
 * [LeagueProvider] model, but the app still depends on a provider-neutral contract.
 */
public interface RacingProvider {
    public val league: League

    public suspend fun season(year: Int): RacingSeason

    public suspend fun classification(year: Int, round: Int, kind: RacingSessionKind): RacingClassification

    public suspend fun standings(year: Int): RacingStandings
}
