package com.bettercontent.dimensiondrink.worldgen.village

import com.mojang.datafixers.util.Pair
import com.bettercontent.dimensiondrink.MOD_ID
import com.bettercontent.dimensiondrink.mixin.StructureTemplatePoolAccessor
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraftforge.event.server.ServerAboutToStartEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object VillageShrinePools {
    private const val TARGET_ATTEMPT_RATE = 0.0025
    private const val SHRINE_WEIGHT = 1

    data class ShrinePoolTarget(
        val style: String,
        val poolId: ResourceLocation,
        val templateId: ResourceLocation,
        val basePoolWeight: Int,
        val placementChance: Float
    ) {
        fun estimatedAttemptRate(): Double = SHRINE_WEIGHT.toDouble() / (basePoolWeight + SHRINE_WEIGHT) * placementChance
    }

    val TARGETS: List<ShrinePoolTarget> = listOf(
        target("plains", 7),
        target("desert", 28),
        target("savanna", 17),
        target("snowy", 27),
        target("taiga", 39)
    )

    @SubscribeEvent
    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val templatePools = event.server.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL)
        TARGETS.forEach { target ->
            templatePools.get(target.poolId)?.let { pool ->
                appendShrineIfMissing(pool, target)
            }
        }
    }

    private fun appendShrineIfMissing(pool: StructureTemplatePool, target: ShrinePoolTarget) {
        val access = pool as StructureTemplatePoolAccessor
        val rawTemplates = access.dimensionDrinkRawTemplates.toMutableList()
        if (rawTemplates.any { pair ->
                val element = pair.first
                element is ChanceLegacySinglePoolElement && element.matchesLocation(target.templateId)
            }) {
            return
        }

        val shrine = ChanceLegacySinglePoolElement(
            location = target.templateId,
            placementChance = target.placementChance,
            projection = StructureTemplatePool.Projection.RIGID
        )
        rawTemplates.add(Pair.of(shrine, SHRINE_WEIGHT))
        access.dimensionDrinkRawTemplates = rawTemplates

        val expandedTemplates: ObjectArrayList<StructurePoolElement> = access.dimensionDrinkTemplates
        repeat(SHRINE_WEIGHT) {
            expandedTemplates.add(shrine)
        }
        access.setDimensionDrinkMaxSize(Int.MIN_VALUE)
    }

    private fun target(style: String, basePoolWeight: Int): ShrinePoolTarget {
        val totalWeight = basePoolWeight + SHRINE_WEIGHT
        val chance = (TARGET_ATTEMPT_RATE * totalWeight).toFloat().coerceAtMost(1.0f)
        return ShrinePoolTarget(
            style = style,
            poolId = ResourceLocation.fromNamespaceAndPath("minecraft", "village/$style/decor"),
            templateId = ResourceLocation.fromNamespaceAndPath(MOD_ID, "village/font_shrine"),
            basePoolWeight = basePoolWeight,
            placementChance = chance
        )
    }
}
