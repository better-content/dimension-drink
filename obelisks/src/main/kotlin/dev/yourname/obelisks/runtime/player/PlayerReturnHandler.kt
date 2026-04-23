package dev.yourname.obelisks.runtime.player

import dev.yourname.obelisks.runtime.backend.RunBackendManager
import dev.yourname.obelisks.runtime.run.RunRegistry
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent

object PlayerReturnHandler {

    @SubscribeEvent
    fun onPlayerDeath(event: LivingDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        if (player.level().isClientSide) return

        RunRegistry.clearPlayerAssignment(player.server, player.uuid)
        RunBackendManager.backend.clearPlayer(player.uuid)
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onPlayerTick(event: TickEvent.PlayerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val player = event.player as? ServerPlayer ?: return
        if (player.level().isClientSide) return

        val run = RunRegistry.getRun(player.uuid) ?: return
        val record = RunRegistry.get(run.runId)

        if (record == null) {
            RunRegistry.clearPlayerAssignment(player.server, player.uuid)
            RunBackendManager.backend.clearPlayer(player.uuid)
            return
        }

        if (player.uuid in record.pendingPlayers) {
            return
        }

        val levelKey = record.backendLevelKey
        val bounds = record.backendSiteBounds
        if (levelKey == null || bounds == null || player.serverLevel().dimension() != levelKey || !bounds.contains(player.blockPosition())) {
            RunRegistry.clearPlayerAssignment(player.server, player.uuid)
            RunBackendManager.backend.clearPlayer(player.uuid)
        }
    }
}
