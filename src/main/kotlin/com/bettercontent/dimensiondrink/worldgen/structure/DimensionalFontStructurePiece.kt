package com.bettercontent.dimensiondrink.worldgen.structure

import com.bettercontent.dimensiondrink.registry.ModStructures
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
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
    private val center: BlockPos,
    private val siteSeed: Long,
    private val definitionId: String,
    private val maxBlood: Double,
    private val layoutVersion: Int = DimensionalFontSiteGenerator.LAYOUT_VERSION
) : StructurePiece(
    ModStructures.DIMENSIONAL_FONT_PIECE.get(),
    0,
    BoundingBox(
        center.x - DimensionalFontSiteGenerator.SITE_RADIUS,
        -64,
        center.z - DimensionalFontSiteGenerator.SITE_RADIUS,
        center.x + DimensionalFontSiteGenerator.SITE_RADIUS,
        320,
        center.z + DimensionalFontSiteGenerator.SITE_RADIUS
    )
) {
    init {
        require(layoutVersion == DimensionalFontSiteGenerator.LAYOUT_VERSION) {
            "Unsupported dimensional font layout version $layoutVersion"
        }
    }

    constructor(
        @Suppress("UNUSED_PARAMETER") context: StructurePieceSerializationContext,
        tag: CompoundTag
    ) : this(
        BlockPos(tag.getInt(TAG_CENTER_X), tag.getInt(TAG_CENTER_Y), tag.getInt(TAG_CENTER_Z)),
        tag.getLong(TAG_SITE_SEED),
        tag.getString(TAG_DEFINITION_ID),
        tag.getDouble(TAG_MAX_BLOOD),
        tag.getInt(TAG_LAYOUT_VERSION)
    )

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, tag: CompoundTag) {
        tag.putInt(TAG_LAYOUT_VERSION, layoutVersion)
        tag.putInt(TAG_CENTER_X, center.x)
        tag.putInt(TAG_CENTER_Y, center.y)
        tag.putInt(TAG_CENTER_Z, center.z)
        tag.putLong(TAG_SITE_SEED, siteSeed)
        tag.putString(TAG_DEFINITION_ID, definitionId)
        tag.putDouble(TAG_MAX_BLOOD, maxBlood)
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
        val definition = ObeliskDataManager.getObelisk(definitionId) ?: return
        DimensionalFontSiteGenerator.place(level, box, center, siteSeed, definition, maxBlood)
    }

    companion object {
        private const val TAG_LAYOUT_VERSION = "LayoutVersion"
        private const val TAG_CENTER_X = "CenterX"
        private const val TAG_CENTER_Y = "CenterY"
        private const val TAG_CENTER_Z = "CenterZ"
        private const val TAG_SITE_SEED = "SiteSeed"
        private const val TAG_DEFINITION_ID = "DefinitionId"
        private const val TAG_MAX_BLOOD = "MaxBlood"
    }
}
