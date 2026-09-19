package com.bettercontent.dimensiondrink.runtime.player

import com.bettercontent.dimensiondrink.runtime.backend.RunBackendManager
import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent

object PlayerReturnHandler {
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
        if (levelKey == null || bounds == null) {
            if (!RunRegistry.returnPlayer(player)) RunRegistry.clearPlayerAssignment(player.server, player.uuid)
            return
        }

        if (player.serverLevel().dimension() != levelKey) {
            // Aether fall-out and other exits are extraction paths. Let the registry
            // confirm transport before consuming the participant binding.
            if (!RunRegistry.returnPlayer(player)) RunRegistry.clearPlayerAssignment(player.server, player.uuid)
            return
        }

        if (!bounds.contains(player.blockPosition())) {
            if (!RunRegistry.returnPlayer(player)) RunRegistry.clearPlayerAssignment(player.server, player.uuid)
        }
    }
}
