package dev.yourname.obelisks.server

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.dimension.DimensionCollapseHandler
import dev.yourname.obelisks.dimension.RunCoordinateManager
import dev.yourname.obelisks.jaunt.RunBossBarManager
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles server lifecycle events (startup/shutdown) to properly clean up runs and dimensions.
 */
object ServerLifecycleHandler {

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        val server = event.server

        // Clear any stale dimension collapse states from previous server instance
        DimensionCollapseHandler.clearAllStates()

        // Get the run manager and check for any persisted runs
        val runManager = RunManager.get(server)
        val activeRuns = runManager.getAllRuns().toList() // Copy to avoid ConcurrentModificationException

        if (activeRuns.isNotEmpty()) {
            // End all runs on startup (they should not persist across server restarts)
            activeRuns.forEach { runData ->
                // Clean up the run
                DimensionCollapseHandler.cleanupRun(runData.runId)
                RunBossBarManager.removeBossBar(runData.runId)

                // Release the coordinate assignment
                RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)

                // Reset the origin obelisk block entity
                val originLevel = server.getLevel(runData.originDimension)
                if (originLevel != null) {
                    val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos)
                        as? dev.yourname.obelisks.content.ObeliskBlockEntity
                    if (obeliskBE != null) {
                        obeliskBE.activeRunId = null
                        obeliskBE.setChanged()
                    }
                }

                // End the run in the manager
                runManager.endRun(runData.obeliskId, runData.runId)
            }
        }
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        val server = event.server

        // Get all active runs
        val runManager = RunManager.get(server)
        val activeRuns = runManager.getAllRuns().toList() // Copy to avoid ConcurrentModificationException

        if (activeRuns.isNotEmpty()) {
            // End all runs gracefully
            activeRuns.forEach { runData ->
                // Return all players to their origin obelisk
                runData.activePlayers.forEach { playerId ->
                    val player = server.playerList.getPlayer(playerId)
                    if (player != null) {
                        // Teleport player back to origin obelisk position
                        val originLevel = server.getLevel(runData.originDimension)
                        if (originLevel != null) {
                            val obeliskPos = runData.originObeliskPos
                            player.teleportTo(
                                originLevel,
                                obeliskPos.x + 0.5,
                                obeliskPos.y + 1.0,
                                obeliskPos.z + 0.5,
                                player.yRot,
                                player.xRot
                            )
                        }
                    }
                }

                // Clean up dimension collapse state
                DimensionCollapseHandler.cleanupRun(runData.runId)

                // Remove boss bar
                RunBossBarManager.removeBossBar(runData.runId)

                // Release the coordinate assignment
                RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)

                // Reset the origin obelisk
                val originLevel = server.getLevel(runData.originDimension)
                if (originLevel != null) {
                    val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos)
                        as? dev.yourname.obelisks.content.ObeliskBlockEntity
                    if (obeliskBE != null) {
                        obeliskBE.activeRunId = null
                        obeliskBE.setChanged()
                    }
                }

                // End the run in the manager
                runManager.endRun(runData.obeliskId, runData.runId)
            }
        }

        // Clear all dimension collapse states
        DimensionCollapseHandler.clearAllStates()
    }
}
