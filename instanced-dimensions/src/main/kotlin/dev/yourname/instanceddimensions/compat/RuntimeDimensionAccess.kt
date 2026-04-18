package dev.yourname.instanceddimensions.compat

import dev.yourname.instanceddimensions.engine.instance.InstanceHandle
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.instance.InstanceTemplate
import dev.yourname.instanceddimensions.engine.travel.PlayerReturnAnchor
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Stable query surface for other mods that need to reason about runtime dimensions
 * without depending on internal manager wiring.
 */
object RuntimeDimensionAccess {

    fun isC2meLoaded(): Boolean {
        return C2meCompat.isLoaded()
    }

    fun templates(): Collection<InstanceTemplate> {
        return InstanceManager.templates()
    }

    fun getTemplate(templateId: String): InstanceTemplate? {
        return InstanceManager.getTemplate(templateId)
    }

    fun allInstances(): Collection<InstanceHandle> {
        return InstanceManager.allInstances()
    }

    fun getInstance(instanceId: UUID): InstanceHandle? {
        return InstanceManager.getInstance(instanceId)
    }

    fun getInstance(levelKey: ResourceKey<Level>): InstanceHandle? {
        return InstanceManager.getInstance(levelKey)
    }

    fun getInstance(level: ServerLevel): InstanceHandle? {
        return getInstance(level.dimension())
    }

    fun isRuntimeLevel(levelKey: ResourceKey<Level>): Boolean {
        return InstanceManager.isRuntimeLevel(levelKey)
    }

    fun isRuntimeLevel(level: ServerLevel): Boolean {
        return isRuntimeLevel(level.dimension())
    }

    fun getReturnAnchor(playerId: UUID): PlayerReturnAnchor? {
        return TravelManager.peekReturnAnchor(playerId)
    }

    fun hasReturnAnchor(playerId: UUID): Boolean {
        return TravelManager.hasReturnAnchor(playerId)
    }
}
