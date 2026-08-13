package com.bettercontent.dimensiondrink.runtime.run

import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

internal data class FontEventContext(
    val definitionId: ResourceLocation,
    val targetDimension: ResourceKey<Level>,
    val aggregateId: ResourceLocation
)

internal object FontEventContextResolver {
    private val aggregateByDestination = mapOf(
        ResourceLocation("minecraft", "the_nether") to ResourceLocation("minecraft", "netherrack"),
        ResourceLocation("aether", "the_aether") to ResourceLocation("aether", "holystone"),
        ResourceLocation("undergarden", "undergarden") to ResourceLocation("undergarden", "deepsoil"),
        ResourceLocation("deeperdarker", "otherside") to ResourceLocation("deeperdarker", "cobbled_sculk_stone"),
        ResourceLocation("minecraft", "the_end") to ResourceLocation("minecraft", "end_stone"),
        ResourceLocation("minecraft", "overworld") to ResourceLocation("minecraft", "stone")
    )

    fun resolve(record: RunRecord): FontEventContext? {
        val targetDimension = record.backendLevelKey ?: return null
        val aggregateId = configuredAggregate(record.definitionId)
            ?: aggregateFor(targetDimension.location())
            ?: return null
        return FontEventContext(
            definitionId = definitionLocation(record.definitionId) ?: return null,
            targetDimension = targetDimension,
            aggregateId = aggregateId
        )
    }

    fun aggregateFor(targetDimension: ResourceLocation): ResourceLocation? =
        aggregateByDestination[targetDimension]

    private fun configuredAggregate(definitionId: String): ResourceLocation? {
        val configured = ObeliskDataManager.getObelisk(definitionId)?.dimensionDrinkCoreBlock
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        return runCatching { ResourceLocation(configured) }.getOrNull()
    }

    private fun definitionLocation(raw: String): ResourceLocation? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null
        return if (':' in normalized) {
            runCatching { ResourceLocation(normalized) }.getOrNull()
        } else {
            ResourceLocation(MOD_ID, normalized)
        }
    }
}
