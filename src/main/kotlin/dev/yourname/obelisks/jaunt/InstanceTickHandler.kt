package dev.yourname.obelisks.jaunt

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles per-tick FE drain from origin obelisks based on active players in run dimensions.
 * Phase 3: FE System
 */
object InstanceTickHandler {

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Process each active run
        for (runData in runManager.getAllRuns()) {
            // Count players in this run's dimension
            val playerCount = runData.activePlayers.size

            if (playerCount == 0) {
                // No players, no drain (cleanup handler will handle empty runs)
                continue
            }

            // Increment tick counter
            runData.ticksElapsed++

            // Update drain multiplier based on exponential growth (dimension-specific)
            // Get dimension-specific config
            val dimConfig = dev.yourname.obelisks.config.ConfigManager.getDimensionConfig(runData.dimensionId)
            val exponentialInterval = (dimConfig?.drainExponentialIntervalTicks ?: ObelisksConstants.DRAIN_EXPONENTIAL_INTERVAL_TICKS).coerceAtLeast(1)

            if (runData.ticksElapsed % exponentialInterval == 0L) {
                // Exponential formula: multiplier = e^(factor * ticks)
                val factor = dimConfig?.drainExponentialFactor ?: ObelisksConstants.DRAIN_EXPONENTIAL_FACTOR
                runData.drainMultiplier = Math.exp(factor * runData.ticksElapsed)
            }

            // Get origin obelisk BlockEntity
            val originLevel = server.getLevel(runData.originDimension)
            if (originLevel == null) {
                // Origin dimension not loaded - this shouldn't happen for overworld
                continue
            }

            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity
            if (obeliskBE == null) {
                // Obelisk no longer exists - force end the run
                forceEndRun(server, runData)
                continue
            }

            // Calculate FE drain for this tick with exponential multiplier
            // Use modified values from the obelisk's modifiers
            val baseDrain = obeliskBE.getModifiedBaseDrain() +
                           (playerCount * obeliskBE.getModifiedPlayerDrain())
            val drainAmount = (baseDrain * runData.drainMultiplier).toInt()

            // Drain FE
            val success = obeliskBE.drainEnergy(drainAmount)

            if (!success) {
                // FE depleted to 0% - trigger forced collapse
                forceCollapseRun(server, runData)
            }
        }
    }

    /**
     * Forces a run to end when FE reaches 0%.
     * Returns all players to their origin obelisks.
     */
    private fun forceCollapseRun(server: net.minecraft.server.MinecraftServer, runData: RunData) {
        // Get all players in this run
        val playersToReturn = runData.activePlayers.toList()

        // Return each player
        for (playerId in playersToReturn) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                dev.yourname.obelisks.player.PlayerReturnHandler.returnPlayerToOrigin(
                    player,
                    "Dimension collapsed - FE depleted to 0%!"
                )
            }
        }

        // Mark the obelisk as no longer having an active run and spawn rewards
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity
            if (obeliskBE != null) {
                // Spawn completion rewards (players survived and were force-returned)
                dev.yourname.obelisks.util.RewardSystem.spawnRewards(originLevel, runData.originObeliskPos, runData)
                
                obeliskBE.activeRunId = null
                obeliskBE.setChanged()
            }
        }

        // CRITICAL: End the run in RunManager to trigger cleanup (boss bar, etc.)
        val runManager = RunManager.get(server)
        runManager.endRun(runData.obeliskId, runData.runId)

        // Release the coordinate assignment
        dev.yourname.obelisks.dimension.RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }

    /**
     * Forces a run to end when the origin obelisk is destroyed.
     */
    private fun forceEndRun(server: net.minecraft.server.MinecraftServer, runData: RunData) {
        // Similar to forceCollapseRun but with different message
        val playersToReturn = runData.activePlayers.toList()

        for (playerId in playersToReturn) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                dev.yourname.obelisks.player.PlayerReturnHandler.returnPlayerToOrigin(
                    player,
                    "Origin obelisk was destroyed!"
                )
            }
        }

        // CRITICAL: End the run in RunManager to trigger cleanup (boss bar, etc.)
        val runManager = RunManager.get(server)
        runManager.endRun(runData.obeliskId, runData.runId)

        // Release the coordinate assignment
        dev.yourname.obelisks.dimension.RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)
    }
}
