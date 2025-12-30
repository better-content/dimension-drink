package dev.yourname.obelisks.server

import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.dimension.DimensionCollapseHandler
import dev.yourname.obelisks.dimension.RunCoordinateManager
import dev.yourname.obelisks.jaunt.RunBossBarManager
import dev.yourname.obelisks.jaunt.RunManager
import dev.yourname.obelisks.player.getRunInfo
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent
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

    /**
     * When a player logs in, check if they have run info from a previous session.
     * If they do, return them to their obelisk origin since runs don't persist across logins.
     */
    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val playerRunInfo = player.getRunInfo() ?: return

        // Check if player has stale run info from before logout/server restart
        if (playerRunInfo.isInRun()) {
            val originDimension = playerRunInfo.originDimension
            val originPos = playerRunInfo.originPos

            if (originDimension != null && originPos != null) {
                val server = player.server
                val originLevel = server.getLevel(originDimension)

                if (originLevel != null) {
                    // Teleport player to their origin obelisk
                    player.teleportTo(
                        originLevel,
                        originPos.x.toDouble() + 0.5,
                        originPos.y.toDouble(),
                        originPos.z.toDouble() + 0.5,
                        player.yRot,
                        player.xRot
                    )

                    player.sendSystemMessage(
                        Component.literal("§eReturned to obelisk (logged out during run)")
                    )
                }
            }

            // Clear the stale run info
            playerRunInfo.clear()
        }
    }

    /**
     * When a player logs out while in a run, return them to their obelisk origin.
     * This prevents players from logging out in dangerous run dimensions.
     */
    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val playerRunInfo = player.getRunInfo() ?: return

        // Check if player is currently in a run
        if (playerRunInfo.isInRun()) {
            val originDimension = playerRunInfo.originDimension
            val originPos = playerRunInfo.originPos

            if (originDimension != null && originPos != null) {
                val server = player.server
                val originLevel = server.getLevel(originDimension)
                val runManager = RunManager.get(server)

                if (originLevel != null) {
                    // Teleport player to their origin obelisk before they disconnect
                    player.teleportTo(
                        originLevel,
                        originPos.x.toDouble() + 0.5,
                        originPos.y.toDouble(),
                        originPos.z.toDouble() + 0.5,
                        player.yRot,
                        player.xRot
                    )
                }

                // Remove player from run tracking
                runManager.removePlayerFromRun(player.uuid)

                // Clear run info (will be persisted to NBT for safety on next login)
                playerRunInfo.clear()
            }
        }
    }
}
