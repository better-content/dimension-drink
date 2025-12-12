package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ConfigManager
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.*
import kotlin.random.Random

/**
 * Manages assignment of obelisk runs to pre-existing dimension slots.
 * Each slot is a persistent dimension that can be assigned to one obelisk at a time.
 * Slots are dynamically allocated based on dimension configs loaded from JSON.
 */
object DimensionSlotManager {

    // Dynamic slot configuration
    private data class SlotRange(val baseType: DimensionBaseType, val start: Int, val end: Int)
    private val dimensionSlotRanges = mutableListOf<SlotRange>()
    private var totalSlots = 0

    // Maps slot index -> obelisk UUID (null = available)
    private val slotAssignments = mutableMapOf<Int, UUID?>()

    // Maps obelisk UUID -> assigned slot index
    private val obeliskToSlot = mutableMapOf<UUID, Int>()

    // Maps slot index -> prepared spawn platform position (null = needs generation)
    private val slotPlatforms = mutableMapOf<Int, BlockPos?>()

    // Maps slot index -> base type for quick lookup
    private val slotToBaseType = mutableMapOf<Int, DimensionBaseType>()

    /**
     * Initializes slot ranges based on loaded dimension configs.
     * Call this after ConfigManager.load()
     */
    fun initializeSlots() {
        dimensionSlotRanges.clear()
        slotToBaseType.clear()
        var currentSlotIndex = 0

        // Iterate through all dimension base types and their configs
        for (baseType in DimensionBaseType.entries) {
            val config = ConfigManager.getConfigForBaseType(baseType)
            if (config != null && config.enabled) {
                val slotCount = config.slotCount
                if (slotCount > 0) {
                    val range = SlotRange(baseType, currentSlotIndex, currentSlotIndex + slotCount)
                    dimensionSlotRanges.add(range)

                    // Map each slot to its base type
                    for (i in currentSlotIndex until currentSlotIndex + slotCount) {
                        slotToBaseType[i] = baseType
                    }

                    currentSlotIndex += slotCount
                    println("[$MOD_ID] Allocated slots ${range.start}-${range.end - 1} for ${baseType.name} (${slotCount} slots)")
                }
            }
        }

        totalSlots = currentSlotIndex
        println("[$MOD_ID] Total dimension slots allocated: $totalSlots")
    }

    /**
     * Attempts to assign an available slot to an obelisk for the specified dimension type.
     * Returns the slot index if successful, null if all slots of that type are full.
     */
    fun assignSlot(obeliskId: UUID, baseType: DimensionBaseType): Int? {
        // Check if this obelisk already has a slot
        obeliskToSlot[obeliskId]?.let { return it }

        // Find the slot range for this base type
        val range = dimensionSlotRanges.find { it.baseType == baseType }
        if (range == null) {
            println("[$MOD_ID] No slots configured for dimension type ${baseType.name}")
            return null
        }

        // Find first available slot of the correct type
        for (slotIndex in range.start until range.end) {
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
     * Also clears the platform position so it gets regenerated for the next use.
     */
    fun releaseSlot(obeliskId: UUID) {
        val slotIndex = obeliskToSlot.remove(obeliskId) ?: return
        slotAssignments[slotIndex] = null
        slotPlatforms[slotIndex] = null // Clear platform - will regenerate on next assignment
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
        val x = Random.nextInt(ObelisksConstants.SPAWN_POS_X_MIN, ObelisksConstants.SPAWN_POS_X_MAX)
        val z = Random.nextInt(ObelisksConstants.SPAWN_POS_Z_MIN, ObelisksConstants.SPAWN_POS_Z_MAX)

        // Y coordinate from config or fallback to constants
        val config = ConfigManager.getConfigForBaseType(baseType)
        val y = config?.spawnY ?: when (baseType) {
            DimensionBaseType.NETHER -> ObelisksConstants.SPAWN_POS_NETHER_Y
            DimensionBaseType.END -> ObelisksConstants.SPAWN_POS_END_Y
            DimensionBaseType.OVERWORLD -> 64
        }

        return BlockPos(x, y, z)
    }

    /**
     * Gets the dimension and spawn position for an obelisk's run.
     * If no platform exists for the slot, generates one immediately.
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

        // Get or generate platform position
        val platformPos = slotPlatforms[slotIndex] ?: run {
            println("[$MOD_ID] Generating platform for slot $slotIndex...")

            // Generate random search position
            val randomSearchPos = generateRandomSpawnPos(baseType)

            // Generate platform (this returns the player spawn position, Y+2 above platform)
            val spawnPos = SpawnPlatformGenerator.generateSpawnPlatform(dimension, baseType, randomSearchPos)

            // Store the spawn position for reuse
            slotPlatforms[slotIndex] = spawnPos
            println("[$MOD_ID] Platform generated and stored for slot $slotIndex at $spawnPos")

            spawnPos
        }

        return Pair(dimension, platformPos)
    }

    /**
     * Gets how many slots are currently in use.
     */
    fun getUsedSlotCount(): Int = slotAssignments.count { it.value != null }

    /**
     * Gets total number of available slots.
     */
    fun getTotalSlotCount(): Int = totalSlots

    /**
     * Debug: prints current slot assignments.
     */
    fun debugPrintSlots() {
        println("[$MOD_ID] Dimension Slot Status:")
        for (i in 0 until totalSlots) {
            val obelisk = slotAssignments[i]
            val baseType = slotToBaseType[i]?.name ?: "UNKNOWN"
            if (obelisk != null) {
                println("  Slot $i ($baseType): OCCUPIED by obelisk ${obelisk.toString().substring(0, 8)}")
            } else {
                println("  Slot $i ($baseType): AVAILABLE")
            }
        }
    }
}
