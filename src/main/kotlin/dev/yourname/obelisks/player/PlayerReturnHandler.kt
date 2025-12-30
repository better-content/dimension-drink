package dev.yourname.obelisks.player

import dev.yourname.obelisks.dimension.DimensionCoordinator
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles player return mechanics from run dimensions.
 * Now delegates to DimensionCoordinator for ACID-like transactions.
 */
object PlayerReturnHandler {

    private const val VOID_FALL_Y = -64.0

    @SubscribeEvent
    fun onPlayerDeath(event: LivingDeathEvent) {
        val entity = event.entity
        if (entity !is ServerPlayer) return
        if (entity.level().isClientSide) return

        val playerRunInfo = entity.getRunInfo() ?: return
        if (!playerRunInfo.isInRun()) return


        // Clean up run info
        val server = entity.server
        val runManager = RunManager.get(server)
        runManager.removePlayerFromRun(entity.uuid)

        // Clear capability data
        playerRunInfo.clear()

    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        if (event.player.level().isClientSide) return

        val player = event.player as? ServerPlayer ?: return
        val playerRunInfo = player.getRunInfo() ?: return

        // Check if player is in a run dimension
        if (!playerRunInfo.isInRun()) return

        // CRITICAL: Verify the run still exists in RunManager
        val server = player.server
        val runManager = RunManager.get(server)
        val runData = runManager.getPlayerRun(player.uuid)

        if (runData == null) {
            // Run no longer exists but player still has run info - clear it
            playerRunInfo.clear()
            return
        }

        // CRITICAL: Verify player is actually in the run dimension
        if (player.level().dimension() != runData.runDimensionKey) {
            // Player is not in run dimension but has run info - they've already been returned
            playerRunInfo.clear()
            runManager.removePlayerFromRun(player.uuid)
            return
        }

        // Check for void fall
        if (player.y < VOID_FALL_Y) {
            returnPlayerToOrigin(player, "Fell into the void")
        }
    }

    /**
     * Returns a player to their origin obelisk location.
     * Now uses DimensionCoordinator for transactional safety.
     */
    fun returnPlayerToOrigin(player: ServerPlayer, reason: String) {
        DimensionCoordinator.exitDimension(player, reason)
            .onFailure { error, _ ->
                // Fallback: clear their run info to prevent stuck state
                player.getRunInfo()?.clear()
            }
    }

    /**
     * Public API for manually returning a player (e.g., from a return pad block).
     */
    fun returnPlayer(player: ServerPlayer) {
        val playerRunInfo = player.getRunInfo()

        if (playerRunInfo == null || !playerRunInfo.isInRun()) {
            player.sendSystemMessage(Component.literal("You are not in a run!"))
            return
        }

        returnPlayerToOrigin(player, "Used return mechanism")
    }

    /**
     * Forces all players in a run to return to their origin (used for 0% FE collapse).
     * Now uses DimensionCoordinator.
     */
    fun forceReturnAllPlayersInRun(
        runData: dev.yourname.obelisks.jaunt.RunData,
        server: net.minecraft.server.MinecraftServer,
        reason: String
    ) {
        DimensionCoordinator.forceReturnAllPlayers(runData, server, reason)
    }
}
