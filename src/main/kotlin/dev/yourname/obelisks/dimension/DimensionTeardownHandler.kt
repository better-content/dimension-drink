package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.run.RunData
import dev.yourname.obelisks.run.RunManager
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles safe teardown of run dimensions when they become empty.
 */
object DimensionTeardownHandler {

    // Map of dimension keys pending cleanup -> tick count
    private val pendingCleanup = mutableMapOf<ResourceKey<Level>, Int>()
    private const val CLEANUP_DELAY_TICKS = 100 // 5 seconds

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Check for empty runs
        val emptyRuns = runManager.getEmptyRuns()
        for (runData in emptyRuns) {
            val dimKey = runData.runDimensionKey

            // Skip if already pending cleanup
            if (pendingCleanup.containsKey(dimKey)) {
                pendingCleanup[dimKey] = pendingCleanup[dimKey]!! + 1
            } else {
                // Start cleanup timer
                pendingCleanup[dimKey] = 0
            }
        }

        // Process pending cleanups
        val toCleanup = mutableListOf<ResourceKey<Level>>()
        pendingCleanup.forEach { (dimKey, ticks) ->
            if (ticks >= CLEANUP_DELAY_TICKS) {
                toCleanup.add(dimKey)
            }
        }

        // Execute cleanups
        for (dimKey in toCleanup) {
            val runData = runManager.getRunByDimension(dimKey)
            if (runData != null) {
                cleanupRunDimension(runData, server)
                runManager.endRun(runData.obeliskId, runData.runId)
            }
            pendingCleanup.remove(dimKey)
        }
    }

    @SubscribeEvent
    fun onPlayerChangeDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        // When a player leaves a run dimension, check if it's now empty
        val player = event.entity
        if (player.level().isClientSide) return

        val server = player.server ?: return
        val runManager = RunManager.get(server)

        // Check if the dimension they left is a run dimension
        val leftDim = event.from
        val runData = runManager.getRunByDimension(leftDim) ?: return

        // If dimension is now empty, cancel any pending cleanup and restart timer
        if (runData.activePlayers.isEmpty()) {
            pendingCleanup[leftDim] = 0
        }
    }

    /**
     * Cleans up a run: releases the slot so it can be reused by another obelisk.
     * Also resets the origin obelisk state (refills FE, clears active run).
     */
    private fun cleanupRunDimension(runData: RunData, server: MinecraftServer) {
        val dimKey = runData.runDimensionKey

        println("[$MOD_ID] Cleaning up run for obelisk ${runData.obeliskId.toString().substring(0, 8)}: ${dimKey.location()}")

        // Reset origin obelisk state
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
            if (obeliskBE != null) {
                // Clear active run ID so obelisk can be reused
                obeliskBE.activeRunId = null
                obeliskBE.setChanged()
                println("[$MOD_ID] Reset obelisk: cleared active run (FE will regenerate naturally at ${dev.yourname.obelisks.config.ObelisksConfig.FE_REGEN_PER_TICK} FE/tick)")
            } else {
                println("[$MOD_ID] Warning: Could not find origin obelisk to reset")
            }
        }

        // Release the slot assignment
        DimensionSlotManager.releaseSlot(runData.obeliskId)

        // Note: We DON'T unload or delete slot dimensions - they persist and get reused
        println("[$MOD_ID] Run cleanup complete - slot released for reuse")
    }

    // Note: The following methods are no longer needed with the slot-based dimension system.
    // Slot dimensions are persistent and never unloaded or deleted - they are simply released
    // for reuse by other obelisks. This greatly simplifies cleanup and avoids the complexity
    // of dynamic dimension management.

    /**
     * Cancel cleanup if a player rejoins an empty dimension.
     */
    fun cancelCleanup(dimensionKey: ResourceKey<Level>) {
        pendingCleanup.remove(dimensionKey)
    }
}
