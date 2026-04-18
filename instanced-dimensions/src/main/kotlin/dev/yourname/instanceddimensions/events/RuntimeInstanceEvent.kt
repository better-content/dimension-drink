package dev.yourname.instanceddimensions.events

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.eventbus.api.Event

open class RuntimeInstanceEvent(
    val server: MinecraftServer,
    val instance: InstanceHandle,
    val level: ServerLevel?
) : Event() {

    class Activated(server: MinecraftServer, instance: InstanceHandle, level: ServerLevel) :
        RuntimeInstanceEvent(server, instance, level)

    class Unloading(server: MinecraftServer, instance: InstanceHandle, level: ServerLevel) :
        RuntimeInstanceEvent(server, instance, level)

    class Destroyed(server: MinecraftServer, instance: InstanceHandle) :
        RuntimeInstanceEvent(server, instance, null)
}
