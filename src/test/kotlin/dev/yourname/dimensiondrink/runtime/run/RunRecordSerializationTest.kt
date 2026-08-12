package dev.yourname.dimensiondrink.runtime.run

import dev.yourname.dimensiondrink.runtime.backend.SiteBounds
import net.minecraft.core.BlockPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RunRecordSerializationTest {
    @Test
    fun savedDataDeclaresSchemaAndRejectsUnknownVersions() {
        val current = net.minecraft.nbt.CompoundTag().apply {
            putInt("schema", RunSavedData.SCHEMA_VERSION)
            put("runs", net.minecraft.nbt.ListTag())
        }
        val first = RunSavedData.load(current)
        val encoded = first.save(net.minecraft.nbt.CompoundTag())
        assertEquals(RunSavedData.SCHEMA_VERSION, encoded.getInt("schema"))
        assertTrue(RunSavedData.load(encoded).values().isEmpty())

        val newer = encoded.copy().apply { putInt("schema", RunSavedData.SCHEMA_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { RunSavedData.load(newer) }
        assertFailsWith<IllegalArgumentException> { RunSavedData.load(net.minecraft.nbt.CompoundTag()) }
    }

    @Test
    fun roundTripPreservesCoreFields() {
        val record = RunRecord(
            id = java.util.UUID.randomUUID(),
            instanceId = java.util.UUID.randomUUID(),
            obeliskId = java.util.UUID.randomUUID(),
            definitionId = "end",
            instanceTemplateId = "minecraft:the_end",
            originObeliskPos = BlockPos(10, 64, -20),
            backendSiteCenter = BlockPos(30, 80, -40),
            backendSiteBounds = SiteBounds(0, -64, 0, 100, 200, 100),
            spawnPos = BlockPos(30, 81, -40),
            createdGameTime = 100L,
            updatedGameTime = 120L,
            activePlayers = linkedSetOf(java.util.UUID.randomUUID()),
            pendingPlayers = linkedSetOf(java.util.UUID.randomUUID()),
            monstersKilled = 7,
            totalDamageDealt = 42.5f,
            ticksElapsed = 320L,
            drainMultiplier = 1.75,
            emptyTicks = 4L,
            rewardsGranted = true,
            state = RunState.ACTIVE
        )

        val serialized = record.toTag()
        val decoded = RunRecord.fromTag(serialized)
        assertNotNull(
            decoded,
            "serialized=$serialized id=${serialized.getString("id")} instance_id=${serialized.getString("instance_id")} obelisk_id=${serialized.getString("obelisk_id")} definition_id=${serialized.getString("definition_id")}"
        )
        assertEquals(record.id, decoded.id)
        assertEquals(record.instanceId, decoded.instanceId)
        assertEquals(record.obeliskId, decoded.obeliskId)
        assertEquals(record.definitionId, decoded.definitionId)
        assertEquals(record.instanceTemplateId, decoded.instanceTemplateId)
        assertEquals(record.originLevelKey, decoded.originLevelKey)
        assertEquals(record.originObeliskPos, decoded.originObeliskPos)
        assertEquals(record.backendLevelKey, decoded.backendLevelKey)
        assertEquals(record.backendSiteCenter, decoded.backendSiteCenter)
        assertEquals(record.backendSiteBounds, decoded.backendSiteBounds)
        assertEquals(record.spawnPos, decoded.spawnPos)
        assertEquals(record.createdGameTime, decoded.createdGameTime)
        assertEquals(record.updatedGameTime, decoded.updatedGameTime)
        assertEquals(record.monstersKilled, decoded.monstersKilled)
        assertEquals(record.totalDamageDealt, decoded.totalDamageDealt)
        assertEquals(record.ticksElapsed, decoded.ticksElapsed)
        assertEquals(record.drainMultiplier, decoded.drainMultiplier)
        assertEquals(record.emptyTicks, decoded.emptyTicks)
        assertEquals(record.rewardsGranted, decoded.rewardsGranted)
        assertEquals(record.state, decoded.state)
        assertEquals(record.activePlayers, decoded.activePlayers)
        assertEquals(record.pendingPlayers, decoded.pendingPlayers)
    }

    @Test
    fun fromTagRejectsInvalidIds() {
        val badTag = net.minecraft.nbt.CompoundTag().apply {
            putString("id", "bad")
            putString("instance_id", java.util.UUID.randomUUID().toString())
            putString("obelisk_id", java.util.UUID.randomUUID().toString())
            putString("definition_id", "end")
        }
        assertNull(RunRecord.fromTag(badTag))
    }

    @Test
    fun deepCopyDetachesPlayerSets() {
        val run = RunRecord(
            id = java.util.UUID.randomUUID(),
            instanceId = java.util.UUID.randomUUID(),
            obeliskId = java.util.UUID.randomUUID(),
            definitionId = "end",
            instanceTemplateId = "minecraft:the_end",
            activePlayers = linkedSetOf(java.util.UUID.randomUUID()),
            pendingPlayers = linkedSetOf(java.util.UUID.randomUUID())
        )
        val copy = run.deepCopy()
        run.activePlayers.clear()
        run.pendingPlayers.clear()
        assertTrue(copy.activePlayers.isNotEmpty())
        assertTrue(copy.pendingPlayers.isNotEmpty())
    }
}
