package dev.yourname.obelisks.server

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.dimension.DimensionCollapseHandler
import dev.yourname.obelisks.dimension.DimensionSlotManager
import dev.yourname.obelisks.run.RunBossBarManager
import dev.yourname.obelisks.run.RunManager
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
        println("[$MOD_ID] Server started - performing initialization checks")

        // Clear any stale dimension collapse states from previous server instance
        DimensionCollapseHandler.clearAllStates()
        println("[$MOD_ID] Cleared dimension collapse states")

        // Get the run manager and check for any persisted runs
        val runManager = RunManager.get(server)
        val activeRuns = runManager.getAllRuns().toList() // Copy to avoid ConcurrentModificationException

        if (activeRuns.isNotEmpty()) {
            println("[$MOD_ID] Found ${activeRuns.size} active runs from previous session")

            // End all runs on startup (they should not persist across server restarts)
            activeRuns.forEach { runData ->
                println("[$MOD_ID] Ending stale run ${runData.runId} for obelisk ${runData.obeliskId.toString().substring(0, 8)}")

                // Clean up the run
                DimensionCollapseHandler.cleanupRun(runData.runId)
                RunBossBarManager.removeBossBar(runData.runId)

                // Release the dimension slot
                DimensionSlotManager.releaseSlot(runData.obeliskId)

                // Reset the origin obelisk block entity
                val originLevel = server.getLevel(runData.originDimension)
                if (originLevel != null) {
                    val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos)
                        as? dev.yourname.obelisks.content.ObeliskBlockEntity
                    if (obeliskBE != null) {
                        obeliskBE.activeRunId = null
                        obeliskBE.setChanged()
                        println("[$MOD_ID] Reset obelisk at ${runData.originObeliskPos}")
                    } else {
                        println("[$MOD_ID] WARNING: Could not find obelisk block entity at ${runData.originObeliskPos}")
                    }
                } else {
                    println("[$MOD_ID] WARNING: Origin dimension ${runData.originDimension.location()} not found")
                }

                // End the run in the manager
                runManager.endRun(runData.obeliskId, runData.runId)
            }

            println("[$MOD_ID] Cleaned up ${activeRuns.size} stale runs")
        }

        println("[$MOD_ID] Server initialization complete")
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        val server = event.server
        println("[$MOD_ID] Server stopping - performing cleanup")

        // Get all active runs
        val runManager = RunManager.get(server)
        val activeRuns = runManager.getAllRuns().toList() // Copy to avoid ConcurrentModificationException

        if (activeRuns.isNotEmpty()) {
            println("[$MOD_ID] Cleaning up ${activeRuns.size} active runs before shutdown")

            // End all runs gracefully
            activeRuns.forEach { runData ->
                println("[$MOD_ID] Ending run ${runData.runId} for obelisk ${runData.obeliskId.toString().substring(0, 8)}")

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
                            println("[$MOD_ID] Returned player ${player.gameProfile.name} to origin")
                        }
                    }
                }

                // Clean up dimension collapse state
                DimensionCollapseHandler.cleanupRun(runData.runId)

                // Remove boss bar
                RunBossBarManager.removeBossBar(runData.runId)

                // Release the dimension slot
                DimensionSlotManager.releaseSlot(runData.obeliskId)

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

            println("[$MOD_ID] Cleaned up ${activeRuns.size} active runs")
        }

        // Clear all dimension collapse states
        DimensionCollapseHandler.clearAllStates()

        println("[$MOD_ID] Server cleanup complete")
    }
}
