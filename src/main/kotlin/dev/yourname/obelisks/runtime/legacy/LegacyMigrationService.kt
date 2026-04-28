package dev.yourname.obelisks.runtime.legacy

import java.util.UUID

/**
 * Migration boundary for the quarantined Obelisks save format.
 *
 * The old code is preserved under `obsolete/legacy_mod` and should be treated as a
 * reference implementation only. Migration logic should consume legacy save keys,
 * convert them into rewrite-era records, and then stop depending on the legacy model.
 */
object LegacyMigrationService {

    const val LEGACY_RUN_MANAGER_DATA_NAME: String = "obelisks_run_manager"
    const val LEGACY_PLAYER_CAPABILITY_ID: String = "player_run_info"

    fun migrateLegacyRun(snapshot: LegacyRunSnapshot): LegacyRunMigrationResult {
        return LegacyRunMigrationResult(
            legacyRunId = snapshot.legacyRunId,
            migrated = false,
            reason = "Migration not implemented yet"
        )
    }
}

data class LegacyRunSnapshot(
    val legacyRunId: Long,
    val obeliskId: UUID,
    val dimensionId: String,
    val activePlayers: Set<UUID>
)

data class LegacyRunMigrationResult(
    val legacyRunId: Long,
    val migrated: Boolean,
    val reason: String
)
