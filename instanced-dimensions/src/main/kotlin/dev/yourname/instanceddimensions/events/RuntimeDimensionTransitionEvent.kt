package dev.yourname.instanceddimensions.events

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import dev.yourname.instanceddimensions.engine.travel.PlayerReturnAnchor
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.Cancelable
import net.minecraftforge.eventbus.api.Event

open class RuntimeDimensionTransitionEvent(
    val player: ServerPlayer,
    val fromLevel: ResourceKey<Level>?,
    val toLevel: ResourceKey<Level>,
    val fromInstance: InstanceHandle?,
    val toInstance: InstanceHandle?,
    val returnAnchor: PlayerReturnAnchor?
) : Event() {

    @Cancelable
    class Pre(
        player: ServerPlayer,
        fromLevel: ResourceKey<Level>,
        toLevel: ResourceKey<Level>,
        fromInstance: InstanceHandle?,
        toInstance: InstanceHandle?,
        returnAnchor: PlayerReturnAnchor?
    ) : RuntimeDimensionTransitionEvent(player, fromLevel, toLevel, fromInstance, toInstance, returnAnchor)

    class Post(
        player: ServerPlayer,
        fromLevel: ResourceKey<Level>,
        toLevel: ResourceKey<Level>,
        fromInstance: InstanceHandle?,
        toInstance: InstanceHandle?,
        returnAnchor: PlayerReturnAnchor?
    ) : RuntimeDimensionTransitionEvent(player, fromLevel, toLevel, fromInstance, toInstance, returnAnchor)

    class Respawned(
        player: ServerPlayer,
        toLevel: ResourceKey<Level>,
        toInstance: InstanceHandle?,
        returnAnchor: PlayerReturnAnchor?
    ) : RuntimeDimensionTransitionEvent(player, null, toLevel, null, toInstance, returnAnchor)
}
