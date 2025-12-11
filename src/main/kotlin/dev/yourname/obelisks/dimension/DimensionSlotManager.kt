package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.run.RunData
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.*
import kotlin.random.Random

/**
 * Manages assignment of obelisk runs to pre-existing dimension slots.
 * Each slot is a persistent dimension that can be assigned to one obelisk at a time.
 */
object DimensionSlotManager {

    // Configuration - TODO: move to config file
    private const val NETHER_SLOTS = 5  // Slots 0-4 are NETHER type
    private const val END_SLOTS = 5     // Slots 5-9 are END type
    private const val TOTAL_SLOTS = NETHER_SLOTS + END_SLOTS

    // Maps slot index -> obelisk UUID (null = available)
    private val slotAssignments = mutableMapOf<Int, UUID?>()

    // Maps obelisk UUID -> assigned slot index
    private val obeliskToSlot = mutableMapOf<UUID, Int>()

    /**
     * Attempts to assign an available slot to an obelisk for the specified dimension type.
     * Returns the slot index if successful, null if all slots of that type are full.
     */
    fun assignSlot(obeliskId: UUID, baseType: DimensionBaseType): Int? {
        // Check if this obelisk already has a slot
        obeliskToSlot[obeliskId]?.let { return it }

        // Determine which range of slots to search based on dimension type
        val slotRange = when (baseType) {
            DimensionBaseType.NETHER -> 0 until NETHER_SLOTS  // Slots 0-4
            DimensionBaseType.END -> NETHER_SLOTS until TOTAL_SLOTS  // Slots 5-9
        }

        // Find first available slot of the correct type
        for (slotIndex in slotRange) {
            if (slotAssignments[slotIndex] == null) {
                slotAssignments[slotIndex] = obeliskId
                obeliskToSlot[obeliskId] = slotIndex
                println("[$MOD_ID] Assigned ${baseType.name} slot $slotIndex to obelisk ${obeliskId.toString().substring(0, 8)}")
                return slotIndex
            }
        }

        // All slots of this type are full
        println("[$MOD_ID] All ${baseType.name} dimension slots are currently in use!")
        return null
    }

    /**
     * Releases a slot when an obelisk's run ends.
     */
    fun releaseSlot(obeliskId: UUID) {
        val slotIndex = obeliskToSlot.remove(obeliskId) ?: return
        slotAssignments[slotIndex] = null
        println("[$MOD_ID] Released slot $slotIndex from obelisk ${obeliskId.toString().substring(0, 8)}")
    }

    /**
     * Gets the slot index for an obelisk, or null if not assigned.
     */
    fun getSlotForObelisk(obeliskId: UUID): Int? {
        return obeliskToSlot[obeliskId]
    }

    /**
     * Gets the dimension for a given slot index.
     * Returns null if the dimension doesn't exist on the server.
     */
    fun getSlotDimension(server: MinecraftServer, slotIndex: Int): ServerLevel? {
        val dimensionKey = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation(MOD_ID, "run_slot_$slotIndex")
        )
        return server.getLevel(dimensionKey)
    }

    /**
     * Generates a random spawn position within a dimension slot.
     * This ensures each run has spatial separation even within the same dimension.
     */
    fun generateRandomSpawnPos(baseType: DimensionBaseType): BlockPos {
        // Random coordinates in a large range to ensure separation
        val x = Random.nextInt(-5000, 5000)
        val z = Random.nextInt(-5000, 5000)

        // Y coordinate depends on dimension type
        val y = when (baseType) {
            DimensionBaseType.NETHER -> 64 // Nether-like height
            DimensionBaseType.END -> 64    // End-like height
        }

        return BlockPos(x, y, z)
    }

    /**
     * Gets the dimension and spawn position for an obelisk's run.
     * Returns null if no slot is available.
     */
    fun getDimensionForRun(
        server: MinecraftServer,
        obeliskId: UUID,
        baseType: DimensionBaseType
    ): Pair<ServerLevel, BlockPos>? {
        // Try to get or assign a slot of the correct type
        val slotIndex = getSlotForObelisk(obeliskId) ?: assignSlot(obeliskId, baseType) ?: return null

        // Get the dimension
        val dimension = getSlotDimension(server, slotIndex)
            ?: throw IllegalStateException("Slot dimension run_slot_$slotIndex not found! Ensure dimension JSONs are in place.")

        // Generate random spawn position
        val spawnPos = generateRandomSpawnPos(baseType)

        return Pair(dimension, spawnPos)
    }

    /**
     * Gets how many slots are currently in use.
     */
    fun getUsedSlotCount(): Int = slotAssignments.count { it.value != null }

    /**
     * Gets total number of available slots.
     */
    fun getTotalSlotCount(): Int = TOTAL_SLOTS

    /**
     * Debug: prints current slot assignments.
     */
    fun debugPrintSlots() {
        println("[$MOD_ID] Dimension Slot Status:")
        for (i in 0 until TOTAL_SLOTS) {
            val obelisk = slotAssignments[i]
            if (obelisk != null) {
                println("  Slot $i: OCCUPIED by obelisk ${obelisk.toString().substring(0, 8)}")
            } else {
                println("  Slot $i: AVAILABLE")
            }
        }
    }
}
