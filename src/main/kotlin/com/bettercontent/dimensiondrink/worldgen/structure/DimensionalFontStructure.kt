package com.bettercontent.dimensiondrink.worldgen.structure

import com.mojang.serialization.Codec
import com.bettercontent.dimensiondrink.ObeliskConstants
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import com.bettercontent.dimensiondrink.registry.ModStructures
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.tags.BiomeTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraftforge.common.Tags
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import java.util.Optional

class DimensionalFontStructure(settings: StructureSettings) : Structure(settings) {
    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val chunk = context.chunkPos()
        val seed = structureSeed(context.seed(), chunk)
        val random = RandomSource.create(seed)
        val center = DimensionalFontSiteGenerator.anchorForStartChunk(chunk, random)
        val centerBiome = context.chunkGenerator().biomeSource.getNoiseBiome(
            QuartPos.fromBlock(center.x),
            0,
            QuartPos.fromBlock(center.z),
            context.randomState().sampler()
        )
        if (
            centerBiome.`is`(BiomeTags.IS_OCEAN) ||
            centerBiome.`is`(BiomeTags.IS_RIVER) ||
            centerBiome.`is`(Tags.Biomes.IS_WATER)
        ) {
            return Optional.empty()
        }
        val definition = pickDefinition(RandomSource.create(seed)) ?: return Optional.empty()
        val heights = mutableListOf<Int>()
        for (dx in -DimensionalFontSiteGenerator.ALTAR_RADIUS..DimensionalFontSiteGenerator.ALTAR_RADIUS) {
            for (dz in -DimensionalFontSiteGenerator.ALTAR_RADIUS..DimensionalFontSiteGenerator.ALTAR_RADIUS) {
                val x = center.x + dx
                val z = center.z + dz
                val groundY = context.chunkGenerator().getFirstFreeHeight(
                    x,
                    z,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(),
                    context.randomState()
                ) - 1
                val surfaceState = context.chunkGenerator().getBaseColumn(
                    x,
                    z,
                    context.heightAccessor(),
                    context.randomState()
                ).getBlock(groundY)
                if (!surfaceState.fluidState.isEmpty) return Optional.empty()
                heights += groundY
            }
        }
        val groundY = heights.maxOrNull() ?: return Optional.empty()
        if (groundY - (heights.minOrNull() ?: groundY) > MAX_ALTAR_SLOPE) return Optional.empty()
        val maxCharge = ((definition.maxCharge ?: ObeliskConstants.MAX_CHARGE_STORAGE) * GENERATED_CAPACITY_MULTIPLIER)
            .coerceAtMost(1_000_000.0)
        val position = BlockPos(center.x, groundY, center.z)
        return Optional.of(GenerationStub(position) { pieces ->
            pieces.addPiece(DimensionalFontStructurePiece(position, seed, definition.id, maxCharge))
        })
    }

    override fun type(): StructureType<*> = ModStructures.DIMENSIONAL_FONT.get()

    companion object {
        val CODEC: Codec<DimensionalFontStructure> = simpleCodec(::DimensionalFontStructure)
        private const val MAX_ALTAR_SLOPE = 6
        private const val GENERATED_CAPACITY_MULTIPLIER = 1.5

        private fun pickDefinition(random: RandomSource): ObeliskDefinition? {
            val enabled = ObeliskDataManager.enabledDimensionDrinks()
                .filter { it.worldgenWeight > 0.0 }
                .sortedBy { it.id }
            if (enabled.isEmpty()) return null
            val total = enabled.sumOf { it.worldgenWeight }
            var cursor = random.nextDouble() * total
            for (definition in enabled) {
                cursor -= definition.worldgenWeight
                if (cursor <= 0.0) return definition
            }
            return enabled.last()
        }

        private fun structureSeed(worldSeed: Long, chunk: ChunkPos): Long {
            var value = worldSeed xor 0x4f1bbcdc2d6a5f3bL
            value = value xor (chunk.x.toLong() * -7046029254386353131L)
            value = value xor (chunk.z.toLong() * -4658895280553007687L)
            value = value xor (chunk.x.toLong() shl 32)
            value = value xor chunk.z.toLong()
            return value
        }
    }
}
