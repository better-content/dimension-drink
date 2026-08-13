package com.bettercontent.dimensiondrink.data

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

object CanonicalTargetResolver {
    fun targetId(definition: ObeliskDefinition): String {
        return definition.targetDimension?.trim()?.takeIf { it.isNotEmpty() }
            ?: definition.instanceTemplateId.trim().takeIf { it.isNotEmpty() }
            ?: definition.id
    }

    fun targetId(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.let(::targetId) ?: definitionId
    }

    fun targetLevelKey(definitionOrTargetId: String): ResourceKey<Level>? {
        val location = resourceLocationOrNull(targetId(definitionOrTargetId)) ?: return null
        return ResourceKey.create(Registries.DIMENSION, location)
    }

    fun coordinateScale(definition: ObeliskDefinition): Double {
        return definition.coordinateScale ?: defaultCoordinateScale(targetId(definition))
    }

    fun coordinateScale(definitionId: String): Double {
        return ObeliskDataManager.getObelisk(definitionId)?.let(::coordinateScale)
            ?: defaultCoordinateScale(definitionId)
    }

    fun defaultCoordinateScale(targetId: String): Double {
        return if (targetId == "minecraft:the_nether") 0.125 else 1.0
    }

    fun resourceLocationOrNull(raw: String): ResourceLocation? {
        return runCatching { ResourceLocation(raw) }.getOrNull()
    }

}
