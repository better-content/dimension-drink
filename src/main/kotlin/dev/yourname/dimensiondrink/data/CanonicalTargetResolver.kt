package dev.yourname.dimensiondrink.data

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

object CanonicalTargetResolver {
    fun targetId(definition: ObeliskDefinition): String {
        return definition.targetDimension?.trim()?.takeIf { it.isNotEmpty() }
            ?: legacyTargetDimension(definition.instanceTemplateId.trim().takeIf { it.isNotEmpty() } ?: definition.id)
    }

    fun targetId(definitionId: String): String {
        return ObeliskDataManager.getObelisk(definitionId)?.let(::targetId) ?: legacyTargetDimension(definitionId)
    }

    fun targetLevelKey(definitionOrTargetId: String): ResourceKey<Level>? {
        val location = resourceLocationOrNull(targetId(definitionOrTargetId)) ?: return null
        return ResourceKey.create(Registries.DIMENSION, location)
    }

    fun coordinateScale(definition: ObeliskDefinition): Double {
        return definition.coordinateScale ?: legacyCoordinateScale(definition.instanceTemplateId.ifBlank { definition.id })
    }

    fun coordinateScale(definitionId: String): Double {
        return ObeliskDataManager.getObelisk(definitionId)?.let(::coordinateScale)
            ?: defaultCoordinateScale(definitionId)
    }

    fun defaultCoordinateScale(targetOrLegacyId: String): Double = legacyCoordinateScale(targetOrLegacyId)

    fun resourceLocationOrNull(raw: String): ResourceLocation? {
        return runCatching { ResourceLocation(raw) }.getOrNull()
    }

    private fun legacyTargetDimension(id: String): String {
        return when (id) {
            "overworld" -> "minecraft:overworld"
            "nether" -> "minecraft:the_nether"
            "end" -> "minecraft:the_end"
            "otherside" -> "deeperdarker:otherside"
            "undergarden" -> "undergarden:undergarden"
            else -> id
        }
    }

    private fun legacyCoordinateScale(id: String): Double {
        return when (id) {
            "nether", "minecraft:the_nether" -> 0.125
            else -> 1.0
        }
    }
}
