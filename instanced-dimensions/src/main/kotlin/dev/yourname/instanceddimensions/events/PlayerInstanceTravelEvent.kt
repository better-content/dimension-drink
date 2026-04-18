package dev.yourname.instanceddimensions.events

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import dev.yourname.instanceddimensions.engine.travel.PlayerReturnAnchor
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.eventbus.api.Cancelable
import net.minecraftforge.eventbus.api.Event

open class PlayerInstanceTravelEvent(
    val player: ServerPlayer,
    val instance: InstanceHandle,
    val returnAnchor: PlayerReturnAnchor?
) : Event() {

    @Cancelable
    class Entering(player: ServerPlayer, instance: InstanceHandle, returnAnchor: PlayerReturnAnchor) :
        PlayerInstanceTravelEvent(player, instance, returnAnchor)

    class Entered(player: ServerPlayer, instance: InstanceHandle, returnAnchor: PlayerReturnAnchor) :
        PlayerInstanceTravelEvent(player, instance, returnAnchor)

    @Cancelable
    class Returning(player: ServerPlayer, instance: InstanceHandle, returnAnchor: PlayerReturnAnchor) :
        PlayerInstanceTravelEvent(player, instance, returnAnchor)

    class Returned(player: ServerPlayer, instance: InstanceHandle, returnAnchor: PlayerReturnAnchor?) :
        PlayerInstanceTravelEvent(player, instance, returnAnchor)
}
