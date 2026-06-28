package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.ObeliskConstants
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties
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
        private const val ALTAR_HEIGHT = 3
        private const val ALTAR_RADIUS = 3
        private const val ALTAR_MAX_FOUNDATION_DROP = 2
        fun generateDefinitionSiteForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val placementCenter = findPlacementCenter(level, center) ?: return false
            val site = buildSiteBlocks(level, level::setBlock, placementCenter, definition, random) ?: return false
            return placeGeneratedFont(level, site, definition)
        }

        private fun findPlacementCenter(level: LevelAccessor, origin: BlockPos): BlockPos? {
            if (!level.getBlockState(origin).fluidState.isEmpty) return null
            val snapped = snapToUniversalGrid(origin)
            val surfaceY = findSurfaceY(level, snapped.x, snapped.z, scanTopForOrigin(level, origin), ALTAR_HEIGHT + FONT_CLEARANCE + 1) ?: return null
            val center = BlockPos(snapped.x, surfaceY, snapped.z)
            return if (canPlaceElevatedAltarAndFont(level, center)) center else null
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
        ): BuiltSite? {
            val palette = GraveyardPalette.from(definition)
            val tiles = planTiles(level, center, random)
            val graveStyle = GraveStyle.choose(center, definition.id)
            val fontTile = tiles[TileCoord(0, 0)] ?: return null
            val altarSurface = altarSurfaceMap(level, fontTile.groundPos) ?: return null
            if (!canPlaceElevatedAltarAndFont(level, fontTile.groundPos, altarSurface)) return null

            val graveFootprints = mutableSetOf<BlockPos>()
            tiles.values.sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z }).forEach { tile ->
                placeTile(level, setBlock, tile, tiles, palette, graveStyle, graveFootprints, random)
            }
            placeBoundaryAccents(level, setBlock, tiles, palette, random)

            val fontPos = placeElevatedAltar(setBlock, fontTile.groundPos, altarSurface, palette, random)
            return BuiltSite(fontPos, generatedCapacityForSite(definition, tiles))
        }

        private fun generatedCapacityForSite(definition: ObeliskDefinition, tiles: Map<TileCoord, TilePlan>): Double {
            val base = definition.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE
            val tileRadius = tiles.keys.maxOfOrNull { maxOf(abs(it.x), abs(it.z)) } ?: MIN_TILE_RADIUS
            val radiusProgress = ((tileRadius - MIN_TILE_RADIUS).toDouble() / (MAX_TILE_RADIUS - MIN_TILE_RADIUS).toDouble()).coerceIn(0.0, 1.0)
            val footprintProgress = ((tiles.size - 180).toDouble() / 520.0).coerceIn(0.0, 1.25)
            val multiplier = 1.0 + maxOf(radiusProgress * 0.8, footprintProgress)
            return (base * multiplier).coerceIn(base, 1_000_000.0)
        }

        private fun placeGeneratedFont(level: LevelAccessor, site: BuiltSite, definition: ObeliskDefinition): Boolean {
            if (!level.setBlock(site.fontPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) return false
            val font = level.getBlockEntity(site.fontPos) as? ObeliskBlockEntity ?: return false
            font.setDefinition(definition.id)
            font.setGeneratedMaxBlood(site.maxBlood)
            font.fillToCapacity()
            font.syncToClients()
            return true
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
                    candidates[coord] = TilePlan(coord, ground, TileType.DECOR, TileZone.GRAVE_FIELD, emptySet())
                }
            }

            val font = candidates[TileCoord(0, 0)] ?: return emptyMap()
            val paths = linkedSetOf(font.coord)
            edgeTargets(candidates.keys, random).take(3 + random.nextInt(2)).forEach { target ->
                carvePath(paths, candidates, font.coord, target, random)
            }
            paths.filter { manhattan(it) in 3 until radius }
                .shuffled(random)
                .take(2 + random.nextInt(2))
                .forEach { start ->
                    val branch = candidates.keys
                        .filter { it !in paths && chebyshevDistance(it, start) in 3..5 && manhattan(it) <= radius + radius / 3 }
                        .shuffled(random)
                        .firstOrNull()
                    if (branch != null) carvePath(paths, candidates, start, branch, random)
                }

            val denseCenters = candidates.keys
                .filter { it !in paths && manhattan(it) in 5 until radius && it != font.coord }
                .shuffled(random)
                .take(2 + random.nextInt(2))
            val quietCenters = candidates.keys
                .filter { it !in paths && manhattan(it) >= radius - 3 }
                .shuffled(random)
                .take(2)
            val focalTiles = candidates.keys
                .filter { it !in paths && manhattan(it) in 3..7 }
                .shuffled(random)
                .take(2)
                .toSet()
            val trophyTiles = candidates.keys
                .filter { it !in paths && it !in focalTiles && manhattan(it) > 4 && paths.any { path -> chebyshevDistance(it, path) <= 2 } }
                .shuffled(random)
                .take(3)
                .toSet()

            val planned = linkedMapOf<TileCoord, TilePlan>()
            candidates.forEach { (coord, tile) ->
                val exitsForTile = Direction.Plane.HORIZONTAL.filter { direction ->
                    val other = coord.relative(direction)
                    other in paths && isPathable(tile, candidates[other])
                }.toSet()
                val zone = when {
                    coord == TileCoord(0, 0) -> TileZone.APPROACH_PATH
                    coord in paths -> TileZone.APPROACH_PATH
                    coord in focalTiles -> TileZone.FOCAL_RUIN
                    coord in trophyTiles -> TileZone.TROPHY_DISPLAY
                    nearestDistance(coord, denseCenters) <= 2 -> TileZone.DENSE_CLUSTER
                    nearestDistance(coord, quietCenters) <= 2 -> TileZone.QUIET_EDGE
                    manhattan(coord) > radius - 4 && random.nextInt(100) < 22 -> TileZone.TREE_BREAK
                    else -> TileZone.GRAVE_FIELD
                }
                planned[coord] = tile.copy(type = typeForZone(zone, coord, paths, random), zone = zone, pathExits = exitsForTile)
            }
            return planned
        }

        private fun edgeTargets(coords: Set<TileCoord>, random: RandomSource): List<TileCoord> =
            Direction.Plane.HORIZONTAL.mapNotNull { direction ->
                coords.maxByOrNull { coord ->
                    when (direction) {
                        Direction.NORTH -> -coord.z
                        Direction.SOUTH -> coord.z
                        Direction.WEST -> -coord.x
                        Direction.EAST -> coord.x
                        else -> 0
                    }
                }
            }.distinct().shuffled(random)

        private fun carvePath(paths: MutableSet<TileCoord>, candidates: Map<TileCoord, TilePlan>, start: TileCoord, target: TileCoord, random: RandomSource) {
            var current = start
            var guard = MAX_TILE_RADIUS * 4
            while (current != target && guard-- > 0) {
                val stepX = if (current.x < target.x) 1 else if (current.x > target.x) -1 else 0
                val stepZ = if (current.z < target.z) 1 else if (current.z > target.z) -1 else 0
                val next = when {
                    stepX != 0 && stepZ != 0 && random.nextBoolean() -> current.copy(x = current.x + stepX)
                    stepZ != 0 -> current.copy(z = current.z + stepZ)
                    stepX != 0 -> current.copy(x = current.x + stepX)
                    else -> current
                }
                if (next !in candidates || !isPathable(candidates[current], candidates[next])) return
                paths += next
                current = next
            }
        }

        private fun typeForZone(zone: TileZone, coord: TileCoord, paths: Set<TileCoord>, random: RandomSource): TileType {
            if (coord == TileCoord(0, 0)) return TileType.FONT_PEDESTAL
            if (zone == TileZone.APPROACH_PATH) return TileType.PATH
            return when (zone) {
                TileZone.FOCAL_RUIN -> when (random.nextInt(3)) {
                    0 -> TileType.MAUSOLEUM_SMALL
                    1 -> TileType.SHRINE
                    else -> TileType.STATUE_RUIN
                }
                TileZone.TROPHY_DISPLAY -> TileType.TROPHY_DISPLAY
                TileZone.DENSE_CLUSTER -> when (random.nextInt(100)) {
                    in 0..42 -> TileType.GRAVE_DOUBLE
                    in 43..84 -> TileType.GRAVE_SINGLE
                    in 85..93 -> TileType.STATUE_RUIN
                    else -> TileType.SHRINE
                }
                TileZone.QUIET_EDGE -> when (random.nextInt(100)) {
                    in 0..44 -> TileType.TREE_STUMP
                    in 45..74 -> TileType.DECOR
                    in 75..91 -> TileType.GRAVE_SINGLE
                    else -> TileType.STATUE_RUIN
                }
                TileZone.TREE_BREAK -> if (random.nextInt(4) == 0) TileType.DECOR else TileType.TREE_STUMP
                TileZone.GRAVE_FIELD -> {
                    val nearPath = paths.any { chebyshevDistance(coord, it) <= 2 }
                    val roll = random.nextInt(100)
                    when {
                        nearPath && roll < 36 -> TileType.GRAVE_DOUBLE
                        roll < 62 -> TileType.GRAVE_SINGLE
                        roll < 76 -> TileType.GRAVE_DOUBLE
                        roll < 85 -> TileType.MAUSOLEUM_SMALL
                        roll < 93 -> TileType.STATUE_RUIN
                        roll < 97 -> TileType.TREE_STUMP
                        else -> TileType.DECOR
                    }
                }
                TileZone.APPROACH_PATH -> TileType.PATH
            }
        }

        private fun nearestDistance(coord: TileCoord, others: List<TileCoord>): Int =
            others.minOfOrNull { chebyshevDistance(coord, it) } ?: Int.MAX_VALUE

        private fun chebyshevDistance(a: TileCoord, b: TileCoord): Int =
            maxOf(abs(a.x - b.x), abs(a.z - b.z))

        private fun manhattan(coord: TileCoord): Int =
            abs(coord.x) + abs(coord.z)

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
            graveStyle: GraveStyle,
            graveFootprints: MutableSet<BlockPos>,
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
                TileType.GRAVE_SINGLE -> buildSingleGrave(level, setBlock, tile.groundPos, palette, graveStyle, graveFootprints, random)
                TileType.GRAVE_DOUBLE -> buildDoubleGrave(level, setBlock, tile.groundPos, palette, graveStyle, graveFootprints, random)
                TileType.MAUSOLEUM_SMALL -> buildMausoleum(level, setBlock, tile.groundPos, palette, random)
                TileType.SHRINE -> buildShrine(level, setBlock, tile.groundPos, palette, random)
                TileType.STATUE_RUIN -> buildRuin(level, setBlock, tile.groundPos, palette, random)
                TileType.TREE_STUMP -> buildStump(level, setBlock, tile.groundPos, random)
                TileType.TROPHY_DISPLAY -> buildTrophyCourt(level, setBlock, tile.groundPos, palette, random)
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

        private fun buildSingleGrave(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, style: GraveStyle, graveFootprints: MutableSet<BlockPos>, random: RandomSource) {
            Direction.Plane.HORIZONTAL.toList().shuffled(random).forEach { direction ->
                val footprint = graveLineFootprint(base, direction)
                if (canReserveGraveFootprint(level, footprint, graveFootprints)) {
                    graveFootprints += footprint
                    buildGraveLine(level, setBlock, base, direction, palette, style, random)
                    return
                }
            }
            buildDecor(level, setBlock, base, palette, random)
        }

        private fun buildDoubleGrave(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, style: GraveStyle, graveFootprints: MutableSet<BlockPos>, random: RandomSource) {
            Direction.Plane.HORIZONTAL.toList().shuffled(random).forEach { direction ->
                val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
                val first = graveLineFootprint(base.relative(side), direction)
                val second = graveLineFootprint(base.relative(side.opposite), direction)
                val footprint = first + second + base
                if (canReserveGraveFootprint(level, footprint, graveFootprints)) {
                    graveFootprints += footprint
                    buildGraveLine(level, setBlock, base.relative(side), direction, palette, style, random)
                    buildGraveLine(level, setBlock, base.relative(side.opposite), direction, palette, style, random)
                    placeGround(level, setBlock, base, if (random.nextBoolean()) palette.path(random) else style.ground)
                    scatterTileDetails(level, setBlock, base, palette, random, 3)
                    return
                }
            }
            buildSingleGrave(level, setBlock, base, palette, style, graveFootprints, random)
        }

        private fun buildGraveLine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, head: BlockPos, direction: Direction, palette: GraveyardPalette, style: GraveStyle, random: RandomSource) {
            val body = head.relative(direction)
            val foot = head.relative(direction, 2)
            if (!canUseTileGround(level, head, 2) || !canUseTileGround(level, body, 1)) return
            placeGround(level, setBlock, head, style.ground)
            placeGround(level, setBlock, body, style.ground)
            if (canUseTileGround(level, foot, 1)) placeGround(level, setBlock, foot, if (random.nextBoolean()) palette.path(random) else style.ground)
            placeSupportedAbove(level, setBlock, head.above(), style.headstone)
            if (random.nextInt(3) != 0) placeSupportedAbove(level, setBlock, body.above(), style.lowMarker)
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            listOf(head.relative(side), body.relative(side.opposite), foot.relative(side)).forEach { pos ->
                if (random.nextInt(3) != 0 && canUseTileGround(level, pos, 1)) {
                    if (random.nextBoolean()) placeGround(level, setBlock, pos, palette.path(random))
                    placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                }
            }
        }

        private fun graveLineFootprint(head: BlockPos, direction: Direction): List<BlockPos> =
            listOf(head, head.relative(direction), head.relative(direction, 2))

        private fun canReserveGraveFootprint(level: LevelAccessor, footprint: List<BlockPos>, reserved: Set<BlockPos>): Boolean =
            footprint.distinct().size == footprint.size &&
                footprint.none { it in reserved } &&
                footprint.all { canUseTileGround(level, it, clearance = 1) }

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

        private fun buildTrophyCourt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeTrophyDisplay(level, setBlock, base, palette, random)
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                val pos = base.relative(direction)
                if (canUseTileGround(level, pos, 1)) {
                    placeGround(level, setBlock, pos, if (random.nextBoolean()) palette.structure(random) else palette.path(random))
                    if (random.nextInt(3) == 0) {
                        placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                    }
                }
            }
            if (random.nextBoolean()) {
                val rear = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
                val left = if (rear.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
                listOf(base.relative(rear).relative(left), base.relative(rear).relative(left.opposite)).forEach { pos ->
                    if (canUseTileGround(level, pos, 2)) placeSupportedAbove(level, setBlock, pos.above(), palette.wall(random))
                }
            }
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
            if (random.nextInt(3) == 0) {
                placeTrophyDisplay(level, setBlock, base, palette, random)
            } else {
                placeSupportedAbove(level, setBlock, base.above(), palette.decoration(random))
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun scatterTileDetails(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource, attempts: Int) {
            repeat(attempts) {
                val pos = base.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2)
                if (canUseTileGround(level, pos, 1)) {
                    if (random.nextInt(4) == 0) placeGround(level, setBlock, pos, palette.path(random))
                    when (random.nextInt(6)) {
                        0 -> placeTrophyDisplay(level, setBlock, pos, palette, random)
                        1, 2, 3, 4 -> placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                    }
                }
            }
        }

        private fun placeBoundaryAccents(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: GraveyardPalette, random: RandomSource) {
            tiles.values.forEach { tile ->
                for (direction in Direction.Plane.HORIZONTAL) {
                    if (tile.coord.relative(direction) in tiles || random.nextInt(100) >= 28) continue
                    val pos = tile.groundPos.relative(direction, TILE_SIZE / 2)
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextInt(4) == 0) {
                            placeTrophyDisplay(level, setBlock, pos, palette, random)
                        } else {
                            placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) palette.headstone(random) else palette.wall(random))
                        }
                    }
                }
            }
        }

        private fun placeTrophyDisplay(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource): Boolean {
            val trophy = palette.trophy(random) ?: return false
            if (!canUseTileGround(level, base, 2)) return false
            placeGround(level, setBlock, base, palette.structure(random))
            return placeSupportedAbove(level, setBlock, base.above(), trophy)
        }

        private fun placeGround(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            if (!canUseTileGround(level, pos, clearance = 1)) return false
            return setBlock(pos, generatedState(block), 3)
        }

        private fun placeSupportedAbove(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            val below = pos.below()
            if (!isSupportedGround(level, below, level.getBlockState(below))) return false
            if (!canReplaceDecoration(level, pos)) return false
            return setBlock(pos, generatedState(block), 3)
        }

        private fun generatedState(block: Block): BlockState {
            val state = block.defaultBlockState()
            return if (state.hasProperty(BlockStateProperties.LIT)) state.setValue(BlockStateProperties.LIT, true) else state
        }

        private fun placeElevatedAltar(
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            surfaceByOffset: Map<Pair<Int, Int>, Int>,
            palette: GraveyardPalette,
            random: RandomSource
        ): BlockPos {
            val baseY = center.y
            surfaceByOffset.forEach { (offset, surfaceY) ->
                val dx = offset.first
                val dz = offset.second
                val distance = maxOf(abs(dx), abs(dz))
                val topY = baseY + when {
                    distance <= 1 -> ALTAR_HEIGHT
                    distance == 2 -> 2
                    else -> 1
                }
                val block = when {
                    distance <= 1 -> palette.pedestal
                    distance == 2 -> palette.structure(random)
                    else -> if (random.nextInt(5) == 0) Blocks.MOSSY_STONE_BRICKS else palette.structure(random)
                }
                for (y in surfaceY + 1..topY) {
                    setBlock(BlockPos(center.x + dx, y, center.z + dz), block.defaultBlockState(), 3)
                }
            }
            val fontPos = center.above(ALTAR_HEIGHT + 1)
            for (dy in 1..FONT_CLEARANCE) {
                setBlock(fontPos.above(dy), Blocks.AIR.defaultBlockState(), 3)
            }
            return fontPos
        }

        private fun canPlaceElevatedAltarAndFont(level: LevelAccessor, center: BlockPos): Boolean {
            val surfaceByOffset = altarSurfaceMap(level, center) ?: return false
            return canPlaceElevatedAltarAndFont(level, center, surfaceByOffset)
        }

        private fun canPlaceElevatedAltarAndFont(level: LevelAccessor, center: BlockPos, surfaceByOffset: Map<Pair<Int, Int>, Int>): Boolean {
            val fontPos = center.above(ALTAR_HEIGHT + 1)
            for (dy in 0..FONT_CLEARANCE) {
                if (!canReplaceSiteAirspace(level.getBlockState(fontPos.above(dy)))) return false
            }
            return surfaceByOffset.isNotEmpty()
        }

        private fun altarSurfaceMap(level: LevelAccessor, center: BlockPos): Map<Pair<Int, Int>, Int>? {
            val surfaces = linkedMapOf<Pair<Int, Int>, Int>()
            for (dx in -ALTAR_RADIUS..ALTAR_RADIUS) {
                for (dz in -ALTAR_RADIUS..ALTAR_RADIUS) {
                    if (maxOf(abs(dx), abs(dz)) > ALTAR_RADIUS) continue
                    val x = center.x + dx
                    val z = center.z + dz
                    val surfaceY = findSurfaceY(level, x, z, center.y + 1, 1) ?: return null
                    if (surfaceY > center.y || center.y - surfaceY > ALTAR_MAX_FOUNDATION_DROP) return null
                    val topY = center.y + when {
                        maxOf(abs(dx), abs(dz)) <= 1 -> ALTAR_HEIGHT
                        maxOf(abs(dx), abs(dz)) == 2 -> 2
                        else -> 1
                    }
                    for (y in surfaceY + 1..topY) {
                        if (!canReplaceSiteAirspace(level.getBlockState(BlockPos(x, y, z)))) return null
                    }
                    surfaces[dx to dz] = surfaceY
                }
            }
            return surfaces
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

        private fun blocksOrEmpty(ids: List<String>?): List<Block> =
            ids.orEmpty().mapNotNull { id ->
                val location = ResourceLocation.tryParse(id) ?: return@mapNotNull null
                val value = BuiltInRegistries.BLOCK.get(location)
                if (value == Blocks.AIR && location.path != "air") null else value
            }.distinct()

        private fun pathBlocks(ids: List<String>?, fallback: List<Block>): List<Block> =
            blocks(ids, fallback).filterNot(::isStoneBrickPathBlock).takeIf { it.isNotEmpty() } ?: fallback

        private fun isStoneBrickPathBlock(block: Block): Boolean =
            block == Blocks.STONE_BRICKS ||
                block == Blocks.MOSSY_STONE_BRICKS ||
                block == Blocks.CRACKED_STONE_BRICKS ||
                block == Blocks.CHISELED_STONE_BRICKS

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
            val decorations: List<Block>,
            val trophies: List<Block>
        ) {
            fun path(random: RandomSource): Block = path[random.nextInt(path.size)]
            fun grave(random: RandomSource): Block = grave[random.nextInt(grave.size)]
            fun structure(random: RandomSource): Block = structure[random.nextInt(structure.size)]
            fun decoration(random: RandomSource): Block = decorations[random.nextInt(decorations.size)]
            fun trophy(random: RandomSource): Block? = trophies.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
            fun wall(random: RandomSource): Block = if (random.nextBoolean()) Blocks.COBBLESTONE_WALL else Blocks.MOSSY_COBBLESTONE_WALL
            fun headstone(random: RandomSource): Block = if (random.nextBoolean()) Blocks.CHISELED_STONE_BRICKS else wall(random)

            companion object {
                fun from(definition: ObeliskDefinition): GraveyardPalette {
                    val configured = definition.graveyardPalette
                    val legacyStone = listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock, definition.pedestalBlock)
                    val trophyIds = configured?.trophyBlocks ?: definition.trophyBlocks ?: buildList {
                        addAll(configured?.pathBlocks.orEmpty())
                        addAll(definition.pathBlocks.orEmpty())
                        addAll(configured?.graveBlocks.orEmpty())
                        addAll(definition.graveBlocks.orEmpty())
                        addAll(configured?.structureBlocks.orEmpty())
                        addAll(definition.structureBlocks.orEmpty())
                        configured?.pedestalBlock?.let(::add)
                        definition.pedestalBlock?.let(::add)
                        addAll(legacyStone)
                    }.distinct().takeIf { it.isNotEmpty() }
                    val decorationIds = configured?.decorations ?: definition.decorations
                    return GraveyardPalette(
                        pedestal = Blocks.POLISHED_ANDESITE,
                        path = pathBlocks(configured?.pathBlocks ?: definition.pathBlocks, listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT, Blocks.MOSSY_COBBLESTONE, Blocks.PODZOL)),
                        grave = listOf(Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.CRACKED_STONE_BRICKS),
                        structure = listOf(Blocks.POLISHED_ANDESITE, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICKS),
                        decorations = blocks(decorationIds, listOf(Blocks.RED_CANDLE, Blocks.SOUL_LANTERN, Blocks.DEAD_BUSH, Blocks.BONE_BLOCK)),
                        trophies = blocksOrEmpty(trophyIds)
                    )
                }
            }
        }

        private data class GraveStyle(
            val ground: Block,
            val headstone: Block,
            val lowMarker: Block
        ) {
            companion object {
                private val STYLES = listOf(
                    GraveStyle(Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE_SLAB),
                    GraveStyle(Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.SMOOTH_STONE_SLAB),
                    GraveStyle(Blocks.CRACKED_STONE_BRICKS, Blocks.COBBLESTONE_WALL, Blocks.RED_CANDLE),
                    GraveStyle(Blocks.MOSSY_COBBLESTONE, Blocks.BONE_BLOCK, Blocks.SKELETON_SKULL)
                )

                fun choose(center: BlockPos, definitionId: String): GraveStyle {
                    val hash = center.x * 73428767 xor center.y * 912271 xor center.z * 4235437 xor definitionId.hashCode()
                    return STYLES[Math.floorMod(hash, STYLES.size)]
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
            TROPHY_DISPLAY,
            DECOR
        }

        private enum class TileZone {
            APPROACH_PATH,
            GRAVE_FIELD,
            DENSE_CLUSTER,
            QUIET_EDGE,
            FOCAL_RUIN,
            TROPHY_DISPLAY,
            TREE_BREAK
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
            val zone: TileZone,
            val pathExits: Set<Direction>
        )

        private data class BuiltSite(
            val fontPos: BlockPos,
            val maxBlood: Double
        )
    }

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != Level.OVERWORLD) return false

        val definition = ObeliskDataManager.pickRandomObelisk() ?: return false
        val center = findPlacementCenter(level, context.origin()) ?: return false
        val site = buildSite(level, center, definition, context.random()) ?: return false
        return placeGeneratedFont(level, site, definition)
    }

    private fun buildSite(level: WorldGenLevel, center: BlockPos, definition: ObeliskDefinition, random: RandomSource): BuiltSite? =
        buildSiteBlocks(level, level::setBlock, center, definition, random)
}
