package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraft.server.level.ServerLevel
import kotlin.math.abs

class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    companion object {
        private const val TILE_SIZE = 4
        private const val MAX_TILE_RADIUS = 16
        private const val MIN_TILE_RADIUS = 10
        private const val FONT_CLEARANCE = 3
        fun generateDefinitionSiteForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val placementCenter = findPlacementCenter(level, center) ?: return false
            val fontPos = buildSiteBlocks(level, level::setBlock, placementCenter, definition, random) ?: return false
            if (!level.setBlock(fontPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) return false
            val font = level.getBlockEntity(fontPos) as? ObeliskBlockEntity ?: return false
            font.setDefinition(definition.id)
            font.fillToCapacity()
            font.syncToClients()
            return true
        }

        private fun findPlacementCenter(level: LevelAccessor, origin: BlockPos): BlockPos? {
            if (!level.getBlockState(origin).fluidState.isEmpty) return null
            val snapped = snapToUniversalGrid(origin)
            val surfaceY = findSurfaceY(level, snapped.x, snapped.z, scanTopForOrigin(level, origin), FONT_CLEARANCE + 1) ?: return null
            val center = BlockPos(snapped.x, surfaceY, snapped.z)
            return if (canPlacePedestalAndFont(level, center)) center else null
        }

        private fun scanTopForOrigin(level: LevelAccessor, origin: BlockPos): Int =
            if (origin.y <= level.minBuildHeight + 2) level.maxBuildHeight - 2 else origin.y + 4

        private fun snapToUniversalGrid(origin: BlockPos): BlockPos {
            val cellX = Math.floorDiv(origin.x, TILE_SIZE)
            val cellZ = Math.floorDiv(origin.z, TILE_SIZE)
            return BlockPos(cellX * TILE_SIZE + TILE_SIZE / 2, origin.y, cellZ * TILE_SIZE + TILE_SIZE / 2)
        }

        private fun buildSiteBlocks(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            definition: ObeliskDefinition,
            random: RandomSource
        ): BlockPos? {
            val palette = GraveyardPalette.from(definition)
            val tiles = planTiles(level, center, random)
            val fontTile = tiles[TileCoord(0, 0)] ?: return null
            if (!canPlacePedestalAndFont(level, fontTile.groundPos)) return null

            tiles.values.sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z }).forEach { tile ->
                placeTile(level, setBlock, tile, tiles, palette, random)
            }
            placeBoundaryAccents(level, setBlock, tiles, palette, random)

            setBlock(fontTile.groundPos, palette.pedestal.defaultBlockState(), 3)
            for (dy in 1..FONT_CLEARANCE) {
                setBlock(fontTile.groundPos.above(dy), Blocks.AIR.defaultBlockState(), 3)
            }
            return fontTile.groundPos.above()
        }

        private fun planTiles(level: LevelAccessor, center: BlockPos, random: RandomSource): Map<TileCoord, TilePlan> {
            val radius = MIN_TILE_RADIUS + random.nextInt(MAX_TILE_RADIUS - MIN_TILE_RADIUS + 1)
            val candidates = linkedMapOf<TileCoord, TilePlan>()
            for (x in -MAX_TILE_RADIUS..MAX_TILE_RADIUS) {
                for (z in -MAX_TILE_RADIUS..MAX_TILE_RADIUS) {
                    val coord = TileCoord(x, z)
                    if (!insideOrganicShape(coord, radius)) continue
                    val ground = tileGround(level, center, coord) ?: continue
                    if (!canUseTileGround(level, ground, clearance = 2)) continue
                    candidates[coord] = TilePlan(coord, ground, TileType.DECOR, emptySet())
                }
            }

            val font = candidates[TileCoord(0, 0)] ?: return emptyMap()
            val paths = linkedSetOf(font.coord)
            val exits = listOf(TileCoord(radius, 0), TileCoord(-radius, 0), TileCoord(0, radius), TileCoord(0, -radius)).shuffled(random).take(3)
            exits.forEach { target ->
                var x = 0
                var z = 0
                var previous = TileCoord(0, 0)
                while (x != target.x || z != target.z) {
                    val stepX = if (x < target.x) 1 else if (x > target.x) -1 else 0
                    val stepZ = if (z < target.z) 1 else if (z > target.z) -1 else 0
                    if ((stepX != 0 && stepZ == 0) || (stepX != 0 && random.nextBoolean())) x += stepX else z += stepZ
                    val next = TileCoord(x, z)
                    if (next !in candidates) break
                    if (isPathable(candidates[previous], candidates[next])) {
                        paths += next
                        previous = next
                    } else {
                        break
                    }
                }
            }

            val planned = linkedMapOf<TileCoord, TilePlan>()
            candidates.forEach { (coord, tile) ->
                val exitsForTile = Direction.Plane.HORIZONTAL.filter { direction ->
                    val other = coord.relative(direction)
                    other in paths && isPathable(tile, candidates[other])
                }.toSet()
                val type = when {
                    coord == TileCoord(0, 0) -> TileType.FONT_PEDESTAL
                    coord in paths -> TileType.PATH
                    abs(coord.x) + abs(coord.z) <= 5 && random.nextInt(100) < 28 -> TileType.SHRINE
                    else -> {
                        val roll = random.nextInt(100)
                        when {
                            roll < 46 -> TileType.GRAVE_SINGLE
                            roll < 69 -> TileType.GRAVE_DOUBLE
                            roll < 80 -> TileType.MAUSOLEUM_SMALL
                            roll < 91 -> TileType.STATUE_RUIN
                            roll < 97 -> TileType.TREE_STUMP
                            else -> TileType.DECOR
                        }
                    }
                }
                planned[coord] = tile.copy(type = type, pathExits = exitsForTile)
            }
            return planned
        }

        private fun insideOrganicShape(coord: TileCoord, radius: Int): Boolean {
            val manhattan = abs(coord.x) + abs(coord.z)
            val chebyshev = maxOf(abs(coord.x), abs(coord.z))
            val noise = Math.floorDiv(coord.x * 31 + coord.z * 17 + coord.x * coord.z * 7, 11).let { abs(it % 5) - 2 }
            return chebyshev <= radius && manhattan <= radius + (radius / 2) + noise
        }

        private fun tileGround(level: LevelAccessor, center: BlockPos, coord: TileCoord): BlockPos? {
            val x = center.x + coord.x * TILE_SIZE
            val z = center.z + coord.z * TILE_SIZE
            val y = findSurfaceY(level, x, z, center.y + 8, 2) ?: return null
            return BlockPos(x, y, z)
        }

        private fun findSurfaceY(level: LevelAccessor, x: Int, z: Int, maxY: Int, clearance: Int): Int? {
            val top = maxY.coerceAtMost(level.maxBuildHeight - 2)
            val bottom = level.minBuildHeight + 1
            for (y in top downTo bottom) {
                val pos = BlockPos(x, y, z)
                val state = level.getBlockState(pos)
                if (isSupportedGround(level, pos, state) && hasClearance(level, pos, clearance)) {
                    return y
                }
            }
            return null
        }

        private fun isPathable(a: TilePlan?, b: TilePlan?): Boolean {
            if (a == null || b == null) return false
            return abs(a.groundPos.y - b.groundPos.y) <= 1
        }

        private fun placeTile(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            tile: TilePlan,
            tiles: Map<TileCoord, TilePlan>,
            palette: GraveyardPalette,
            random: RandomSource
        ) {
            when (tile.type) {
                TileType.FONT_PEDESTAL -> {
                    placeGround(level, setBlock, tile.groundPos, palette.path(random))
                    tile.pathExits.forEach { direction -> placePathArm(level, setBlock, tile, direction, palette, random) }
                    scatterTileDetails(level, setBlock, tile.groundPos, palette, random, 3)
                }
                TileType.PATH -> {
                    placeGround(level, setBlock, tile.groundPos, palette.path(random))
                    tile.pathExits.forEach { direction -> placePathArm(level, setBlock, tile, direction, palette, random) }
                    placeStepForRaisedNeighbors(level, setBlock, tile, tiles)
                    scatterTileDetails(level, setBlock, tile.groundPos, palette, random, 2)
                }
                TileType.GRAVE_SINGLE -> buildSingleGrave(level, setBlock, tile.groundPos, palette, random)
                TileType.GRAVE_DOUBLE -> buildDoubleGrave(level, setBlock, tile.groundPos, palette, random)
                TileType.MAUSOLEUM_SMALL -> buildMausoleum(level, setBlock, tile.groundPos, palette, random)
                TileType.SHRINE -> buildShrine(level, setBlock, tile.groundPos, palette, random)
                TileType.STATUE_RUIN -> buildRuin(level, setBlock, tile.groundPos, palette, random)
                TileType.TREE_STUMP -> buildStump(level, setBlock, tile.groundPos, random)
                TileType.DECOR -> buildDecor(level, setBlock, tile.groundPos, palette, random)
            }
        }

        private fun placePathArm(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tile: TilePlan, direction: Direction, palette: GraveyardPalette, random: RandomSource) {
            for (step in 1 until TILE_SIZE) {
                val pos = tile.groundPos.relative(direction, step)
                if (canUseTileGround(level, pos, clearance = 1)) {
                    placeGround(level, setBlock, pos, palette.path(random))
                }
            }
        }

        private fun placeStepForRaisedNeighbors(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tile: TilePlan, tiles: Map<TileCoord, TilePlan>) {
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val neighbor = tiles[tile.coord.relative(direction)] ?: return@forEach
                if (abs(neighbor.groundPos.y - tile.groundPos.y) != 1) return@forEach
                val lower = if (neighbor.groundPos.y < tile.groundPos.y) neighbor.groundPos else tile.groundPos
                val slabPos = lower.relative(if (lower == tile.groundPos) direction else direction.opposite, TILE_SIZE / 2).above()
                if (canReplaceDecoration(level, slabPos) && isSupportedGround(level, slabPos.below(), level.getBlockState(slabPos.below()))) {
                    setBlock(slabPos, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 3)
                }
            }
        }

        private fun buildSingleGrave(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            val direction = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            buildGraveLine(level, setBlock, base, direction, palette, random)
        }

        private fun buildDoubleGrave(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            val direction = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            buildGraveLine(level, setBlock, base.relative(side), direction, palette, random)
            buildGraveLine(level, setBlock, base.relative(side.opposite), direction, palette, random)
            scatterTileDetails(level, setBlock, base, palette, random, 2)
        }

        private fun buildGraveLine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, head: BlockPos, direction: Direction, palette: GraveyardPalette, random: RandomSource) {
            val body = head.relative(direction)
            val foot = head.relative(direction, 2)
            if (!canUseTileGround(level, head, 2) || !canUseTileGround(level, body, 1)) return
            placeGround(level, setBlock, head, palette.grave(random))
            placeGround(level, setBlock, body, palette.grave(random))
            if (canUseTileGround(level, foot, 1)) placeGround(level, setBlock, foot, if (random.nextBoolean()) palette.path(random) else palette.grave(random))
            placeSupportedAbove(level, setBlock, head.above(), palette.headstone(random))
            if (random.nextInt(3) != 0) placeSupportedAbove(level, setBlock, body.above(), lowGraveMarker(random))
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            listOf(head.relative(side), body.relative(side.opposite), foot.relative(side)).forEach { pos ->
                if (random.nextInt(3) != 0 && canUseTileGround(level, pos, 1)) {
                    if (random.nextBoolean()) placeGround(level, setBlock, pos, palette.path(random))
                    placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                }
            }
        }

        private fun buildMausoleum(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            val door = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val pos = base.offset(dx, 0, dz)
                    if (!canUseTileGround(level, pos, 3)) return
                }
            }
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val pos = base.offset(dx, 0, dz)
                    placeGround(level, setBlock, pos, palette.structure(random))
                    val isDoor = (door == Direction.NORTH && dz == -1 && dx == 0) ||
                        (door == Direction.SOUTH && dz == 1 && dx == 0) ||
                        (door == Direction.WEST && dx == -1 && dz == 0) ||
                        (door == Direction.EAST && dx == 1 && dz == 0)
                    if ((abs(dx) == 1 || abs(dz) == 1) && !isDoor) {
                        placeSupportedAbove(level, setBlock, pos.above(), palette.structure(random))
                        if (random.nextInt(3) != 0) placeSupportedAbove(level, setBlock, pos.above(2), palette.structure(random))
                    }
                }
            }
            placeSupportedAbove(level, setBlock, base.above(), palette.headstone(random))
            placeSupportedAbove(level, setBlock, base.above(2), if (random.nextBoolean()) Blocks.SMOOTH_STONE_SLAB else palette.structure(random))
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildShrine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val pos = base.offset(dx, 0, dz)
                    if (canUseTileGround(level, pos, 2)) {
                        placeGround(level, setBlock, pos, if (abs(dx) + abs(dz) == 0) palette.structure(random) else palette.path(random))
                    }
                }
            }
            if (!canUseTileGround(level, base, 3)) return
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val corner = base.relative(direction).relative(if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST)
                if (canUseTileGround(level, corner, 2)) placeSupportedAbove(level, setBlock, corner.above(), palette.wall(random))
            }
            placeSupportedAbove(level, setBlock, base.above(), palette.headstone(random))
            placeSupportedAbove(level, setBlock, base.above(2), if (random.nextBoolean()) Blocks.SOUL_LANTERN else Blocks.SKELETON_SKULL)
            scatterTileDetails(level, setBlock, base, palette, random, 4)
        }

        private fun buildRuin(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeGround(level, setBlock, base, palette.structure(random))
            Direction.Plane.HORIZONTAL.toList().shuffled(random).forEach { direction ->
                for (step in 1..2) {
                    val pos = base.relative(direction, step)
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextBoolean()) placeGround(level, setBlock, pos, palette.structure(random))
                        placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) palette.headstone(random) else palette.wall(random))
                        if (random.nextInt(5) == 0) placeSupportedAbove(level, setBlock, pos.above(2), palette.wall(random))
                    }
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildStump(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeGround(level, setBlock, base, if (random.nextBoolean()) Blocks.COARSE_DIRT else Blocks.ROOTED_DIRT)
            placeSupportedAbove(level, setBlock, base.above(), if (random.nextBoolean()) Blocks.OAK_LOG else Blocks.SPRUCE_LOG)
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                val root = base.relative(direction)
                if (canUseTileGround(level, root, 1)) placeGround(level, setBlock, root, if (random.nextBoolean()) Blocks.ROOTED_DIRT else Blocks.COARSE_DIRT)
            }
        }

        private fun buildDecor(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            if (!canUseTileGround(level, base, 1)) return
            placeGround(level, setBlock, base, if (random.nextInt(4) == 0) palette.grave(random) else palette.path(random))
            placeSupportedAbove(level, setBlock, base.above(), palette.decoration(random))
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun scatterTileDetails(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource, attempts: Int) {
            repeat(attempts) {
                val pos = base.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2)
                if (canUseTileGround(level, pos, 1)) {
                    if (random.nextInt(4) == 0) placeGround(level, setBlock, pos, palette.path(random))
                    if (random.nextInt(3) != 0) placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                }
            }
        }

        private fun placeBoundaryAccents(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: GraveyardPalette, random: RandomSource) {
            tiles.values.forEach { tile ->
                for (direction in Direction.Plane.HORIZONTAL) {
                    if (tile.coord.relative(direction) in tiles || random.nextInt(100) >= 28) continue
                    val pos = tile.groundPos.relative(direction, TILE_SIZE / 2)
                    if (canUseTileGround(level, pos, 2)) {
                        placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) palette.headstone(random) else palette.wall(random))
                    }
                }
            }
        }

        private fun lowGraveMarker(random: RandomSource): Block = when (random.nextInt(4)) {
            0 -> Blocks.SMOOTH_STONE_SLAB
            1 -> Blocks.BONE_BLOCK
            2 -> Blocks.SKELETON_SKULL
            else -> Blocks.RED_CANDLE
        }

        private fun placeGround(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            if (!canUseTileGround(level, pos, clearance = 1)) return false
            return setBlock(pos, block.defaultBlockState(), 3)
        }

        private fun placeSupportedAbove(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            val below = pos.below()
            if (!isSupportedGround(level, below, level.getBlockState(below))) return false
            if (!canReplaceDecoration(level, pos)) return false
            return setBlock(pos, block.defaultBlockState(), 3)
        }

        private fun canPlacePedestalAndFont(level: LevelAccessor, pedestalPos: BlockPos): Boolean {
            if (!canUseTileGround(level, pedestalPos, clearance = FONT_CLEARANCE + 1)) return false
            for (dy in 1..FONT_CLEARANCE + 1) {
                if (!canReplaceSiteAirspace(level.getBlockState(pedestalPos.above(dy)))) return false
            }
            return true
        }

        private fun hasClearance(level: LevelAccessor, pos: BlockPos, clearance: Int): Boolean {
            for (dy in 1..clearance) {
                if (!canReplaceSiteAirspace(level.getBlockState(pos.above(dy)))) return false
            }
            return true
        }

        private fun canUseTileGround(level: LevelAccessor, pos: BlockPos, clearance: Int): Boolean {
            val state = level.getBlockState(pos)
            if (!isSupportedGround(level, pos, state)) return false
            for (dy in 1..clearance) {
                if (!canReplaceSiteAirspace(level.getBlockState(pos.above(dy)))) return false
            }
            return true
        }

        private fun isSupportedGround(level: LevelAccessor, pos: BlockPos, state: BlockState): Boolean {
            if (state.`is`(ModBlocks.OBELISK.get())) return false
            if (state.isAir || !state.fluidState.isEmpty || isLeafLike(state) || state.`is`(Blocks.ICE)) return false
            return state.isFaceSturdy(level, pos, Direction.UP)
        }

        private fun canReplaceDecoration(level: LevelAccessor, pos: BlockPos): Boolean =
            canReplaceSiteAirspace(level.getBlockState(pos))

        private fun isLeafLike(state: BlockState): Boolean =
            state.`is`(BlockTags.LEAVES) || state.block is LeavesBlock

        private fun canReplaceSiteAirspace(state: BlockState): Boolean =
            !state.`is`(ModBlocks.OBELISK.get()) &&
                state.fluidState.isEmpty &&
                (state.canBeReplaced() || isLeafLike(state) || isFoliageLikeAirspace(state))

        private fun isFoliageLikeAirspace(state: BlockState): Boolean {
            if (state.block is BushBlock) return true
            val path = BuiltInRegistries.BLOCK.getKey(state.block).path
            return path == "grass" ||
                path == "tall_grass" ||
                path == "fern" ||
                path == "large_fern" ||
                path == "dead_bush" ||
                path.endsWith("_grass") ||
                path.endsWith("_fern") ||
                path.endsWith("_bush") ||
                path.endsWith("_shrub") ||
                path.endsWith("_sprout") ||
                path.endsWith("_shoots") ||
                path.endsWith("_roots") ||
                path.endsWith("_vine") ||
                path.endsWith("_vines") ||
                path.endsWith("_foliage") ||
                path.endsWith("_flower") ||
                path.endsWith("_flowers")
        }

        private fun block(id: String?, fallback: Block): Block {
            val location = id?.let(ResourceLocation::tryParse) ?: return fallback
            val value = BuiltInRegistries.BLOCK.get(location)
            return if (value == Blocks.AIR && location.path != "air") fallback else value
        }

        private fun blocks(ids: List<String>?, fallback: List<Block>): List<Block> =
            ids.orEmpty().mapIndexed { index, id -> block(id, fallback[index % fallback.size]) }.takeIf { it.isNotEmpty() } ?: fallback

        private fun <T> List<T>.shuffled(random: RandomSource): List<T> {
            val copy = toMutableList()
            for (i in copy.lastIndex downTo 1) {
                val j = random.nextInt(i + 1)
                val value = copy[i]
                copy[i] = copy[j]
                copy[j] = value
            }
            return copy
        }

        private data class GraveyardPalette(
            val pedestal: Block,
            val path: List<Block>,
            val grave: List<Block>,
            val structure: List<Block>,
            val decorations: List<Block>
        ) {
            fun path(random: RandomSource): Block = path[random.nextInt(path.size)]
            fun grave(random: RandomSource): Block = grave[random.nextInt(grave.size)]
            fun structure(random: RandomSource): Block = structure[random.nextInt(structure.size)]
            fun decoration(random: RandomSource): Block = decorations[random.nextInt(decorations.size)]
            fun wall(random: RandomSource): Block = if (random.nextBoolean()) Blocks.COBBLESTONE_WALL else Blocks.MOSSY_COBBLESTONE_WALL
            fun headstone(random: RandomSource): Block = if (random.nextBoolean()) Blocks.CHISELED_STONE_BRICKS else wall(random)

            companion object {
                fun from(definition: ObeliskDefinition): GraveyardPalette {
                    val configured = definition.graveyardPalette
                    val legacyStone = listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock, definition.pedestalBlock)
                    val legacyGround = definition.craterFillBlocks
                    val pathIds = configured?.pathBlocks ?: definition.pathBlocks ?: legacyGround
                    val graveIds = configured?.graveBlocks ?: definition.graveBlocks ?: legacyStone
                    val structureIds = configured?.structureBlocks ?: definition.structureBlocks ?: legacyStone
                    val decorationIds = configured?.decorations ?: definition.decorations
                    val pedestalId = configured?.pedestalBlock ?: definition.pedestalBlock ?: definition.meteorCoreBlock
                    return GraveyardPalette(
                        pedestal = block(pedestalId, Blocks.POLISHED_ANDESITE),
                        path = blocks(pathIds, listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS)),
                        grave = blocks(graveIds, listOf(Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS)),
                        structure = blocks(structureIds, listOf(Blocks.POLISHED_ANDESITE, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS)),
                        decorations = blocks(decorationIds, listOf(Blocks.RED_CANDLE, Blocks.SOUL_LANTERN, Blocks.DEAD_BUSH, Blocks.BONE_BLOCK))
                    )
                }
            }
        }

        private enum class TileType {
            FONT_PEDESTAL,
            PATH,
            GRAVE_SINGLE,
            GRAVE_DOUBLE,
            MAUSOLEUM_SMALL,
            SHRINE,
            STATUE_RUIN,
            TREE_STUMP,
            DECOR
        }

        private data class TileCoord(val x: Int, val z: Int) {
            fun relative(direction: Direction): TileCoord = when (direction) {
                Direction.NORTH -> copy(z = z - 1)
                Direction.SOUTH -> copy(z = z + 1)
                Direction.WEST -> copy(x = x - 1)
                Direction.EAST -> copy(x = x + 1)
                else -> this
            }
        }

        private data class TilePlan(
            val coord: TileCoord,
            val groundPos: BlockPos,
            val type: TileType,
            val pathExits: Set<Direction>
        )
    }

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != Level.OVERWORLD) return false

        val definition = ObeliskDataManager.pickRandomObelisk() ?: return false
        val center = findPlacementCenter(level, context.origin()) ?: return false
        val fontPos = buildSite(level, center, definition, context.random()) ?: return false
        if (!level.setBlock(fontPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) return false
        val font = level.getBlockEntity(fontPos) as? ObeliskBlockEntity ?: return false
        font.setDefinition(definition.id)
        font.fillToCapacity()
        font.syncToClients()
        return true
    }

    private fun buildSite(level: WorldGenLevel, center: BlockPos, definition: ObeliskDefinition, random: RandomSource): BlockPos? =
        buildSiteBlocks(level, level::setBlock, center, definition, random)
}
