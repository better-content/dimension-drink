package com.bettercontent.dimensiondrink.worldgen.structure

import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import com.bettercontent.dimensiondrink.registry.ModBlocks
import com.bettercontent.dimensiondrink.worldgen.preferredAltarSconceBlockIds
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import kotlin.math.abs
import kotlin.math.max

/**
 * Materializes a font site one vanilla structure slice at a time.
 *
 * Layout decisions depend only on the serialized site data and absolute block coordinates. Terrain
 * reads and writes are restricted to the supplied structure box, so callback order and C2ME's
 * callback chunk bookkeeping cannot change the result.
 */
object DimensionalFontSiteGenerator {
    const val LAYOUT_VERSION = 2
    const val SITE_RADIUS = 32
    const val COURT_RADIUS = 5
    const val ALTAR_RADIUS = 3
    private const val PATH_RADIUS = 24
    private const val FONT_CLEARANCE = 3
    private const val UPDATE_FLAGS = Block.UPDATE_CLIENTS

    fun anchorForStartChunk(chunk: ChunkPos, random: net.minecraft.util.RandomSource): BlockPos {
        val span = 16 - COURT_RADIUS * 2
        val localX = COURT_RADIUS + random.nextInt(span)
        val localZ = COURT_RADIUS + random.nextInt(span)
        return BlockPos(chunk.minBlockX + localX, 0, chunk.minBlockZ + localZ)
    }

    fun place(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        siteSeed: Long,
        definition: ObeliskDefinition,
        maxBlood: Double
    ) {
        placeLocalDressing(level, box, center, siteSeed, definition)
        if (box.isInside(center.offset(0, 3, 0))) {
            placeCenter(level, box, center, siteSeed, definition, maxBlood)
        }
    }

    internal fun centerFitsStartChunk(center: BlockPos): Boolean {
        val localX = Math.floorMod(center.x, 16)
        val localZ = Math.floorMod(center.z, 16)
        return localX in COURT_RADIUS..(15 - COURT_RADIUS) &&
            localZ in COURT_RADIUS..(15 - COURT_RADIUS)
    }

    internal fun isPathColumn(dx: Int, dz: Int): Boolean {
        val distance = max(abs(dx), abs(dz))
        if (distance <= COURT_RADIUS || distance > PATH_RADIUS) return false
        return abs(dx) <= 1 || abs(dz) <= 1
    }

    internal fun coordinateHash(seed: Long, x: Int, z: Int, salt: Long = 0L): Long {
        var value = seed xor salt
        value = value xor (x.toLong() * -7046029254386353131L)
        value = value xor (z.toLong() * -4658895280553007687L)
        value = value xor (value ushr 30)
        value *= -4658895280553007687L
        value = value xor (value ushr 27)
        value *= -7723592293110705685L
        return value xor (value ushr 31)
    }

    private fun placeCenter(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        siteSeed: Long,
        definition: ObeliskDefinition,
        maxBlood: Double
    ) {
        require(centerFitsStartChunk(center)) { "Dimensional font center must fit inside its start chunk" }

        for (dx in -COURT_RADIUS..COURT_RADIUS) {
            for (dz in -COURT_RADIUS..COURT_RADIUS) {
                val x = center.x + dx
                val z = center.z + dz
                val groundY = localGroundY(level, box, x, z) ?: continue
                val distance = max(abs(dx), abs(dz))
                if (distance > ALTAR_RADIUS) {
                    val court = BlockPos(x, groundY, z)
                    setBoxed(level, box, court, courtState(siteSeed, court))
                    continue
                }

                for (y in groundY..center.y) {
                    val foundation = BlockPos(x, y, z)
                    setBoxed(level, box, foundation, copperState(Blocks.CUT_COPPER, foundation, center))
                }
                setBoxed(level, box, BlockPos(x, center.y, z), copperState(Blocks.CUT_COPPER, BlockPos(x, center.y, z), center))
                if (distance <= 2) {
                    val middle = BlockPos(x, center.y + 1, z)
                    setBoxed(level, box, middle, copperState(Blocks.COPPER_BLOCK, middle, center))
                }
                if (distance <= 1) {
                    val upper = BlockPos(x, center.y + 2, z)
                    setBoxed(level, box, upper, copperState(Blocks.COPPER_BLOCK, upper, center))
                }
            }
        }

        val pedestal = center.above(2)
        setBoxed(level, box, pedestal, copperState(Blocks.RAW_COPPER_BLOCK, pedestal, center))
        listOf(-COURT_RADIUS to -COURT_RADIUS, -COURT_RADIUS to COURT_RADIUS, COURT_RADIUS to -COURT_RADIUS, COURT_RADIUS to COURT_RADIUS)
            .forEach { (dx, dz) ->
                val corner = center.offset(dx, 0, dz)
                setBoxed(level, box, corner, copperState(Blocks.CUT_COPPER, corner, center))
            }
        val fontPos = center.above(3)
        setBoxed(level, box, fontPos, ModBlocks.OBELISK.get().defaultBlockState())
        for (dy in 1..FONT_CLEARANCE) {
            setBoxed(level, box, fontPos.above(dy), Blocks.AIR.defaultBlockState())
        }

        placeCenterDetails(level, box, center, siteSeed, definition)
        (level.getBlockEntity(fontPos) as? ObeliskBlockEntity)
            ?.initializeGeneratedFont(definition.id, maxBlood)
    }

    private fun placeCenterDetails(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        siteSeed: Long,
        definition: ObeliskDefinition
    ) {
        val supportY = center.y + 4
        listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).forEach { (dx, dz) ->
            for (y in center.y + 2..supportY) {
                val support = BlockPos(center.x + dx, y, center.z + dz)
                var state = Blocks.STRIPPED_WARPED_STEM.defaultBlockState()
                if (state.hasProperty(BlockStateProperties.AXIS)) {
                    state = state.setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                }
                setBoxed(level, box, support, state)
            }
        }

        placeAltarSideSconces(level, box, center, supportY)
        placeAltarCopperRoof(level, box, center, supportY + 1)

        val potBase = center.offset(0, 1, 4)
        setBoxed(level, box, potBase, copperState(Blocks.CUT_COPPER, potBase, center))
        setBoxed(level, box, potBase.above(), Blocks.POTTED_FLOWERING_AZALEA.defaultBlockState())

        val trophies = paletteBlocks(definition, PaletteKind.TROPHY, listOf(Blocks.LANTERN, Blocks.WHITE_CANDLE))
        val offsets = listOf(-4 to -4, 4 to -4, -4 to 4, 4 to 4)
        offsets.forEachIndexed { index, (dx, dz) ->
            val base = BlockPos(center.x + dx, center.y + 1, center.z + dz)
            setBoxed(level, box, base, copperState(Blocks.RAW_COPPER_BLOCK, base, center))
            val trophyPos = base.above()
            val trophy = trophies[Math.floorMod(coordinateHash(siteSeed, trophyPos.x, trophyPos.z).toInt() + index, trophies.size)]
            val state = preparedState(trophy)
            if (state.canSurvive(level, trophyPos)) setBoxed(level, box, trophyPos, state)
        }
    }

    private fun placeAltarCopperRoof(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        roofY: Int
    ) {
        for (dx in -2..2) {
            for (dz in -2..2) {
                if (max(abs(dx), abs(dz)) != 2) continue
                val pos = center.offset(dx, roofY - center.y, dz)
                val block = when {
                    abs(dx) == 2 && abs(dz) == 2 -> roofSlabBlock(pos)
                    else -> roofStairBlock(pos)
                }
                val facing = when {
                    dx < 0 -> Direction.WEST
                    dx > 0 -> Direction.EAST
                    dz < 0 -> Direction.NORTH
                    else -> Direction.SOUTH
                }
                setBoxed(level, box, pos, roofState(block, pos, facing))
            }
        }
    }

    private fun placeAltarSideSconces(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        supportTopY: Int
    ) {
        listOf(
            BlockPos(center.x - 2, supportTopY, center.z - 3) to Direction.NORTH,
            BlockPos(center.x - 3, supportTopY, center.z - 2) to Direction.WEST,
            BlockPos(center.x - 2, supportTopY, center.z + 3) to Direction.SOUTH,
            BlockPos(center.x - 3, supportTopY, center.z + 2) to Direction.WEST,
            BlockPos(center.x + 2, supportTopY, center.z - 3) to Direction.NORTH,
            BlockPos(center.x + 3, supportTopY, center.z - 2) to Direction.EAST,
            BlockPos(center.x + 2, supportTopY, center.z + 3) to Direction.SOUTH,
            BlockPos(center.x + 3, supportTopY, center.z + 2) to Direction.EAST
        ).forEach { (pos, facing) ->
            val key = preferredAltarSconceBlockIds()
                .asSequence()
                .mapNotNull(ResourceLocation::tryParse)
                .mapNotNull { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
                .firstOrNull { it != Blocks.AIR }
                ?: Blocks.LANTERN
            setBoxed(level, box, pos, sconceState(key, pos, facing))
        }
    }

    private fun sconceState(block: Block, pos: BlockPos, facing: Direction): BlockState {
        var state = block.defaultBlockState()
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing)
        }
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
        }
        if (state.hasProperty(BlockStateProperties.LIT)) {
            state = state.setValue(BlockStateProperties.LIT, true)
        }
        return state
    }

    private fun roofState(block: Block, pos: BlockPos, facing: Direction): BlockState {
        var state = copperState(block, pos, null)
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, facing)
        }
        if (state.hasProperty(BlockStateProperties.STAIRS_SHAPE)) {
            state = state.setValue(BlockStateProperties.STAIRS_SHAPE, net.minecraft.world.level.block.state.properties.StairsShape.STRAIGHT)
        }
        return state
    }

    private fun roofSlabBlock(pos: BlockPos): Block =
        optionalBlock("create", "create:exposed_copper_shingle_slab", "create:weathered_copper_shingle_slab")
            ?: Blocks.CUT_COPPER_SLAB

    private fun roofStairBlock(pos: BlockPos): Block =
        optionalBlock("create", "create:exposed_copper_shingle_stairs", "create:weathered_copper_shingle_stairs")
            ?: Blocks.CUT_COPPER_STAIRS

    private fun optionalBlock(namespace: String, vararg ids: String): Block? =
        ids.asSequence()
            .mapNotNull(ResourceLocation::tryParse)
            .filter { it.namespace == namespace }
            .mapNotNull { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
            .firstOrNull { it != Blocks.AIR }

    private fun placeLocalDressing(
        level: WorldGenLevel,
        box: BoundingBox,
        center: BlockPos,
        siteSeed: Long,
        definition: ObeliskDefinition
    ) {
        // A stable packed-mud approach keeps path transitions legible without allowing a
        // configurable palette to turn the whole surrounding terrain into a rigid platform.
        val paths = listOf(Blocks.PACKED_MUD)
        val structures = paletteBlocks(definition, PaletteKind.STRUCTURE, listOf(Blocks.CUT_COPPER, Blocks.COPPER_BLOCK))
        val decorations = paletteBlocks(definition, PaletteKind.DECORATION, listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE))
        val minX = maxOf(box.minX(), center.x - SITE_RADIUS)
        val maxX = minOf(box.maxX(), center.x + SITE_RADIUS)
        val minZ = maxOf(box.minZ(), center.z - SITE_RADIUS)
        val maxZ = minOf(box.maxZ(), center.z + SITE_RADIUS)

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val dx = x - center.x
                val dz = z - center.z
                val distance = max(abs(dx), abs(dz))
                if (distance <= COURT_RADIUS || distance > SITE_RADIUS) continue
                val groundY = localGroundY(level, box, x, z) ?: continue
                val ground = BlockPos(x, groundY, z)
                val groundState = level.getBlockState(ground)
                val above = ground.above()
                if (!groundState.fluidState.isEmpty || level.getBlockEntity(ground) != null) continue
                if (!groundState.isFaceSturdy(level, ground, Direction.UP)) continue

                val hash = coordinateHash(siteSeed, x, z)
                if (isPathColumn(dx, dz)) {
                    if (isNaturalPathGround(groundState)) {
                        val block = paths[Math.floorMod(hash.toInt(), paths.size)]
                        setBoxed(level, box, ground, preparedState(block))
                    }
                    continue
                }

                if (Math.floorMod(hash, 29L) == 0L && level.getBlockState(above).isAir) {
                    val block = structures[Math.floorMod((hash ushr 8).toInt(), structures.size)]
                    setBoxed(level, box, ground, preparedState(block))
                } else if (Math.floorMod(hash, 43L) == 0L && level.getBlockState(above).isAir) {
                    val block = decorations[Math.floorMod((hash ushr 16).toInt(), decorations.size)]
                    val state = preparedState(block)
                    if (state.canSurvive(level, above)) setBoxed(level, box, above, state)
                }
            }
        }
    }

    private fun localGroundY(level: WorldGenLevel, box: BoundingBox, x: Int, z: Int): Int? {
        if (x !in box.minX()..box.maxX() || z !in box.minZ()..box.maxZ()) return null
        val firstFree = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)
        val y = firstFree - 1
        return y.takeIf { it in box.minY()..box.maxY() }
    }

    private fun isNaturalPathGround(state: BlockState): Boolean =
        state.`is`(BlockTags.DIRT) ||
            state.`is`(BlockTags.SAND) ||
            state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
            state.`is`(BlockTags.BASE_STONE_NETHER) ||
            state.`is`(Blocks.PACKED_MUD)

    private fun setBoxed(level: WorldGenLevel, box: BoundingBox, pos: BlockPos, state: BlockState): Boolean =
        box.isInside(pos) && level.setBlock(pos, state, UPDATE_FLAGS)

    private fun courtState(siteSeed: Long, pos: BlockPos): BlockState {
        val block = if (Math.floorMod(coordinateHash(siteSeed, pos.x, pos.z, 0x43a7L), 5L) == 0L) {
            Blocks.PACKED_MUD
        } else {
            Blocks.CUT_COPPER
        }
        return if (block == Blocks.PACKED_MUD) block.defaultBlockState() else copperState(block, pos, null)
    }

    private fun copperState(block: Block, pos: BlockPos, center: BlockPos?): BlockState {
        val distance = center?.let { max(abs(pos.x - it.x), abs(pos.z - it.z)) } ?: ALTAR_RADIUS + 1
        val aged = when (block) {
            Blocks.COPPER_BLOCK -> if (distance <= 1) Blocks.COPPER_BLOCK else Blocks.EXPOSED_COPPER
            Blocks.CUT_COPPER -> if (distance <= 2) Blocks.EXPOSED_CUT_COPPER else Blocks.WEATHERED_CUT_COPPER
            else -> block
        }
        return aged.defaultBlockState()
    }

    private fun preparedState(block: Block): BlockState {
        var state = block.defaultBlockState()
        if (state.hasProperty(BlockStateProperties.LIT)) state = state.setValue(BlockStateProperties.LIT, true)
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
        if (state.hasProperty(BlockStateProperties.FACING)) state = state.setValue(BlockStateProperties.FACING, Direction.UP)
        return state
    }

    private enum class PaletteKind { PATH, STRUCTURE, DECORATION, TROPHY }

    private fun paletteBlocks(definition: ObeliskDefinition, kind: PaletteKind, fallback: List<Block>): List<Block> {
        val configured = definition.cultivationPalette ?: definition.graveyardPalette
        val ids = when (kind) {
            PaletteKind.PATH -> configured?.pathBlocks ?: definition.pathBlocks
            PaletteKind.STRUCTURE -> configured?.structureBlocks ?: definition.structureBlocks
            PaletteKind.DECORATION -> configured?.decorations ?: definition.decorations
            PaletteKind.TROPHY -> configured?.trophyBlocks ?: definition.trophyBlocks
        }
        val resolved = ids.orEmpty().mapNotNull { id ->
            val key = ResourceLocation.tryParse(id) ?: return@mapNotNull null
            BuiltInRegistries.BLOCK.getOptional(key).orElse(null)?.takeUnless { it == Blocks.AIR }
        }
        return resolved.ifEmpty { fallback }
    }
}
