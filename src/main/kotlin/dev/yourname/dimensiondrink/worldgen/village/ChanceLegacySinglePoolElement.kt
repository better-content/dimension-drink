package dev.yourname.dimensiondrink.worldgen.village

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.yourname.dimensiondrink.registry.ModStructurePoolElements
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.pools.LegacySinglePoolElement
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager
import net.minecraft.core.BlockPos

class ChanceLegacySinglePoolElement(
    private val location: ResourceLocation,
    val placementChance: Float,
    projection: StructureTemplatePool.Projection
) : LegacySinglePoolElement(Either.left(location), EMPTY_PROCESSORS, projection) {

    override fun place(
        structureTemplateManager: StructureTemplateManager,
        level: WorldGenLevel,
        structureManager: StructureManager,
        chunkGenerator: ChunkGenerator,
        blockPos: BlockPos,
        pivot: BlockPos,
        rotation: Rotation,
        boundingBox: BoundingBox,
        random: RandomSource,
        keepJigsaws: Boolean
    ): Boolean {
        if (random.nextFloat() > placementChance) {
            return false
        }
        return super.place(structureTemplateManager, level, structureManager, chunkGenerator, blockPos, pivot, rotation, boundingBox, random, keepJigsaws)
    }

    override fun getType(): StructurePoolElementType<*> = ModStructurePoolElements.CHANCE_LEGACY_SINGLE.get()

    fun matchesLocation(other: ResourceLocation): Boolean = location == other

    companion object {
        private val EMPTY_PROCESSORS = Holder.direct(StructureProcessorList(emptyList<StructureProcessor>()))

        val CODEC: Codec<ChanceLegacySinglePoolElement> = RecordCodecBuilder.create { instance ->
            instance.group(
                ResourceLocation.CODEC.fieldOf("location").forGetter { it.location },
                Codec.floatRange(0.0f, 1.0f).fieldOf("placement_chance").forGetter { it.placementChance },
                StructureTemplatePool.Projection.CODEC.fieldOf("projection").forGetter { it.getProjection() }
            ).apply(instance, ::ChanceLegacySinglePoolElement)
        }
    }
}
