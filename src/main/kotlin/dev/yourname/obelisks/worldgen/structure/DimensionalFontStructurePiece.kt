package dev.yourname.obelisks.worldgen.structure

import dev.yourname.obelisks.registry.ModStructures
import dev.yourname.obelisks.worldgen.ObeliskFeature
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext

class DimensionalFontStructurePiece(
    private val centerX: Int,
    private val centerZ: Int,
    private val siteSeed: Long
) : StructurePiece(
    ModStructures.DIMENSIONAL_FONT_PIECE.get(),
    0,
    BoundingBox(
        centerX - ObeliskFeature.STRUCTURE_SITE_MAX_BLOCK_RADIUS,
        ObeliskFeature.STRUCTURE_MIN_Y,
        centerZ - ObeliskFeature.STRUCTURE_SITE_MAX_BLOCK_RADIUS,
        centerX + ObeliskFeature.STRUCTURE_SITE_MAX_BLOCK_RADIUS,
        ObeliskFeature.STRUCTURE_MAX_Y,
        centerZ + ObeliskFeature.STRUCTURE_SITE_MAX_BLOCK_RADIUS
    )
) {
    constructor(
        @Suppress("UNUSED_PARAMETER") context: StructurePieceSerializationContext,
        tag: CompoundTag
    ) : this(
        tag.getInt(TAG_CENTER_X),
        tag.getInt(TAG_CENTER_Z),
        tag.getLong(TAG_SITE_SEED)
    )

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putInt(TAG_CENTER_X, centerX)
        tag.putInt(TAG_CENTER_Z, centerZ)
        tag.putLong(TAG_SITE_SEED, siteSeed)
    }

    override fun postProcess(
        level: WorldGenLevel,
        structureManager: StructureManager,
        chunkGenerator: ChunkGenerator,
        random: RandomSource,
        box: BoundingBox,
        chunkPos: ChunkPos,
        pivot: BlockPos
    ) {
        ObeliskFeature.placeStructureSiteForBox(level, centerX, centerZ, siteSeed, box)
    }

    companion object {
        private const val TAG_CENTER_X = "CenterX"
        private const val TAG_CENTER_Z = "CenterZ"
        private const val TAG_SITE_SEED = "SiteSeed"
    }
}
