package dev.yourname.dimensiondrink.runtime.legacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LegacyMigrationServiceTest {
    @Test
    fun migrateLegacyRunIsExplicitlyNotImplementedYet() {
        val snapshot = LegacyRunSnapshot(
            legacyRunId = 42L,
            obeliskId = java.util.UUID.randomUUID(),
            dimensionId = "minecraft:the_end",
            activePlayers = setOf(java.util.UUID.randomUUID())
        )

        val result = LegacyMigrationService.migrateLegacyRun(snapshot)
        assertEquals(42L, result.legacyRunId)
        assertFalse(result.migrated)
        assertEquals("Migration not implemented yet", result.reason)
    }
}
