package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.util.Result
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/**
 * Coordinator for all dimension entry/exit operations.
 * Provides ACID-like guarantees: operations are atomic, consistent, isolated, and durable.
 *
 * All dimension teleportation should go through this coordinator.
 */
object DimensionCoordinator {

    private val commandLog = mutableListOf<DimensionEvent>()
    private const val MAX_LOG_SIZE = 1000

    /**
     * Enter a dimension (player clicks obelisk).
     * Returns success or failure with rollback on any error.
     */
    fun enterDimension(
        player: ServerPlayer,
        obelisk: ObeliskBlockEntity,
        obeliskPos: BlockPos,
        level: ServerLevel
    ): Result<Unit> {
        val command = EnterDimensionCommand(player, obelisk, obeliskPos, level)
        return command.execute()
            .onSuccess { event ->
                logEvent(event)
            }
            .onFailure { error, cause ->
                println("[Obelisks] EnterDimension failed: $error")
                cause?.printStackTrace()
            }
            .map { } // Convert Result<DimensionEvent> to Result<Unit>
    }

    /**
     * Exit a dimension (player returns to origin).
     * Returns success or failure with rollback on any error.
     */
    fun exitDimension(player: ServerPlayer, reason: String): Result<Unit> {
        val command = ExitDimensionCommand(player, reason)
        return command.execute()
            .onSuccess { event ->
                logEvent(event)
            }
            .onFailure { error, cause ->
                println("[Obelisks] ExitDimension failed: $error")
                cause?.printStackTrace()
            }
            .map { } // Convert Result<DimensionEvent> to Result<Unit>
    }

    /**
     * Force return all players in a run (used for 0% energy collapse).
     */
    fun forceReturnAllPlayers(
        runData: dev.yourname.obelisks.jaunt.RunData,
        server: net.minecraft.server.MinecraftServer,
        reason: String
    ) {
        val playersToReturn = runData.activePlayers.toList()

        for (playerId in playersToReturn) {
            val player = server.playerList.getPlayer(playerId)
            if (player != null) {
                exitDimension(player, reason)
                    .onFailure { error, _ ->
                        println("[Obelisks] Failed to force return player ${player.gameProfile.name}: $error")
                    }
            }
        }
    }

    /**
     * Get command execution history (for debugging).
     */
    fun getCommandHistory(): List<DimensionEvent> = commandLog.toList()

    /**
     * Clear command history.
     */
    fun clearHistory() {
        commandLog.clear()
    }

    private fun logEvent(event: DimensionEvent) {
        commandLog.add(event)

        // Keep log size bounded
        if (commandLog.size > MAX_LOG_SIZE) {
            commandLog.removeAt(0)
        }

        // Log to console
        println("[Obelisks] ${event.commandType}: ${event.playerName} - ${event.details}")
    }
}
