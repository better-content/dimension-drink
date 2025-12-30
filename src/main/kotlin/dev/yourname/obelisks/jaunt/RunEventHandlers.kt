package dev.yourname.obelisks.jaunt

import dev.yourname.obelisks.player.PlayerReturnHandler
import dev.yourname.obelisks.player.getRunInfo
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles player login/logout events for run persistence.
 */
object RunEventHandlers {

    @SubscribeEvent
    fun onPlayerLogIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val runInfo = player.getRunInfo() ?: return

        // Check if player was in a run when they logged out
        if (runInfo.isInRun()) {
            val server = player.server
            val runManager = RunManager.get(server)

            // For Phase 2 MVP: Just return player to origin rather than resume run
            // In a more advanced version, we could recreate the dimension and put them back

            player.sendSystemMessage(
                Component.literal("Your previous run has ended. Returning to origin...")
            )

            // Important: Wait 1 tick before teleporting to ensure player is fully loaded
            server.execute {
                PlayerReturnHandler.returnPlayerToOrigin(player, "Session expired")
            }
        }
    }

    @SubscribeEvent
    fun onPlayerLogOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val runInfo = player.getRunInfo() ?: return

        if (runInfo.isInRun()) {
            // Remove player from run tracking
            val server = player.server
            val runManager = RunManager.get(server)
            runManager.removePlayerFromRun(player.uuid)

            // Note: We keep their runInfo data so they can be returned on login
            // The dimension will be cleaned up if it becomes empty
        }
    }
}
