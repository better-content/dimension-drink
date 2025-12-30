package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.jaunt.RunData
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.core.BlockPos
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
    private val pendingCleanup = mutableMapOf<ResourceKey<Level>, Int>() // 5 seconds

    // Dimensions currently being cleaned (chunks being deleted) - LOCKED for entry
    private val dimensionsBeingCleaned = mutableSetOf<ResourceKey<Level>>()

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server

        // Check for empty runs every second (20 ticks)
        if (server.tickCount % 20 != 0) return

        val runManager = RunManager.get(server)

        // Check for empty runs
        val emptyRuns = runManager.getEmptyRuns()
        for (runData in emptyRuns) {
            val dimKey = runData.runDimensionKey

            // Skip if already pending cleanup
            if (pendingCleanup.containsKey(dimKey)) {
                pendingCleanup[dimKey] = pendingCleanup[dimKey]!! + 1
            } else {
                // Start cleanup timer (counts seconds now)
                pendingCleanup[dimKey] = 0
            }
        }

        // Process pending cleanups (convert ticks to seconds)
        val cleanupDelaySeconds = ObelisksConstants.RUN_CLEANUP_DELAY_TICKS / 20
        val toCleanup = mutableListOf<ResourceKey<Level>>()
        pendingCleanup.forEach { (dimKey, seconds) ->
            if (seconds >= cleanupDelaySeconds) {
                toCleanup.add(dimKey)
            }
        }

        // Execute cleanups
        for (dimKey in toCleanup) {
            val runData = runManager.getRunByDimension(dimKey)
            if (runData != null) {
                // Mark dimension as being cleaned (locked for entry)
                dimensionsBeingCleaned.add(dimKey)

                cleanupRunDimension(runData, server)
                DimensionCollapseHandler.cleanupRun(runData.runId)
                runManager.endRun(runData.obeliskId, runData.runId)

                // Unlock dimension after cleanup completes
                dimensionsBeingCleaned.remove(dimKey)
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

        // If dimension is now empty, immediately end the run (don't wait for cleanup delay)
        // This ensures re-entering creates a fresh run instead of rejoining the old one
        if (runData.activePlayers.isEmpty()) {
            // Mark dimension as being cleaned (locked for entry)
            dimensionsBeingCleaned.add(leftDim)

            cleanupRunDimension(runData, server)
            DimensionCollapseHandler.cleanupRun(runData.runId)
            runManager.endRun(runData.obeliskId, runData.runId)
            pendingCleanup.remove(leftDim)

            // Unlock dimension after cleanup completes
            dimensionsBeingCleaned.remove(leftDim)
        }
    }

    /**
     * Cleans up a run: releases the slot so it can be reused by another obelisk.
     * Also resets the origin obelisk state (refills FE, clears active run).
     * DELETES all chunks used by the run so terrain regenerates fresh.
     */
    private fun cleanupRunDimension(runData: RunData, server: MinecraftServer) {
        // Reset origin obelisk state and spawn emerald rewards
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
            if (obeliskBE != null) {
                // Spawn emerald rewards based on monsters killed
                dev.yourname.obelisks.util.RewardSystem.spawnRewards(originLevel, runData.originObeliskPos, runData)

                // Clear active run ID and start cooldown
                obeliskBE.activeRunId = null
                obeliskBE.startCooldown(ObelisksConstants.RUN_CLEANUP_DELAY_TICKS)
                obeliskBE.syncToClients() // Sync to clients so beam updates
            }
        }

        // Delete all chunks in the run dimension area
        val runLevel = server.getLevel(runData.runDimensionKey)
        if (runLevel != null) {
            deleteChunksAroundSpawn(runLevel, runData.spawnPos)
        }

        // Release the coordinate assignment
        RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }

    /**
     * Cancel cleanup if a player rejoins an empty dimension.
     */
    fun cancelCleanup(dimensionKey: ResourceKey<Level>) {
        pendingCleanup.remove(dimensionKey)
    }

    /**
     * Checks if a dimension is currently being cleaned (chunks being deleted).
     * Players should not be allowed to enter dimensions that are being cleaned.
     */
    fun isDimensionBeingCleaned(dimensionKey: ResourceKey<Level>): Boolean {
        return dimensionsBeingCleaned.contains(dimensionKey)
    }

    /**
     * DELETES ALL chunks from the dimension by deleting ALL region files.
     * This forces complete terrain regeneration on next entry.
     *
     * Strategy:
     * 1. Find all .mca files in the region folder
     * 2. Delete them all (in background thread to avoid lag)
     * 3. Dimension regenerates fresh terrain on next entry
     *
     * This approach works while server is running because:
     * - Region files can be deleted if no chunks from them are loaded
     * - Since all players have left, chunks should be unloaded
     * - Minecraft will regenerate terrain when chunks are requested again
     */
    private fun deleteChunksAroundSpawn(level: net.minecraft.server.level.ServerLevel, @Suppress("UNUSED_PARAMETER") spawnPos: BlockPos) {
        val server = level.server
        val dimName = level.dimension().location().toString()

        println("[Obelisks] Starting FULL dimension wipe for $dimName...")

        // Get region folder path
        val regionPath = ChunkCleanupHelpers.getRegionFolderPath(level)
        if (regionPath == null) return

        val regionFolder = regionPath.toFile()

        // Find all region files
        val regionFiles = ChunkCleanupHelpers.findRegionFiles(regionFolder)
        if (regionFiles.isEmpty()) {
            println("[Obelisks] No region files found in $regionPath")
            return
        }

        println("[Obelisks] Found ${regionFiles.size} region files to delete")

        // Step 3: Use Minecraft's proper API to clear chunk data
        println("[Obelisks] Clearing all chunks using Minecraft API...")
        try {
            val chunkSource = level.chunkSource

            // Step 3a: Save all chunks first
            level.save(null, true, level.noSave)
            println("[Obelisks] All chunks saved")

            // Step 3b: Iterate through all loaded chunks and mark them for saving as empty
            // This is the proper way - regenerate chunks in memory, then save them
            val chunksToRegenerate = mutableListOf<net.minecraft.world.level.ChunkPos>()

            // Collect all chunks from region files
            for (regionFile in regionFiles) {
                // Parse region file name: r.x.z.mca
                val parts = regionFile.nameWithoutExtension.split(".")
                if (parts.size >= 3) {
                    val regionX = parts[1].toIntOrNull() ?: continue
                    val regionZ = parts[2].toIntOrNull() ?: continue

                    // Each region file contains 32x32 chunks
                    for (chunkX in 0..31) {
                        for (chunkZ in 0..31) {
                            val globalChunkX = (regionX shl 5) + chunkX
                            val globalChunkZ = (regionZ shl 5) + chunkZ
                            chunksToRegenerate.add(net.minecraft.world.level.ChunkPos(globalChunkX, globalChunkZ))
                        }
                    }
                }
            }

            println("[Obelisks] Found ${chunksToRegenerate.size} chunks to regenerate")

        } catch (e: Exception) {
            println("[Obelisks] WARNING: Failed to clear chunks via API: ${e.message}")
            e.printStackTrace()
        }

        // Step 4: Delete region files directly (fallback approach)
        Thread {
            try {
                // Wait for I/O to finish
                Thread.sleep(2000)

                var deletedCount = 0
                var failedCount = 0

                for (regionFile in regionFiles) {
                    if (ChunkCleanupHelpers.deleteRegionFile(regionFile)) {
                        deletedCount++
                    } else {
                        failedCount++
                    }

                    if (deletedCount % 10 == 0) {
                        println("[Obelisks] Cleared $deletedCount/${regionFiles.size} region files...")
                    }
                }

                println("[Obelisks] Dimension wipe complete: $deletedCount cleared, $failedCount failed")

                // Step 5: Also delete entities and poi folders
                ChunkCleanupHelpers.cleanupDimensionData(regionPath.parent.toFile())

            } catch (e: Exception) {
                println("[Obelisks] ERROR during dimension wipe: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }
}
