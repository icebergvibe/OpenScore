package org.openscore.model

import org.openscore.model.football.footballFormationLabel

public enum class LineupGroupKind {
    /** Positional groups (what the NHL boxscore gives). */
    FORWARDS, DEFENSE, GOALIES,
    /** Actual line / pairing structure where the league publishes it (CHL, Liiga, SHL). */
    LINE, PAIRING,
    STARTERS, BENCH, SCRATCHES,
    OTHER,
}

public data class LineupGroup(
    val kind: LineupGroupKind,
    /** Display label: `Forwards`, `Line 1`, `Pairing 2`, `Scratches` … */
    val label: String,
    val players: List<PlayerRef>,
)

public data class Lineup(
    val gameId: String,
    val team: TeamRef,
    val groups: List<LineupGroup>,
    val headCoach: String? = null,
    /** Canonical football formation as digits per line (`433`, `41212`), when published. */
    val formation: String? = null,
) {
    val players: List<PlayerRef> get() = groups.flatMap { it.players }
    /** Formation in the shared user-facing form (`4-3-3`), never provider punctuation. */
    val formationLabel: String? get() = footballFormationLabel(formation)
}
