package dev.yourname.dimensiondrink.worldgen.structure

import com.mojang.serialization.Codec
import dev.yourname.dimensiondrink.registry.ModStructures
import dev.yourname.dimensiondrink.worldgen.ObeliskFeature
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
        val center = ObeliskFeature.structureAnchorForChunk(chunk, random)
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
        val surfaceY = context.chunkGenerator().getFirstFreeHeight(
            center.x,
            center.z,
            Heightmap.Types.WORLD_SURFACE_WG,
            context.heightAccessor(),
            context.randomState()
        )
        val position = BlockPos(center.x, surfaceY, center.z)
        return Optional.of(GenerationStub(position) { pieces ->
            pieces.addPiece(DimensionalFontStructurePiece(center.x, center.z, seed))
        })
    }

    override fun type(): StructureType<*> = ModStructures.DIMENSIONAL_FONT.get()

    companion object {
        val CODEC: Codec<DimensionalFontStructure> = simpleCodec(::DimensionalFontStructure)

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
