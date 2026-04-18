package dev.yourname.instanceddimensions.compat

import dev.yourname.instanceddimensions.events.PlayerInstanceTravelEvent
import dev.yourname.instanceddimensions.events.RuntimeDimensionTransitionEvent
import dev.yourname.instanceddimensions.events.RuntimeInstanceEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.eventbus.api.IEventBus

/**
 * Stable listener registration surface for mods that need to react to runtime
 * instance lifecycle and player transfer events without depending on manager
 * internals.
 */
object RuntimeDimensionEvents {

    fun onInstanceActivated(
        listener: (RuntimeInstanceEvent.Activated) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onInstanceUnloading(
        listener: (RuntimeInstanceEvent.Unloading) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onInstanceDestroyed(
        listener: (RuntimeInstanceEvent.Destroyed) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPlayerEnteringInstance(
        listener: (PlayerInstanceTravelEvent.Entering) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPlayerEnteredInstance(
        listener: (PlayerInstanceTravelEvent.Entered) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPlayerReturningInstance(
        listener: (PlayerInstanceTravelEvent.Returning) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPlayerReturnedInstance(
        listener: (PlayerInstanceTravelEvent.Returned) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPreRuntimeDimensionTransition(
        listener: (RuntimeDimensionTransitionEvent.Pre) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onPostRuntimeDimensionTransition(
        listener: (RuntimeDimensionTransitionEvent.Post) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }

    fun onRuntimeRespawned(
        listener: (RuntimeDimensionTransitionEvent.Respawned) -> Unit,
        bus: IEventBus = MinecraftForge.EVENT_BUS
    ) {
        bus.addListener(listener)
    }
}
