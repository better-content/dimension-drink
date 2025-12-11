package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.MOD_ID
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Initializes slot dimensions by forcing chunk loading at server startup.
 * This ensures the DistanceManager's chunk tracking is properly set up.
 */
object SlotDimensionInitializer {

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server
        println("[$MOD_ID] Initializing slot dimensions...")

        val totalSlots = DimensionSlotManager.getTotalSlotCount()
        for (slotIndex in 0 until totalSlots) {
            val dimension = DimensionSlotManager.getSlotDimension(server, slotIndex)
            if (dimension != null) {
                initializeDimension(dimension, slotIndex)
            } else {
                println("[$MOD_ID] Warning: Slot dimension $slotIndex not found!")
            }
        }

        println("[$MOD_ID] Slot dimension initialization complete!")
    }

    private fun initializeDimension(level: ServerLevel, slotIndex: Int) {
        try {
            // Force load spawn chunks around 0,0
            val spawnPos = BlockPos(0, 64, 0)
            val chunkX = spawnPos.x shr 4
            val chunkZ = spawnPos.z shr 4

            // Load a 3x3 area of chunks to initialize chunk tracking
            for (x in -1..1) {
                for (z in -1..1) {
                    level.getChunk(chunkX + x, chunkZ + z)
                }
            }

            println("[$MOD_ID] Initialized slot dimension $slotIndex (${level.dimension().location()})")
        } catch (e: Exception) {
            println("[$MOD_ID] Error initializing slot $slotIndex: ${e.message}")
            e.printStackTrace()
        }
    }
}
