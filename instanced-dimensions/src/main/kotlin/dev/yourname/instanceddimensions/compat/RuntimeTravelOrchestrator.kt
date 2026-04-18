package dev.yourname.instanceddimensions.compat

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.travel.PlayerReturnAnchor
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import dev.yourname.instanceddimensions.events.RuntimeDimensionTransitionEvent
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

/**
 * Bridges vanilla and modded dimension changes back into the runtime instance
 * layer so external travel does not bypass return-anchor and lifecycle state.
 */
object RuntimeTravelOrchestrator {

    private val pendingTransitions = linkedMapOf<UUID, PendingTransition>()

    @SubscribeEvent
    fun onEntityTravelToDimension(event: EntityTravelToDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val fromLevel = player.serverLevel().dimension()
        val toLevel = event.dimension
        val fromInstance = InstanceManager.getInstance(fromLevel)
        val toInstance = InstanceManager.getInstance(toLevel)
        if (fromInstance == null && toInstance == null) {
            return
        }

        val returnAnchor = when {
            toInstance != null && fromInstance == null -> {
                TravelManager.peekReturnAnchor(player.uuid) ?: captureAnchor(player)
            }
            else -> TravelManager.peekReturnAnchor(player.uuid)
        }

        if (toInstance != null && !InstanceManager.isTravelReady(toInstance.id)) {
            event.isCanceled = true
            return
        }

        val preEvent = RuntimeDimensionTransitionEvent.Pre(
            player = player,
            fromLevel = fromLevel,
            toLevel = toLevel,
            fromInstance = fromInstance,
            toInstance = toInstance,
            returnAnchor = returnAnchor
        )
        if (MinecraftForge.EVENT_BUS.post(preEvent)) {
            event.isCanceled = true
            return
        }

        pendingTransitions[player.uuid] = PendingTransition(
            fromLevel = fromLevel,
            toLevel = toLevel,
            fromInstance = fromInstance,
            toInstance = toInstance,
            returnAnchor = preEvent.returnAnchor
        )
    }

    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val pending = pendingTransitions.remove(player.uuid)
        val fromLevel = pending?.fromLevel ?: event.from
        val toLevel = pending?.toLevel ?: event.to
        val fromInstance = pending?.fromInstance ?: InstanceManager.getInstance(event.from)
        val toInstance = pending?.toInstance ?: InstanceManager.getInstance(event.to)
        if (fromInstance == null && toInstance == null) {
            return
        }

        val anchor = pending?.returnAnchor ?: TravelManager.peekReturnAnchor(player.uuid)
        if (toInstance != null && fromInstance == null && anchor != null) {
            TravelManager.rememberReturnAnchor(player.uuid, anchor)
        }
        if (fromInstance != null && toInstance == null) {
            TravelManager.clearReturnAnchor(player.uuid)
        }

        MinecraftForge.EVENT_BUS.post(
            RuntimeDimensionTransitionEvent.Post(
                player = player,
                fromLevel = fromLevel,
                toLevel = toLevel,
                fromInstance = fromInstance,
                toInstance = toInstance,
                returnAnchor = anchor
            )
        )
    }

    @SubscribeEvent
    fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val currentLevel = player.serverLevel().dimension()
        val currentInstance = InstanceManager.getInstance(currentLevel)
        val anchor = TravelManager.peekReturnAnchor(player.uuid)
        if (currentInstance == null && anchor != null) {
            TravelManager.clearReturnAnchor(player.uuid)
        }
        if (currentInstance != null || anchor != null) {
            MinecraftForge.EVENT_BUS.post(
                RuntimeDimensionTransitionEvent.Respawned(
                    player = player,
                    toLevel = currentLevel,
                    toInstance = currentInstance,
                    returnAnchor = anchor
                )
            )
        }
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        pendingTransitions.clear()
    }

    private fun captureAnchor(player: ServerPlayer): PlayerReturnAnchor {
        return PlayerReturnAnchor(
            levelKey = player.serverLevel().dimension(),
            x = player.x,
            y = player.y,
            z = player.z,
            yRot = player.yRot,
            xRot = player.xRot
        )
    }

    private data class PendingTransition(
        val fromLevel: ResourceKey<Level>,
        val toLevel: ResourceKey<Level>,
        val fromInstance: InstanceHandle?,
        val toInstance: InstanceHandle?,
        val returnAnchor: PlayerReturnAnchor?
    )
}
