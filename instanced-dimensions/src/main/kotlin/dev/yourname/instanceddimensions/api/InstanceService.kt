package dev.yourname.instanceddimensions.api

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import dev.yourname.instanceddimensions.engine.instance.InstanceTemplate
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import java.util.UUID

interface InstanceService {
    fun templates(): Collection<InstanceTemplate>
    fun getTemplate(templateId: String): InstanceTemplate?
    fun registerTemplate(template: InstanceTemplate)
    fun allInstances(): Collection<InstanceHandle>
    fun getInstance(id: UUID): InstanceHandle?
    fun getInstance(levelKey: ResourceKey<Level>): InstanceHandle?
    fun isRuntimeLevel(levelKey: ResourceKey<Level>): Boolean
    fun createInstance(server: MinecraftServer, templateId: String, ownerId: UUID? = null): InstanceCreateResult
    fun scheduleDestroy(server: MinecraftServer, id: UUID): Boolean
}
