package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.ObeliskConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
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
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    companion object {
        private const val TILE_SIZE = 4
        private const val MAX_TILE_RADIUS = 16
        private const val MIN_TILE_RADIUS = 10
        private const val FONT_CLEARANCE = 3
        private const val ALTAR_HEIGHT = 3
        private const val ALTAR_RADIUS = 3
        private const val ALTAR_MAX_FOUNDATION_DROP = 7
        private const val TERRAIN_SCAN_UP = 28
        private const val SITE_GRID_CHUNKS = 6
        private const val SITE_GRID_BLOCKS = SITE_GRID_CHUNKS * 16
        private const val SITE_GRID_SCAN_RADIUS = 1
        private const val TARGET_RARITY_CHUNKS = 64
        private const val ACTIVE_SITE_THRESHOLD = 36
        private const val SITE_MAX_BLOCK_RADIUS = MAX_TILE_RADIUS * TILE_SIZE + TILE_SIZE
        private const val SITE_LAYOUT_SALT = -0x61c8864680b583ebL
        private const val SITE_CHUNK_DETAIL_SALT = 0x2545f4914f6cdd1dL
        private const val SITE_PRIORITY_SALT = 0x13a5ba1d7c4e9f21L
        fun generateDefinitionSiteForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val maxScanY = scanTopForOrigin(level, center)
            val anchors = listOf(snapToUniversalGrid(center), center)
            anchors.distinct().forEach { anchor ->
                altarCandidateOffsets().forEach { (dx, dz) ->
                    val probe = BlockPos(anchor.x + dx, center.y, anchor.z + dz)
                    val surfaceY = findSurfaceY(level, probe.x, probe.z, maxScanY, 1) ?: return@forEach
                    val placementCenter = normalizeAltarCenter(level, probe.atY(surfaceY)) ?: return@forEach
                    if (!canPlaceElevatedAltarAndFont(level, placementCenter)) return@forEach
                    val site = buildSiteBlocks(level, level::setBlock, placementCenter, definition, random) ?: return@forEach
                    if (placeGeneratedFont(level, site, definition)) return true
                }
            }
            return false
        }

        fun generateChunkSlicedSiteForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            seed: Long
        ): Boolean {
            return ObeliskFeature(NoneFeatureConfiguration.CODEC)
                .generateChunkSlicedSiteForTestsInternal(level, center, definitionId, seed)
        }

        fun generatedSiteAnchorsForChunkRangeForTests(
            minChunkX: Int,
            maxChunkX: Int,
            minChunkZ: Int,
            maxChunkZ: Int
        ): List<BlockPos> {
            val minCellX = Math.floorDiv(minChunkX * 16, SITE_GRID_BLOCKS) - SITE_GRID_SCAN_RADIUS
            val maxCellX = Math.floorDiv(maxChunkX * 16 + 15, SITE_GRID_BLOCKS) + SITE_GRID_SCAN_RADIUS
            val minCellZ = Math.floorDiv(minChunkZ * 16, SITE_GRID_BLOCKS) - SITE_GRID_SCAN_RADIUS
            val maxCellZ = Math.floorDiv(maxChunkZ * 16 + 15, SITE_GRID_BLOCKS) + SITE_GRID_SCAN_RADIUS
            val anchors = linkedSetOf<BlockPos>()
            for (cellX in minCellX..maxCellX) {
                for (cellZ in minCellZ..maxCellZ) {
                    if (!shouldMaterializeSite(cellX, cellZ)) continue
                    anchors += siteAnchorForCell(cellX, cellZ)
                }
            }
            return anchors.toList()
        }

        fun generatePlacedSitesForChunkRangeForTests(
            level: ServerLevel,
            minChunkX: Int,
            maxChunkX: Int,
            minChunkZ: Int,
            maxChunkZ: Int
        ): List<BlockPos> {
            if (level.dimension() != Level.OVERWORLD) return emptyList()
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            val placedFonts = mutableListOf<BlockPos>()
            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    placedFonts += feature.generatePlacedSitesForChunkForTests(level, ChunkPos(chunkX, chunkZ))
                }
            }
            return placedFonts
        }

        private fun scanTopForOrigin(level: LevelAccessor, origin: BlockPos): Int =
            if (origin.y <= level.minBuildHeight + 2) level.maxBuildHeight - 2 else origin.y + 4

        private fun snapToUniversalGrid(origin: BlockPos): BlockPos {
            val cellX = Math.floorDiv(origin.x, TILE_SIZE)
            val cellZ = Math.floorDiv(origin.z, TILE_SIZE)
            return BlockPos(cellX * TILE_SIZE + TILE_SIZE / 2, origin.y, cellZ * TILE_SIZE + TILE_SIZE / 2)
        }

        private fun siteAnchorForCell(cellX: Int, cellZ: Int): BlockPos {
            val random = RandomSource.create(siteSeed(cellX, cellZ))
            val offsetRange = SITE_GRID_CHUNKS
            val offsetX = (random.nextInt(offsetRange * 2 + 1) - offsetRange) * TILE_SIZE
            val offsetZ = (random.nextInt(offsetRange * 2 + 1) - offsetRange) * TILE_SIZE
            val x = cellX * SITE_GRID_BLOCKS + SITE_GRID_BLOCKS / 2 + offsetX
            val z = cellZ * SITE_GRID_BLOCKS + SITE_GRID_BLOCKS / 2 + offsetZ
            return snapToUniversalGrid(BlockPos(x, 0, z))
        }

        private fun siteSeed(cellX: Int, cellZ: Int): Long {
            var value = 0x6A09E667F3BCC909L
            value = value xor (cellX.toLong() * -7046029254386353131L)
            value = value xor (cellZ.toLong() * -4658895280553007687L)
            value = value xor (cellX.toLong() shl 32)
            value = value xor cellZ.toLong()
            return value
        }

        private fun shouldGenerateSite(cellX: Int, cellZ: Int): Boolean =
            RandomSource.create(siteSeed(cellX, cellZ)).nextInt(TARGET_RARITY_CHUNKS) < ACTIVE_SITE_THRESHOLD

        private fun shouldMaterializeSite(cellX: Int, cellZ: Int): Boolean {
            if (!shouldGenerateSite(cellX, cellZ)) return false
            val anchor = siteAnchorForCell(cellX, cellZ)
            val priority = sitePriority(cellX, cellZ)
            for (otherX in cellX - 1..cellX + 1) {
                for (otherZ in cellZ - 1..cellZ + 1) {
                    if (otherX == cellX && otherZ == cellZ) continue
                    if (!shouldGenerateSite(otherX, otherZ)) continue
                    val otherAnchor = siteAnchorForCell(otherX, otherZ)
                    val dx = otherAnchor.x - anchor.x
                    val dz = otherAnchor.z - anchor.z
                    if (dx * dx + dz * dz > (SITE_MAX_BLOCK_RADIUS * 2) * (SITE_MAX_BLOCK_RADIUS * 2)) continue
                    val otherPriority = sitePriority(otherX, otherZ)
                    if (otherPriority > priority) return false
                    if (otherPriority == priority && (otherX > cellX || (otherX == cellX && otherZ > cellZ))) return false
                }
            }
            return true
        }

        private fun sitePriority(cellX: Int, cellZ: Int): Long =
            RandomSource.create(siteSeed(cellX, cellZ) xor SITE_PRIORITY_SALT).nextLong()

        private fun normalizeAltarCenter(level: LevelAccessor, center: BlockPos): BlockPos? {
            var highestSurface = center.y
            for (dx in -ALTAR_RADIUS..ALTAR_RADIUS) {
                for (dz in -ALTAR_RADIUS..ALTAR_RADIUS) {
                    val x = center.x + dx
                    val z = center.z + dz
                    val surfaceY = findSurfaceY(level, x, z, center.y + ALTAR_MAX_FOUNDATION_DROP, 1) ?: return null
                    highestSurface = maxOf(highestSurface, surfaceY)
                }
            }
            return center.atY(highestSurface)
        }

        private fun findChunkLocalAltarCenter(level: LevelAccessor, origin: BlockPos, chunk: ChunkPos): BlockPos? =
            findNearestViableAltarCenter(level, origin, origin.y + ALTAR_MAX_FOUNDATION_DROP) { candidate ->
                candidate.x >= chunk.minBlockX &&
                    candidate.x <= chunk.maxBlockX &&
                    candidate.z >= chunk.minBlockZ &&
                    candidate.z <= chunk.maxBlockZ
            }

        private fun findNearestViableAltarCenter(
            level: LevelAccessor,
            origin: BlockPos,
            maxScanY: Int,
            allowed: (BlockPos) -> Boolean
        ): BlockPos? {
            val valid = altarCandidateOffsets().asSequence()
                .map { (dx, dz) -> BlockPos(origin.x + dx, origin.y, origin.z + dz) }
                .filter(allowed)
                .mapNotNull { candidate ->
                    val surfaceY = findSurfaceY(level, candidate.x, candidate.z, maxScanY, 1) ?: return@mapNotNull null
                    val base = BlockPos(candidate.x, surfaceY, candidate.z)
                    val normalized = normalizeAltarCenter(level, base) ?: return@mapNotNull null
                    val surface = altarSurfaceMap(level, normalized) ?: return@mapNotNull null
                    if (!canPlaceElevatedAltarAndFont(level, normalized, surface)) return@mapNotNull null
                    normalized
                }
                .toList()
            val ordering = compareBy<BlockPos> { abs(it.x - origin.x) + abs(it.z - origin.z) }.thenBy { it.y }
            return valid
                .filter { isChunkInterior(it, ALTAR_RADIUS) }
                .minWithOrNull(ordering)
                ?: valid.minWithOrNull(ordering)
        }

        private fun altarCandidateOffsets(): List<Pair<Int, Int>> {
            val offsets = mutableListOf(0 to 0)
            for (radius in 1..6) {
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (maxOf(abs(dx), abs(dz)) != radius) continue
                        offsets += dx to dz
                    }
                }
            }
            return offsets
        }

        private fun isChunkInterior(pos: BlockPos, margin: Int): Boolean {
            val localX = Math.floorMod(pos.x, 16)
            val localZ = Math.floorMod(pos.z, 16)
            return localX in margin..(15 - margin) && localZ in margin..(15 - margin)
        }

        private fun buildSiteBlocks(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            definition: ObeliskDefinition,
            random: RandomSource
        ): BuiltSite? {
            val palette = GraveyardPalette.from(definition, center)
            val tiles = planTiles(level, center, palette, random)
            val graveStyle = palette.graveStyle(center, definition.id)
            val fontTile = tiles[TileCoord(0, 0)] ?: return null
            val altarCenter = normalizeAltarCenter(level, fontTile.groundPos) ?: return null
            val altarSurface = altarSurfaceMap(level, altarCenter) ?: return null
            if (!canPlaceElevatedAltarAndFont(level, altarCenter, altarSurface)) return null

            val graveFootprints = mutableSetOf<BlockPos>()
            val graveRecords = mutableListOf<GravePlacement>()
            tiles.values.sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z }).forEach { tile ->
                placeTile(level, setBlock, tile, tiles, palette, graveStyle, graveFootprints, graveRecords, random)
            }
            placeIntersectionTrophies(level, setBlock, tiles, palette, random)
            placeBoundaryAccents(level, setBlock, tiles, palette, random)
            graveRecords.forEach { enforceGraveLine(level, setBlock, it) }

            val fontPos = placeElevatedAltar(level, setBlock, altarCenter, altarSurface, palette, random)
            return BuiltSite(fontPos, generatedCapacityForSite(definition, tiles))
        }

        private fun generatedCapacityForSite(definition: ObeliskDefinition, tiles: Map<TileCoord, TilePlan>): Double {
            val tileRadius = tiles.keys.maxOfOrNull { maxOf(abs(it.x), abs(it.z)) } ?: MIN_TILE_RADIUS
            return generatedCapacityForSite(definition, tileRadius, tiles.size)
        }

        private fun generatedCapacityForSite(definition: ObeliskDefinition, tileRadius: Int, footprintSize: Int): Double {
            val base = definition.maxBlood ?: ObeliskConstants.MAX_BLOOD_STORAGE
            val radiusProgress = ((tileRadius - MIN_TILE_RADIUS).toDouble() / (MAX_TILE_RADIUS - MIN_TILE_RADIUS).toDouble()).coerceIn(0.0, 1.0)
            val footprintProgress = ((footprintSize - 180).toDouble() / 520.0).coerceIn(0.0, 1.25)
            val multiplier = 1.15 + maxOf(radiusProgress * 0.8, footprintProgress)
            return (base * multiplier).coerceIn(base, 1_000_000.0).roundToInt().toDouble()
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

        private fun planTiles(level: LevelAccessor, center: BlockPos, palette: GraveyardPalette, random: RandomSource): Map<TileCoord, TilePlan> {
            val radius = MIN_TILE_RADIUS + random.nextInt(MAX_TILE_RADIUS - MIN_TILE_RADIUS + 1)
            val candidates = linkedMapOf<TileCoord, TilePlan>()
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val coord = TileCoord(x, z)
                    if (!insideOrganicShape(coord, radius)) continue
                    val ground = tileGround(level, center, coord) ?: continue
                    if (!canUseTileGround(level, ground, clearance = 2)) continue
                    candidates[coord] = TilePlan(coord, ground, TileType.DECOR, TileZone.GRAVE_FIELD, emptySet())
                }
            }

            val font = candidates[TileCoord(0, 0)] ?: return emptyMap()
            val paths = linkedSetOf(font.coord)
            terrainTargets(candidates, font.coord, random).take(5).forEach { target ->
                carvePath(paths, candidates, font.coord, target, radius, random)
            }
            carveBranchingMaze(paths, candidates, radius, random)
            carveLoopConnectors(paths, candidates, radius, random)
            widenJunctionCourts(paths, candidates, radius, random)

            val occupied = organicallyExpandedFootprint(candidates, paths, radius, random)
            val junctions = pathJunctions(paths)
            val denseCenters = occupied
                .filter { it !in paths && manhattan(it) in 5 until radius && it != font.coord }
                .shuffled(random)
                .take(8 + random.nextInt(6))
            val quietCenters = occupied
                .filter { it !in paths && manhattan(it) >= radius - 3 }
                .shuffled(random)
                .take(3)
            val focalTiles = (junctions + occupied
                .filter { it !in paths && manhattan(it) in 3..10 }
                .shuffled(random)
                .take(14 + random.nextInt(8)))
                .toSet()
            val trophyTiles = (junctions.flatMap { junction ->
                Direction.Plane.HORIZONTAL.map { junction.relative(it) }
            } + occupied
                .filter { it !in paths && it !in focalTiles && manhattan(it) > 4 && paths.any { path -> chebyshevDistance(it, path) <= 1 } })
                .distinct()
                .shuffled(random)
                .take(10 + random.nextInt(5))
                .toSet()

            val planned = linkedMapOf<TileCoord, TilePlan>()
            occupied.mapNotNull { coord -> candidates[coord]?.let { coord to it } }.forEach { (coord, tile) ->
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
                planned[coord] = tile.copy(type = typeForZone(zone, coord, paths, palette, random), zone = zone, pathExits = exitsForTile)
            }
            return planned
        }

        private fun planChunkTiles(level: LevelAccessor, center: BlockPos, chunk: ChunkPos, palette: GraveyardPalette, random: RandomSource): ChunkSitePlan {
            val radius = MIN_TILE_RADIUS + random.nextInt(MAX_TILE_RADIUS - MIN_TILE_RADIUS + 1)
            val shapeCoords = linkedSetOf<TileCoord>()
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val coord = TileCoord(x, z)
                    if (insideOrganicShape(coord, radius)) shapeCoords += coord
                }
            }
            val dummyCandidates = shapeCoords.associateWith { coord ->
                TilePlan(coord, BlockPos.ZERO, TileType.DECOR, TileZone.GRAVE_FIELD, emptySet())
            }
            val paths = linkedSetOf(TileCoord(0, 0))
            terrainTargets(dummyCandidates, TileCoord(0, 0), random).take(5).forEach { target ->
                carvePath(paths, dummyCandidates, TileCoord(0, 0), target, radius, random)
            }
            carveBranchingMaze(paths, dummyCandidates, radius, random)
            carveLoopConnectors(paths, dummyCandidates, radius, random)
            widenJunctionCourts(paths, dummyCandidates, radius, random)

            val occupied = organicallyExpandedFootprint(dummyCandidates, paths, radius, random)
            val junctions = pathJunctions(paths)
            val denseCenters = occupied
                .filter { it !in paths && manhattan(it) in 5 until radius && it != TileCoord(0, 0) }
                .shuffled(random)
                .take(8 + random.nextInt(6))
            val quietCenters = occupied
                .filter { it !in paths && manhattan(it) >= radius - 3 }
                .shuffled(random)
                .take(3)
            val focalTiles = (junctions + occupied
                .filter { it !in paths && manhattan(it) in 3..10 }
                .shuffled(random)
                .take(14 + random.nextInt(8)))
                .toSet()
            val trophyTiles = (junctions.flatMap { junction ->
                Direction.Plane.HORIZONTAL.map { junction.relative(it) }
            } + occupied
                .filter { it !in paths && it !in focalTiles && manhattan(it) > 4 && paths.any { path -> chebyshevDistance(it, path) <= 1 } })
                .distinct()
                .shuffled(random)
                .take(10 + random.nextInt(5))
                .toSet()

            val planned = linkedMapOf<TileCoord, TilePlan>()
            occupied.forEach { coord ->
                val blockX = center.x + coord.x * TILE_SIZE
                val blockZ = center.z + coord.z * TILE_SIZE
                if (blockX < chunk.minBlockX || blockX > chunk.maxBlockX || blockZ < chunk.minBlockZ || blockZ > chunk.maxBlockZ) return@forEach
                val ground = tileGround(level, center, coord) ?: return@forEach
                if (!canUseTileGround(level, ground, clearance = 2)) return@forEach
                val exitsForTile = Direction.Plane.HORIZONTAL.filter { direction -> coord.relative(direction) in paths }.toSet()
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
                planned[coord] = TilePlan(coord, ground, typeForZone(zone, coord, paths, palette, random), zone, exitsForTile)
            }
            if (center.x in chunk.minBlockX..chunk.maxBlockX && center.z in chunk.minBlockZ..chunk.maxBlockZ && TileCoord(0, 0) !in planned) {
                tileGround(level, center, TileCoord(0, 0))?.let { ground ->
                    if (isSupportedGround(level, ground, level.getBlockState(ground))) {
                        planned[TileCoord(0, 0)] = TilePlan(
                            TileCoord(0, 0),
                            ground,
                            TileType.FONT_PEDESTAL,
                            TileZone.APPROACH_PATH,
                            Direction.Plane.HORIZONTAL.filter { direction -> TileCoord(0, 0).relative(direction) in paths }.toSet()
                        )
                    }
                }
            }
            return ChunkSitePlan(planned, radius, occupied.size)
        }

        private fun carveBranchingMaze(
            paths: MutableSet<TileCoord>,
            candidates: Map<TileCoord, TilePlan>,
            radius: Int,
            random: RandomSource
        ) {
            repeat(8 + random.nextInt(6)) {
                val starts = paths
                    .filter { manhattan(it) in 3 until radius * 2 }
                    .shuffled(random)
                val start = starts.firstOrNull() ?: return
                val branch = candidates.keys.asSequence()
                    .filter { it !in paths && chebyshevDistance(it, start) in 5..11 }
                    .filter { manhattan(it) <= radius + radius / 2 && directionSpread(start, it) >= 2 }
                    .toList()
                    .shuffled(random)
                    .firstOrNull() ?: return@repeat
                carvePath(paths, candidates, start, branch, radius, random)

                if (random.nextInt(100) < 68) {
                    val loopBack = paths
                        .filter { it != start && chebyshevDistance(it, branch) in 5..14 && directionSpread(it, branch) >= 2 }
                        .shuffled(random)
                        .firstOrNull()
                    if (loopBack != null) carvePath(paths, candidates, branch, loopBack, radius, random)
                }
            }
        }

        private fun carveLoopConnectors(
            paths: MutableSet<TileCoord>,
            candidates: Map<TileCoord, TilePlan>,
            radius: Int,
            random: RandomSource
        ) {
            repeat(5 + random.nextInt(4)) {
                val start = paths.toList().shuffled(random).firstOrNull() ?: return
                val target = paths
                    .filter { it != start && chebyshevDistance(it, start) in 8..18 && directionSpread(it, start) >= 3 }
                    .shuffled(random)
                    .firstOrNull() ?: return@repeat
                val connector = findWindingPathThroughTerrain(candidates, start, target, radius, random) ?: return@repeat
                val newTiles = connector.count { it !in paths }
                if (newTiles in 3..28) paths += connector
            }
        }

        private fun widenJunctionCourts(
            paths: MutableSet<TileCoord>,
            candidates: Map<TileCoord, TilePlan>,
            radius: Int,
            random: RandomSource
        ) {
            val seeds = paths
                .filter { coord -> pathNeighborCount(coord, paths) >= 2 || random.nextInt(100) < 18 }
                .filter { manhattan(it) <= radius + radius / 2 }
                .shuffled(random)
                .take(8 + random.nextInt(6))
            seeds.forEach { seed ->
                val courtRadius = if (random.nextInt(4) == 0) 2 else 1
                for (dx in -courtRadius..courtRadius) {
                    for (dz in -courtRadius..courtRadius) {
                        if (abs(dx) + abs(dz) > courtRadius + 1) continue
                        val coord = seed.offset(dx, dz)
                        if (coord !in candidates) continue
                        if (hasPathableNeighbor(coord, paths, candidates)) paths += coord
                    }
                }
            }
        }

        private fun organicallyExpandedFootprint(
            candidates: Map<TileCoord, TilePlan>,
            paths: Set<TileCoord>,
            radius: Int,
            random: RandomSource
        ): Set<TileCoord> {
            val occupied = linkedSetOf<TileCoord>()
            val pathList = paths.toList()
            occupied += paths
            occupied += paths.flatMap { path ->
                Direction.Plane.HORIZONTAL
                    .map { path.relative(it) }
                    .filter { it in candidates && isPathable(candidates[path], candidates[it]) && random.nextInt(100) < 88 }
            }

            val lobeSeeds = paths
                .filter { manhattan(it) in 3..(radius + radius / 2) }
                .shuffled(random)
                .take(18 + random.nextInt(11))
            lobeSeeds.forEach { seed ->
                val lobeRadius = 2 + random.nextInt(3)
                for (dx in -lobeRadius..lobeRadius) {
                    for (dz in -lobeRadius..lobeRadius) {
                        val coord = seed.offset(dx, dz)
                        if (coord !in candidates) continue
                        if (chebyshevDistance(coord, seed) > lobeRadius) continue
                        if (nearestDistance(coord, pathList) > 4) continue
                        val edgeNoise = abs((coord.x * 37 + coord.z * 19 + seed.x * 11 + seed.z * 23) % 100)
                        val keepChance = 92 - chebyshevDistance(coord, seed) * 13 - maxOf(0, nearestDistance(coord, pathList) - 1) * 8
                        if (edgeNoise < keepChance && hasPathableNeighbor(coord, occupied, candidates)) occupied += coord
                    }
                }
            }

            val sideYards = paths
                .filter { manhattan(it) in 2..radius }
                .shuffled(random)
                .take(28 + random.nextInt(18))
            sideYards.forEach { seed ->
                Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                    val first = seed.relative(direction)
                    val second = first.relative(direction)
                    if (first in candidates && isPathable(candidates[seed], candidates[first])) occupied += first
                    if (random.nextInt(100) < 62 && second in candidates && isPathable(candidates[first], candidates[second])) occupied += second
                    val third = second.relative(direction)
                    if (random.nextInt(100) < 20 && third in candidates && isPathable(candidates[second], candidates[third])) occupied += third
                }
            }

            candidates.keys
                .filter { it !in occupied && nearestDistance(it, pathList) <= 2 && random.nextInt(100) < 42 }
                .filter { hasPathableNeighbor(it, occupied, candidates) }
                .forEach { occupied += it }

            return occupied
                .filter { it == TileCoord(0, 0) || it in paths || hasPathableNeighbor(it, occupied, candidates) }
                .toSet()
        }

        private fun pathNeighborCount(coord: TileCoord, paths: Set<TileCoord>): Int =
            Direction.Plane.HORIZONTAL.count { coord.relative(it) in paths }

        private fun hasPathableNeighbor(coord: TileCoord, occupied: Set<TileCoord>, candidates: Map<TileCoord, TilePlan>): Boolean =
            Direction.Plane.HORIZONTAL.any { direction ->
                val neighbor = coord.relative(direction)
                neighbor in occupied && isPathable(candidates[coord], candidates[neighbor])
            }

        private fun pathJunctions(paths: Set<TileCoord>): List<TileCoord> =
            paths.filter { pathNeighborCount(it, paths) >= 3 }

        private fun terrainTargets(candidates: Map<TileCoord, TilePlan>, start: TileCoord, random: RandomSource): List<TileCoord> {
            val startY = candidates[start]?.groundPos?.y ?: 0
            val sectors = (0 until 7).toList().shuffled(random).take(5)
            val targets = sectors.mapNotNull { sector ->
                candidates.values
                    .filter { angularSector(it.coord, sectors = 7) == sector && radialDistance(it.coord) >= MIN_TILE_RADIUS * 0.72 }
                    .maxWithOrNull(
                        compareBy<TilePlan> { radialDistance(it.coord) + abs(it.groundPos.y - startY) * 2.0 }
                            .thenBy { it.coord.x }
                            .thenBy { it.coord.z }
                    )
                    ?.coord
            }.toMutableList()
            targets += candidates.values
                .filter { abs(it.groundPos.y - startY) >= 3 }
                .shuffled(random)
                .take(4)
                .map { it.coord }
            return targets.distinct().shuffled(random)
        }

        private fun radialDistance(coord: TileCoord): Double =
            sqrt((coord.x * coord.x + coord.z * coord.z).toDouble())

        private fun angularSector(coord: TileCoord, sectors: Int): Int {
            val normalized = (atan2(coord.z.toDouble(), coord.x.toDouble()) + Math.PI) / (Math.PI * 2.0)
            return Math.floor(normalized * sectors).toInt().coerceIn(0, sectors - 1)
        }

        private fun carvePath(paths: MutableSet<TileCoord>, candidates: Map<TileCoord, TilePlan>, start: TileCoord, target: TileCoord, radius: Int, random: RandomSource) {
            val routed = findWindingPathThroughTerrain(candidates, start, target, radius, random)
            if (routed != null) {
                paths += routed
                return
            }
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

        private fun findWindingPathThroughTerrain(
            candidates: Map<TileCoord, TilePlan>,
            start: TileCoord,
            target: TileCoord,
            radius: Int,
            random: RandomSource
        ): List<TileCoord>? {
            val route = mutableListOf<TileCoord>()
            var current = start
            route += current
            windingWaypoints(start, target, radius, candidates.keys, random).forEach { waypoint ->
                val segment = findPathThroughTerrain(candidates, current, waypoint) ?: return null
                route += segment.drop(1)
                current = waypoint
            }
            if (route.last() != target) {
                val segment = findPathThroughTerrain(candidates, current, target) ?: return null
                route += segment.drop(1)
            }
            return route
        }

        private fun windingWaypoints(
            start: TileCoord,
            target: TileCoord,
            radius: Int,
            candidates: Set<TileCoord>,
            random: RandomSource
        ): List<TileCoord> {
            val waypoints = mutableListOf<TileCoord>()
            val segments = 3 + random.nextInt(3)
            val dx = target.x - start.x
            val dz = target.z - start.z
            val perpendicular = if (abs(dx) > abs(dz)) TileCoord(0, 1) else TileCoord(1, 0)
            var side = if (random.nextBoolean()) 1 else -1
            for (step in 1 until segments) {
                val baseX = start.x + dx * step / segments
                val baseZ = start.z + dz * step / segments
                val bend = (2 + random.nextInt(maxOf(3, radius / 2))) * side
                side *= -1
                val raw = TileCoord(
                    (baseX + perpendicular.x * bend).coerceIn(-radius, radius),
                    (baseZ + perpendicular.z * bend).coerceIn(-radius, radius)
                )
                nearestCandidate(raw, candidates)?.let { waypoints += it }
            }
            return waypoints.distinct().filter { it != start && it != target }
        }

        private fun nearestCandidate(target: TileCoord, candidates: Set<TileCoord>): TileCoord? =
            candidates
                .filter { chebyshevDistance(it, target) <= 4 }
                .minWithOrNull(compareBy<TileCoord> { manhattanTo(it, target) }.thenBy { it.x }.thenBy { it.z })

        private fun directionSpread(a: TileCoord, b: TileCoord): Int =
            minOf(abs(a.x - b.x), abs(a.z - b.z))

        private fun findPathThroughTerrain(candidates: Map<TileCoord, TilePlan>, start: TileCoord, target: TileCoord): List<TileCoord>? {
            if (start !in candidates || target !in candidates) return null
            val frontier = ArrayDeque<TileCoord>()
            val cameFrom = mutableMapOf<TileCoord, TileCoord?>()
            frontier += start
            cameFrom[start] = null
            while (frontier.isNotEmpty() && cameFrom.size <= candidates.size) {
                val current = frontier.removeFirst()
                if (current == target) break
                Direction.Plane.HORIZONTAL
                    .map { current.relative(it) }
                    .filter { next -> next in candidates && next !in cameFrom && isPathable(candidates[current], candidates[next]) }
                    .sortedWith(compareBy<TileCoord> { terrainRouteScore(candidates[current], candidates[it], it, target) }.thenBy { it.x }.thenBy { it.z })
                    .forEach { next ->
                        cameFrom[next] = current
                        frontier += next
                    }
            }
            if (target !in cameFrom) return null
            val route = mutableListOf<TileCoord>()
            var current: TileCoord? = target
            while (current != null) {
                route += current
                current = cameFrom[current]
            }
            return route.asReversed()
        }

        private fun terrainRouteScore(current: TilePlan?, next: TilePlan?, nextCoord: TileCoord, target: TileCoord): Int {
            if (current == null || next == null) return Int.MAX_VALUE
            val yDelta = abs(current.groundPos.y - next.groundPos.y)
            val contourBonus = if (yDelta == 0) -2 else 0
            val climbCost = yDelta * 5
            val turnTowardTarget = manhattanTo(nextCoord, target)
            return turnTowardTarget + climbCost + contourBonus
        }

        private fun typeForZone(zone: TileZone, coord: TileCoord, paths: Set<TileCoord>, palette: GraveyardPalette, random: RandomSource): TileType {
            if (coord == TileCoord(0, 0)) return TileType.FONT_PEDESTAL
            if (zone == TileZone.APPROACH_PATH) return TileType.PATH
            return when (zone) {
                TileZone.FOCAL_RUIN -> palette.focalStructure(random)
                TileZone.TROPHY_DISPLAY -> TileType.TROPHY_DISPLAY
                TileZone.DENSE_CLUSTER -> when (random.nextInt(100)) {
                    in 0..27 -> TileType.GRAVE_DOUBLE
                    in 28..45 -> TileType.GRAVE_SINGLE
                    else -> palette.clusterStructure(random)
                }
                TileZone.QUIET_EDGE -> when (random.nextInt(100)) {
                    in 0..19 -> TileType.TREE_STUMP
                    in 20..29 -> TileType.DECOR
                    in 30..57 -> TileType.GRAVE_SINGLE
                    else -> palette.edgeStructure(random)
                }
                TileZone.TREE_BREAK -> if (random.nextInt(4) == 0) TileType.DECOR else TileType.TREE_STUMP
                TileZone.GRAVE_FIELD -> {
                    val nearPath = paths.any { chebyshevDistance(coord, it) <= 2 }
                    val roll = random.nextInt(100)
                    when {
                        nearPath && roll < 30 -> TileType.GRAVE_DOUBLE
                        roll < 28 -> TileType.GRAVE_SINGLE
                        roll < 46 -> TileType.GRAVE_DOUBLE
                        roll < 95 -> palette.fieldStructure(random)
                        roll < 99 -> TileType.TREE_STUMP
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

        private fun manhattanTo(a: TileCoord, b: TileCoord): Int =
            abs(a.x - b.x) + abs(a.z - b.z)

        private fun insideOrganicShape(coord: TileCoord, radius: Int): Boolean {
            if (coord == TileCoord(0, 0)) return true
            val distance = sqrt((coord.x * coord.x + coord.z * coord.z).toDouble())
            val angle = atan2(coord.z.toDouble(), coord.x.toDouble())
            val wave = sin(angle * 3.0 + radius * 0.71) * 0.16 +
                cos(angle * 5.0 - radius * 0.37) * 0.11 +
                sin(angle * 9.0 + coord.x * 0.19 - coord.z * 0.23) * 0.07
            val sectorNoise = signedNoise(coord.x / 2, coord.z / 2, radius) * 0.09
            val localRadius = (radius * (0.78 + wave + sectorNoise)).coerceIn(radius * 0.48, radius.toDouble())
            return distance <= localRadius
        }

        private fun signedNoise(x: Int, z: Int, salt: Int): Double {
            val mixed = x * 73428767 xor z * 912931 xor salt * 42349
            val value = Math.floorMod(mixed, 2001) / 1000.0
            return value - 1.0
        }

        private fun tileGround(level: LevelAccessor, center: BlockPos, coord: TileCoord): BlockPos? {
            val x = center.x + coord.x * TILE_SIZE
            val z = center.z + coord.z * TILE_SIZE
            val y = findSurfaceY(level, x, z, center.y + TERRAIN_SCAN_UP, 2) ?: return null
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
            graveRecords: MutableList<GravePlacement>,
            random: RandomSource
        ) {
            when (tile.type) {
                TileType.FONT_PEDESTAL -> {
                    placeGround(level, setBlock, tile.groundPos, palette.path(random))
                    tile.pathExits.forEach { direction -> placePathArm(level, setBlock, tile, tiles[tile.coord.relative(direction)], direction, palette, random) }
                    scatterTileDetails(level, setBlock, tile.groundPos, palette, random, 3)
                }
                TileType.PATH -> {
                    placeGround(level, setBlock, tile.groundPos, palette.path(random))
                    tile.pathExits.forEach { direction -> placePathArm(level, setBlock, tile, tiles[tile.coord.relative(direction)], direction, palette, random) }
                    placeStepForRaisedNeighbors(level, setBlock, tile, tiles)
                    scatterTileDetails(level, setBlock, tile.groundPos, palette, random, 2)
                }
                TileType.GRAVE_SINGLE -> buildBurialPlot(level, setBlock, tile.groundPos, palette, graveStyle, graveFootprints, graveRecords, random, 1)
                TileType.GRAVE_DOUBLE -> buildBurialPlot(level, setBlock, tile.groundPos, palette, graveStyle, graveFootprints, graveRecords, random, 2)
                TileType.MAUSOLEUM_SMALL -> buildMausoleum(level, setBlock, tile.groundPos, palette, random)
                TileType.SHRINE -> buildShrine(level, setBlock, tile.groundPos, palette, random)
                TileType.STATUE_RUIN -> buildRuin(level, setBlock, tile.groundPos, palette, random)
                TileType.CRYPT_ENTRY -> buildCryptEntry(level, setBlock, tile.groundPos, palette, random)
                TileType.BROKEN_ARCH -> buildBrokenArch(level, setBlock, tile.groundPos, palette, random)
                TileType.MEMORIAL_COURT -> buildMemorialCourt(level, setBlock, tile.groundPos, palette, random)
                TileType.OSSUARY -> buildOssuary(level, setBlock, tile.groundPos, palette, random)
                TileType.TREE_STUMP -> buildStump(level, setBlock, tile.groundPos, random)
                TileType.TROPHY_DISPLAY -> buildTrophyCourt(level, setBlock, tile.groundPos, palette, random)
                TileType.DECOR -> buildDecor(level, setBlock, tile.groundPos, palette, random)
            }
            if (tile.zone == TileZone.DENSE_CLUSTER && tile.type != TileType.PATH && tile.type != TileType.FONT_PEDESTAL) {
                reinforceDenseCluster(level, setBlock, tile.groundPos, palette, random)
            }
        }

        private fun reinforceDenseCluster(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            palette: GraveyardPalette,
            random: RandomSource
        ) {
            val directions = Direction.Plane.HORIZONTAL.toList().shuffled(random)
            directions.take(3).forEachIndexed { index, direction ->
                val rough = base.relative(direction)
                val pos = terrainGroundNear(level, rough, base.y + 4, 1) ?: return@forEachIndexed
                if (!canUseTileGround(level, pos, 2)) return@forEachIndexed
                placeGround(level, setBlock, pos, if (index == 0 || random.nextBoolean()) palette.grave(random) else palette.path(random))
                when (index) {
                    0 -> placeSupportedAbove(level, setBlock, pos.above(), palette.headstone(random))
                    1 -> {
                        placeSupportedAbove(level, setBlock, pos.above(), palette.headstone(random))
                        if (random.nextBoolean()) {
                            placeSupportedAbove(level, setBlock, pos.above(2), if (random.nextBoolean()) Blocks.SOUL_LANTERN else Blocks.RED_CANDLE)
                        }
                    }
                    else -> placeSupportedAbove(level, setBlock, pos.above(), if (random.nextBoolean()) palette.decoration(random) else palette.wall(random))
                }
            }
        }

        private fun placePathArm(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tile: TilePlan, neighbor: TilePlan?, direction: Direction, palette: GraveyardPalette, random: RandomSource) {
            val maxScanY = maxOf(tile.groundPos.y, neighbor?.groundPos?.y ?: tile.groundPos.y) + 5
            var previous = tile.groundPos
            for (step in 1 until TILE_SIZE) {
                val xz = tile.groundPos.relative(direction, step)
                val y = findSurfaceY(level, xz.x, xz.z, maxScanY, 1) ?: continue
                val pos = BlockPos(xz.x, y, xz.z)
                if (canUseTileGround(level, pos, clearance = 1)) {
                    placeGround(level, setBlock, pos, palette.path(random))
                    if (abs(pos.y - previous.y) == 1) placeSlopeStep(level, setBlock, previous, pos)
                    previous = pos
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

        private fun placeSlopeStep(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, a: BlockPos, b: BlockPos) {
            val lower = if (a.y < b.y) a else b
            val slabPos = lower.above()
            if (canReplaceDecoration(level, slabPos) && isSupportedGround(level, lower, level.getBlockState(lower))) {
                setBlock(slabPos, Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 3)
            }
        }

        private fun buildBurialPlot(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            palette: GraveyardPalette,
            style: GraveStyle,
            graveFootprints: MutableSet<BlockPos>,
            graveRecords: MutableList<GravePlacement>,
            random: RandomSource,
            targetClumps: Int
        ) {
            val direction = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            val starts = mutableListOf<BlockPos>()
            for (row in -3..3 step 3) {
                for (column in -1..3) {
                    starts += base.relative(side, row).relative(direction, column)
                }
            }
            var placedClumps = 0
            starts.shuffled(random).forEach { roughHead ->
                if (placedClumps >= targetClumps) return@forEach
                val center = terrainGroundNear(level, roughHead, base.y + 5, 2) ?: return@forEach
                val heads = listOf(center.relative(side), center, center.relative(side.opposite))
                if (!canReserveGraveClump(level, heads, direction, graveFootprints)) return@forEach
                val placed = heads.mapNotNull { head ->
                    if (!buildGraveLine(level, setBlock, head, direction, palette, style, random)) return@mapNotNull null
                    GravePlacement(head, direction, style.headstone)
                }
                if (placed.size == heads.size) {
                    graveFootprints += heads.flatMap { graveLineFootprint(it, direction) }
                    graveRecords += placed
                    placedClumps++
                }
            }
            if (placedClumps == 0) {
                buildGraveClump(level, setBlock, base, direction, side, palette, style, graveFootprints, graveRecords, random)
            } else {
                Direction.Plane.HORIZONTAL.toList().shuffled(random).take(4).forEach { edge ->
                    val edgePos = terrainGroundNear(level, base.relative(edge, 2), base.y + 5, 1) ?: return@forEach
                    placeGround(level, setBlock, edgePos, palette.path(random))
                    placeSupportedAbove(level, setBlock, edgePos.above(), if (random.nextBoolean()) palette.wall(random) else palette.decoration(random))
                }
            }
        }

        private fun buildGraveClump(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, direction: Direction, side: Direction, palette: GraveyardPalette, style: GraveStyle, graveFootprints: MutableSet<BlockPos>, graveRecords: MutableList<GravePlacement>, random: RandomSource) {
            val center = terrainGroundNear(level, base, base.y + 5, 2) ?: return buildDecor(level, setBlock, base, palette, random)
            val heads = listOf(center.relative(side), center, center.relative(side.opposite))
            if (!canReserveGraveClump(level, heads, direction, graveFootprints)) return buildDecor(level, setBlock, base, palette, random)
            val placed = heads.mapNotNull { head ->
                if (!buildGraveLine(level, setBlock, head, direction, palette, style, random)) return@mapNotNull null
                GravePlacement(head, direction, style.headstone)
            }
            if (placed.size != heads.size) return buildDecor(level, setBlock, base, palette, random)
            graveFootprints += heads.flatMap { graveLineFootprint(it, direction) }
            graveRecords += placed
        }

        private fun buildDoubleGrave(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, style: GraveStyle, graveFootprints: MutableSet<BlockPos>, graveRecords: MutableList<GravePlacement>, random: RandomSource) {
            val direction = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            buildGraveClump(level, setBlock, base.relative(direction), direction, side, palette, style, graveFootprints, graveRecords, random)
            buildGraveClump(level, setBlock, base.relative(direction.opposite), direction, side, palette, style, graveFootprints, graveRecords, random)
            placeGround(level, setBlock, base, palette.path(random))
            scatterTileDetails(level, setBlock, base, palette, random, 2)
        }

        private fun buildGraveLine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, head: BlockPos, direction: Direction, palette: GraveyardPalette, style: GraveStyle, random: RandomSource): Boolean {
            val body = head.relative(direction)
            val headstoneBase = head.relative(direction, 2)
            if (!canBuildGraveLineAt(level, head, direction)) return false
            placeGround(level, setBlock, head, Blocks.GRAVEL)
            placeGround(level, setBlock, body, Blocks.GRAVEL)
            placeSupportedAbove(level, setBlock, headstoneBase.above(), style.headstone)
            val side = if (direction.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            listOf(headstoneBase.relative(side), headstoneBase.relative(side.opposite)).forEach { pos ->
                if (random.nextInt(100) < 45 && canUseTileGround(level, pos, 1)) {
                    placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                }
            }
            return true
        }

        private fun enforceGraveLine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, grave: GravePlacement) {
            val head = grave.head
            val body = head.relative(grave.direction)
            val headstoneBase = head.relative(grave.direction, 2)
            if (!isSupportedGround(level, head, level.getBlockState(head))) return
            if (!isSupportedGround(level, body, level.getBlockState(body))) return
            if (!isSupportedGround(level, headstoneBase, level.getBlockState(headstoneBase))) return
            setBlock(head, generatedState(Blocks.GRAVEL), 3)
            setBlock(body, generatedState(Blocks.GRAVEL), 3)
            clearGraveSides(level, setBlock, head)
            clearGraveSides(level, setBlock, body)
            listOf(head.above(), body.above()).forEach { pos ->
                val state = level.getBlockState(pos)
                if (canReplaceSiteAirspace(state) || state.hasProperty(BlockStateProperties.LIT)) {
                    setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                }
            }
            setBlock(headstoneBase.above(), generatedState(grave.headstone), 3)
        }

        private fun graveLineFootprint(head: BlockPos, direction: Direction): List<BlockPos> =
            listOf(head, head.relative(direction), head.relative(direction, 2))

        private fun canBuildGraveLineAt(level: LevelAccessor, head: BlockPos, direction: Direction): Boolean {
            val body = head.relative(direction)
            val headstoneBase = head.relative(direction, 2)
            val footprint = listOf(head, body, headstoneBase)
            if (!footprint.all { sameChunk(it, head) }) return false
            if (head.y != body.y || body.y != headstoneBase.y) return false
            return isProperlyBuriedGraveCell(level, head) &&
                isProperlyBuriedGraveCell(level, body) &&
                canUseTileGround(level, headstoneBase, 2)
        }

        private fun sameChunk(a: BlockPos, b: BlockPos): Boolean =
            Math.floorDiv(a.x, 16) == Math.floorDiv(b.x, 16) && Math.floorDiv(a.z, 16) == Math.floorDiv(b.z, 16)

        private fun canReserveGraveFootprint(level: LevelAccessor, footprint: List<BlockPos>, reserved: Set<BlockPos>): Boolean =
            footprint.distinct().size == footprint.size &&
                footprint.none { it in reserved } &&
                footprint.withIndex().all { (index, pos) -> canUseTileGround(level, pos, clearance = if (index == 2) 2 else 1) }

        private fun canReserveGraveClump(level: LevelAccessor, heads: List<BlockPos>, direction: Direction, reserved: Set<BlockPos>): Boolean {
            val footprint = heads.flatMap { graveLineFootprint(it, direction) }
            if (footprint.distinct().size != footprint.size) return false
            if (footprint.any { it in reserved }) return false
            return heads.all { canBuildGraveLineAt(level, it, direction) }
        }

        private fun isProperlyBuriedGraveCell(level: LevelAccessor, pos: BlockPos): Boolean {
            if (!canUseTileGround(level, pos, 1)) return false
            return Direction.Plane.HORIZONTAL.all { direction ->
                val neighbor = pos.relative(direction)
                val neighborState = level.getBlockState(neighbor)
                !neighborState.isAir && isSupportedGround(level, neighbor, neighborState)
            }
        }

        private fun clearGraveSides(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos) {
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val sideAir = pos.relative(direction).above()
                val state = level.getBlockState(sideAir)
                if (canReplaceSiteAirspace(state)) {
                    setBlock(sideAir, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        private fun buildMausoleum(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            if (buildLargeCrypt(level, setBlock, base, palette, random)) return
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

        private fun buildLargeCrypt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource): Boolean {
            val door = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            val surfaces = linkedMapOf<Pair<Int, Int>, BlockPos>()
            for (dx in -2..2) {
                for (dz in -2..2) {
                    val ground = terrainGroundNear(level, base.offset(dx, 0, dz), base.y + 5, 3) ?: return false
                    if (abs(ground.y - base.y) > 2) return false
                    surfaces[dx to dz] = ground
                }
            }
            surfaces.forEach { (offset, pos) ->
                val dx = offset.first
                val dz = offset.second
                val edge = abs(dx) == 2 || abs(dz) == 2
                val isDoor = (door == Direction.NORTH && dz == -2 && abs(dx) <= 1) ||
                    (door == Direction.SOUTH && dz == 2 && abs(dx) <= 1) ||
                    (door == Direction.WEST && dx == -2 && abs(dz) <= 1) ||
                    (door == Direction.EAST && dx == 2 && abs(dz) <= 1)
                placeGround(level, setBlock, pos, if (edge) palette.structure(random) else palette.path(random))
                if (edge && !isDoor) {
                    placeSupportedAbove(level, setBlock, pos.above(), palette.structure(random))
                    if (random.nextInt(100) < 65) placeSupportedAbove(level, setBlock, pos.above(2), if (random.nextBoolean()) palette.wall(random) else palette.structure(random))
                }
            }
            surfaces[0 to 0]?.let { center ->
                placeSupportedAbove(level, setBlock, center.above(), palette.headstone(random))
                placeSupportedAbove(level, setBlock, center.above(2), if (random.nextBoolean()) Blocks.SOUL_LANTERN else Blocks.SKELETON_SKULL)
            }
            scatterTileDetails(level, setBlock, base, palette, random, 6)
            return true
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
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                for (step in 1..4) {
                    val pos = terrainGroundNear(level, base.relative(direction, step), base.y + 5, 2) ?: continue
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextBoolean()) placeGround(level, setBlock, pos, palette.structure(random))
                        placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) palette.headstone(random) else palette.wall(random))
                        if (random.nextInt(3) == 0) placeSupportedAbove(level, setBlock, pos.above(2), palette.wall(random))
                    }
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildCryptEntry(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            val facing = Direction.Plane.HORIZONTAL.toList().shuffled(random).first()
            val side = if (facing.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            val apron = listOf(base, base.relative(facing.opposite), base.relative(facing.opposite, 2))
            if (!apron.all { canUseTileGround(level, it, 2) }) return
            apron.forEachIndexed { index, pos ->
                placeGround(level, setBlock, pos, if (index == apron.lastIndex) palette.path(random) else palette.structure(random))
            }
            listOf(base.relative(side), base.relative(side.opposite)).forEach { flank ->
                if (canUseTileGround(level, flank, 2)) {
                    placeGround(level, setBlock, flank, palette.structure(random))
                    placeSupportedAbove(level, setBlock, flank.above(), palette.wall(random))
                    if (random.nextBoolean()) placeSupportedAbove(level, setBlock, flank.above(2), palette.wall(random))
                }
            }
            val lintelLeft = base.relative(side).above(2)
            val lintelRight = base.relative(side.opposite).above(2)
            val lintelCenter = base.above(3)
            placeSupportedAbove(level, setBlock, base.above(), palette.headstone(random))
            if (canReplaceDecoration(level, lintelLeft) && canReplaceDecoration(level, lintelRight) && canReplaceDecoration(level, lintelCenter)) {
                setBlock(lintelLeft, generatedState(palette.structure(random)), 3)
                setBlock(lintelRight, generatedState(palette.structure(random)), 3)
                setBlock(lintelCenter, generatedState(palette.structure(random)), 3)
            }
            scatterTileDetails(level, setBlock, base, palette, random, 4)
        }

        private fun buildBrokenArch(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            val axis = if (random.nextBoolean()) Direction.EAST else Direction.NORTH
            val side = if (axis.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            val piers = listOf(base.relative(axis, 2), base.relative(axis.opposite, 2))
            if (!piers.all { canUseTileGround(level, it, 3) }) return
            piers.forEach { pier ->
                placeGround(level, setBlock, pier, palette.structure(random))
                placeSupportedAbove(level, setBlock, pier.above(), palette.structure(random))
                placeSupportedAbove(level, setBlock, pier.above(2), if (random.nextBoolean()) palette.wall(random) else palette.structure(random))
            }
            if (random.nextBoolean()) {
                val cap = piers.shuffled(random).first().above(3)
                if (canReplaceDecoration(level, cap)) {
                    setBlock(cap, generatedState(palette.structure(random)), 3)
                }
            }
            for (step in -1..1) {
                val pos = base.relative(axis, step)
                if (canUseTileGround(level, pos, 1)) {
                    placeGround(level, setBlock, pos, if (step == 0) palette.path(random) else palette.structure(random))
                }
            }
            listOf(base.relative(side), base.relative(side.opposite)).forEach { flank ->
                if (canUseTileGround(level, flank, 1) && random.nextBoolean()) {
                    placeSupportedAbove(level, setBlock, flank.above(), palette.headstone(random))
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildMemorialCourt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val pos = base.offset(dx, 0, dz)
                    if (!canUseTileGround(level, pos, 2)) return
                }
            }
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val pos = base.offset(dx, 0, dz)
                    val edge = abs(dx) == 1 || abs(dz) == 1
                    placeGround(level, setBlock, pos, if (edge) palette.path(random) else palette.structure(random))
                    if (abs(dx) == 1 && abs(dz) == 1) {
                        placeSupportedAbove(level, setBlock, pos.above(), if (random.nextBoolean()) palette.wall(random) else palette.decoration(random))
                    }
                }
            }
            placeSupportedAbove(level, setBlock, base.above(), palette.headstone(random))
            if (random.nextBoolean()) {
                placeSupportedAbove(level, setBlock, base.above(2), palette.decoration(random))
            }
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val trophyBase = base.relative(direction, 2)
                if (canUseTileGround(level, trophyBase, 2) && random.nextInt(100) < 75) {
                    placeTrophyDisplay(level, setBlock, trophyBase, palette, random)
                }
            }
        }

        private fun buildOssuary(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeGround(level, setBlock, base, palette.structure(random))
            val pile = listOf(
                base.above(),
                base.above(2),
                base.relative(Direction.Plane.HORIZONTAL.toList().shuffled(random).first()).above()
            )
            pile.forEachIndexed { index, pos ->
                if (canReplaceDecoration(level, pos)) {
                    val block = when {
                        index == 1 && random.nextBoolean() -> palette.headstone(random)
                        random.nextBoolean() -> palette.decoration(random)
                        else -> palette.wall(random)
                    }
                    setBlock(pos, generatedState(block), 3)
                }
            }
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(2).forEach { direction ->
                val scatter = base.relative(direction)
                if (canUseTileGround(level, scatter, 1)) {
                    placeGround(level, setBlock, scatter, if (random.nextBoolean()) palette.grave(random) else palette.path(random))
                    if (random.nextBoolean()) placeSupportedAbove(level, setBlock, scatter.above(), palette.headstone(random))
                }
            }
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
            val placedHeadstone = if (random.nextInt(100) < 42) {
                placeSupportedAbove(level, setBlock, base.above(), palette.headstone(random))
            } else if (random.nextInt(3) == 0) {
                placeTrophyDisplay(level, setBlock, base, palette, random)
            } else {
                placeSupportedAbove(level, setBlock, base.above(), palette.decoration(random))
            }
            if (placedHeadstone) {
                Direction.Plane.HORIZONTAL.toList().shuffled(random).take(2).forEach { direction ->
                    val flank = base.relative(direction)
                    if (canUseTileGround(level, flank, 1)) {
                        placeGround(level, setBlock, flank, if (random.nextBoolean()) palette.path(random) else palette.grave(random))
                        placeSupportedAbove(level, setBlock, flank.above(), if (random.nextBoolean()) palette.decoration(random) else palette.headstone(random))
                    }
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 5)
        }

        private fun scatterTileDetails(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: GraveyardPalette, random: RandomSource, attempts: Int) {
            repeat(attempts) {
                val rough = base.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2)
                val pos = terrainGroundNear(level, rough, base.y + 4, 1) ?: return@repeat
                if (canUseTileGround(level, pos, 1)) {
                    if (random.nextInt(4) == 0) placeGround(level, setBlock, pos, palette.path(random))
                    when (random.nextInt(6)) {
                        1, 2 -> placeSupportedAbove(level, setBlock, pos.above(), palette.headstone(random))
                        3, 4 -> placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                    }
                }
            }
        }

        private fun placeIntersectionTrophies(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: GraveyardPalette, random: RandomSource) {
            val pathCoords = tiles.values.filter { it.type == TileType.PATH }.map { it.coord }.toSet()
            tiles.values
                .filter { it.type == TileType.PATH }
                .filter { pathNeighborCount(it.coord, pathCoords) >= 3 }
                .shuffled(random)
                .take(12)
                .forEach { tile ->
                    Direction.Plane.HORIZONTAL.toList().shuffled(random).forEach { direction ->
                        val pos = terrainGroundNear(level, tile.groundPos.relative(direction, 2), tile.groundPos.y + 4, 2) ?: return@forEach
                        if (placeTrophyDisplay(level, setBlock, pos, palette, random)) return@forEach
                    }
                }
        }

        private fun placeBoundaryAccents(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: GraveyardPalette, random: RandomSource) {
            tiles.values.forEach { tile ->
                for (direction in Direction.Plane.HORIZONTAL) {
                    if (tile.coord.relative(direction) in tiles || random.nextInt(100) >= 18) continue
                    val pos = tile.groundPos.relative(direction, TILE_SIZE / 2)
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextInt(100) < 45) {
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

        private fun terrainGroundNear(level: LevelAccessor, rough: BlockPos, maxY: Int, clearance: Int): BlockPos? {
            val y = findSurfaceY(level, rough.x, rough.z, maxY, clearance) ?: return null
            return BlockPos(rough.x, y, rough.z)
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
            level: LevelAccessor,
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
                    distance == 0 -> Blocks.GILDED_BLACKSTONE
                    distance <= 1 -> Blocks.POLISHED_BLACKSTONE
                    distance == 2 -> if ((abs(dx) + abs(dz)) % 2 == 0) Blocks.POLISHED_BLACKSTONE_BRICKS else palette.structure(random)
                    else -> if (random.nextInt(4) == 0) Blocks.CHISELED_POLISHED_BLACKSTONE else palette.structure(random)
                }
                for (y in surfaceY + 1..topY) {
                    setBlock(BlockPos(center.x + dx, y, center.z + dz), block.defaultBlockState(), 3)
                }
            }
            placeAltarWelcomeDetails(level, setBlock, center, palette)
            val fontPos = center.above(ALTAR_HEIGHT + 1)
            for (dy in 1..FONT_CLEARANCE) {
                setBlock(fontPos.above(dy), Blocks.AIR.defaultBlockState(), 3)
            }
            return fontPos
        }

        private fun placeAltarWelcomeDetails(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            palette: GraveyardPalette
        ) {
            val baseY = center.y
            val goldInlay = generatedState(Blocks.GILDED_BLACKSTONE)
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val inner = center.relative(direction)
                val mid = center.relative(direction, 2)
                val outer = center.relative(direction, 3)
                setBlock(BlockPos(inner.x, baseY + ALTAR_HEIGHT, inner.z), goldInlay, 3)
                setBlock(BlockPos(mid.x, baseY + 3, mid.z), Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 3)
                setBlock(BlockPos(outer.x, baseY + 2, outer.z), Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 3)
            }
            listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1).forEach { (dx, dz) ->
                setBlock(center.offset(dx, ALTAR_HEIGHT + 1, dz), generatedState(Blocks.YELLOW_CANDLE), 3)
            }
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val trophyBase = center.relative(direction, 2)
                if (!placeTrophyDisplay(level, setBlock, trophyBase, palette, RandomSource.create(center.asLong() xor direction.ordinal.toLong()))) {
                    setBlock(trophyBase.above(2), generatedState(palette.headstone(RandomSource.create(center.asLong() xor direction.ordinal.toLong()))), 3)
                }
            }
            listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).forEach { (dx, dz) ->
                setBlock(center.offset(dx, 3, dz), generatedState(Blocks.SOUL_LANTERN), 3)
            }
            listOf(-3 to -3, -3 to 3, 3 to -3, 3 to 3).forEach { (dx, dz) ->
                setBlock(center.offset(dx, 2, dz), generatedState(Blocks.RED_CANDLE), 3)
            }
        }

        private fun canPlaceElevatedAltarAndFont(level: LevelAccessor, center: BlockPos): Boolean {
            val surfaceByOffset = altarSurfaceMap(level, center) ?: return false
            return canPlaceElevatedAltarAndFont(level, center, surfaceByOffset)
        }

        private fun canPlaceElevatedAltarAndFont(level: LevelAccessor, center: BlockPos, surfaceByOffset: Map<Pair<Int, Int>, Int>): Boolean {
            val fontPos = center.above(ALTAR_HEIGHT + 1)
            for (dy in 0..FONT_CLEARANCE) {
                if (!canClearAltarAirspace(level.getBlockState(fontPos.above(dy)))) return false
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
                        if (!canClearAltarAirspace(level.getBlockState(BlockPos(x, y, z)))) return null
                    }
                    altarWelcomeDetailYOffset(dx, dz)?.let { detailYOffset ->
                        val detailY = center.y + detailYOffset
                        if (detailY > topY && !canClearAltarAirspace(level.getBlockState(BlockPos(x, detailY, z)))) return null
                    }
                    surfaces[dx to dz] = surfaceY
                }
            }
            return surfaces
        }

        private fun altarWelcomeDetailYOffset(dx: Int, dz: Int): Int? =
            when {
                abs(dx) == 1 && abs(dz) == 1 -> ALTAR_HEIGHT + 1
                abs(dx) == 2 && abs(dz) == 2 -> 3
                abs(dx) == 3 && abs(dz) == 3 -> 2
                (abs(dx) == 2 && dz == 0) || (dx == 0 && abs(dz) == 2) -> 3
                (abs(dx) == 3 && dz == 0) || (dx == 0 && abs(dz) == 3) -> 2
                else -> null
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

        private fun canClearAltarAirspace(state: BlockState): Boolean =
            canReplaceSiteAirspace(state)

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

        private fun pathBlocks(ids: List<String>?, fallback: List<Block>): List<Block> {
            val safeFallback = fallback.filterNot(::isForbiddenPathBlock).takeIf { it.isNotEmpty() } ?: listOf(Blocks.COARSE_DIRT, Blocks.COBBLESTONE)
            return blocks(ids, safeFallback).filterNot(::isForbiddenPathBlock).takeIf { it.isNotEmpty() } ?: safeFallback
        }

        private fun graveBlocks(ids: List<String>?, fallback: List<Block>): List<Block> {
            val safeFallback = fallback.filterNot { it == Blocks.GRAVEL }.takeIf { it.isNotEmpty() } ?: listOf(Blocks.COBBLESTONE, Blocks.ANDESITE)
            return blocks(ids, safeFallback).filterNot { it == Blocks.GRAVEL }.takeIf { it.isNotEmpty() } ?: safeFallback
        }

        private fun isForbiddenPathBlock(block: Block): Boolean =
            block == Blocks.GRAVEL || isStoneBrickPathBlock(block)

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
            val trophies: List<Block>,
            val walls: List<Block>,
            val headstones: List<Block>,
            val graveStyles: List<GraveStyle>,
            val focalStructures: List<TileType>,
            val clusterStructures: List<TileType>,
            val edgeStructures: List<TileType>,
            val fieldStructures: List<TileType>
        ) {
            fun path(random: RandomSource): Block = path[random.nextInt(path.size)]
            fun grave(random: RandomSource): Block = grave[random.nextInt(grave.size)]
            fun structure(random: RandomSource): Block = structure[random.nextInt(structure.size)]
            fun decoration(random: RandomSource): Block = decorations[random.nextInt(decorations.size)]
            fun trophy(random: RandomSource): Block? = trophies.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
            fun wall(random: RandomSource): Block = walls[random.nextInt(walls.size)]
            fun headstone(random: RandomSource): Block = headstones[random.nextInt(headstones.size)]
            fun focalStructure(random: RandomSource): TileType = focalStructures[random.nextInt(focalStructures.size)]
            fun clusterStructure(random: RandomSource): TileType = clusterStructures[random.nextInt(clusterStructures.size)]
            fun edgeStructure(random: RandomSource): TileType = edgeStructures[random.nextInt(edgeStructures.size)]
            fun fieldStructure(random: RandomSource): TileType = fieldStructures[random.nextInt(fieldStructures.size)]
            fun graveStyle(center: BlockPos, definitionId: String): GraveStyle {
                val hash = center.x * 19349663 xor center.y * 83492791 xor center.z * 297121507 xor definitionId.hashCode()
                return graveStyles[Math.floorMod(hash, graveStyles.size)]
            }

            companion object {
                private val THEMES = listOf(
                    GraveyardTheme(
                        path = listOf(Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.COBBLESTONE),
                        grave = listOf(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS),
                        structure = listOf(Blocks.POLISHED_ANDESITE, Blocks.ANDESITE, Blocks.COBBLESTONE),
                        decorations = listOf(Blocks.RED_CANDLE, Blocks.SOUL_LANTERN, Blocks.DEAD_BUSH, Blocks.COBWEB),
                        walls = listOf(Blocks.COBBLESTONE_WALL, Blocks.ANDESITE_WALL),
                        headstones = listOf(Blocks.CHISELED_STONE_BRICKS, Blocks.COBBLESTONE_WALL),
                        graveStyles = listOf(
                            GraveStyle(Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE_SLAB),
                            GraveStyle(Blocks.CRACKED_STONE_BRICKS, Blocks.COBBLESTONE_WALL, Blocks.RED_CANDLE)
                        ),
                        focalStructures = listOf(TileType.MAUSOLEUM_SMALL, TileType.SHRINE, TileType.STATUE_RUIN, TileType.BROKEN_ARCH),
                        clusterStructures = listOf(TileType.MAUSOLEUM_SMALL, TileType.STATUE_RUIN, TileType.MEMORIAL_COURT, TileType.SHRINE),
                        edgeStructures = listOf(TileType.BROKEN_ARCH, TileType.OSSUARY, TileType.STATUE_RUIN, TileType.SHRINE),
                        fieldStructures = listOf(TileType.MAUSOLEUM_SMALL, TileType.STATUE_RUIN, TileType.MEMORIAL_COURT, TileType.CRYPT_ENTRY)
                    ),
                    GraveyardTheme(
                        path = listOf(Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MOSSY_COBBLESTONE),
                        grave = listOf(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.ANDESITE),
                        structure = listOf(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE, Blocks.ANDESITE),
                        decorations = listOf(Blocks.FERN, Blocks.DEAD_BUSH, Blocks.RED_CANDLE, Blocks.LANTERN),
                        walls = listOf(Blocks.MOSSY_COBBLESTONE_WALL, Blocks.COBBLESTONE_WALL),
                        headstones = listOf(Blocks.MOSSY_COBBLESTONE_WALL, Blocks.COBBLESTONE_WALL),
                        graveStyles = listOf(
                            GraveStyle(Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL, Blocks.SMOOTH_STONE_SLAB),
                            GraveStyle(Blocks.COBBLESTONE, Blocks.COBBLESTONE_WALL, Blocks.LANTERN)
                        ),
                        focalStructures = listOf(TileType.BROKEN_ARCH, TileType.MEMORIAL_COURT, TileType.STATUE_RUIN, TileType.SHRINE),
                        clusterStructures = listOf(TileType.BROKEN_ARCH, TileType.MEMORIAL_COURT, TileType.STATUE_RUIN, TileType.OSSUARY),
                        edgeStructures = listOf(TileType.OSSUARY, TileType.BROKEN_ARCH, TileType.STATUE_RUIN, TileType.TREE_STUMP),
                        fieldStructures = listOf(TileType.MEMORIAL_COURT, TileType.BROKEN_ARCH, TileType.STATUE_RUIN, TileType.SHRINE)
                    ),
                    GraveyardTheme(
                        path = listOf(Blocks.COBBLED_DEEPSLATE, Blocks.COARSE_DIRT, Blocks.TUFF),
                        grave = listOf(Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.COBBLED_DEEPSLATE),
                        structure = listOf(Blocks.POLISHED_DEEPSLATE, Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_BRICKS),
                        decorations = listOf(Blocks.SOUL_LANTERN, Blocks.BLUE_CANDLE, Blocks.COBWEB, Blocks.SCULK),
                        walls = listOf(Blocks.COBBLED_DEEPSLATE_WALL, Blocks.POLISHED_DEEPSLATE_WALL, Blocks.DEEPSLATE_TILE_WALL),
                        headstones = listOf(Blocks.CHISELED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_WALL),
                        graveStyles = listOf(
                            GraveStyle(Blocks.DEEPSLATE_TILES, Blocks.CHISELED_DEEPSLATE, Blocks.BLUE_CANDLE),
                            GraveStyle(Blocks.POLISHED_DEEPSLATE, Blocks.COBBLED_DEEPSLATE_WALL, Blocks.SOUL_LANTERN)
                        ),
                        focalStructures = listOf(TileType.CRYPT_ENTRY, TileType.MAUSOLEUM_SMALL, TileType.OSSUARY, TileType.STATUE_RUIN),
                        clusterStructures = listOf(TileType.CRYPT_ENTRY, TileType.MAUSOLEUM_SMALL, TileType.OSSUARY, TileType.MEMORIAL_COURT),
                        edgeStructures = listOf(TileType.OSSUARY, TileType.BROKEN_ARCH, TileType.STATUE_RUIN, TileType.SHRINE),
                        fieldStructures = listOf(TileType.CRYPT_ENTRY, TileType.MAUSOLEUM_SMALL, TileType.OSSUARY, TileType.STATUE_RUIN)
                    ),
                    GraveyardTheme(
                        path = listOf(Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SOUL_SOIL),
                        grave = listOf(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE, Blocks.BLACKSTONE),
                        structure = listOf(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE, Blocks.BASALT),
                        decorations = listOf(Blocks.SOUL_LANTERN, Blocks.RED_CANDLE, Blocks.SOUL_TORCH, Blocks.CRYING_OBSIDIAN),
                        walls = listOf(Blocks.BLACKSTONE_WALL, Blocks.POLISHED_BLACKSTONE_BRICK_WALL, Blocks.POLISHED_BLACKSTONE_WALL),
                        headstones = listOf(Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICK_WALL),
                        graveStyles = listOf(
                            GraveStyle(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CHISELED_POLISHED_BLACKSTONE, Blocks.SOUL_LANTERN),
                            GraveStyle(Blocks.BLACKSTONE, Blocks.BLACKSTONE_WALL, Blocks.RED_CANDLE)
                        ),
                        focalStructures = listOf(TileType.CRYPT_ENTRY, TileType.MAUSOLEUM_SMALL, TileType.SHRINE, TileType.OSSUARY),
                        clusterStructures = listOf(TileType.CRYPT_ENTRY, TileType.MAUSOLEUM_SMALL, TileType.SHRINE, TileType.MEMORIAL_COURT),
                        edgeStructures = listOf(TileType.OSSUARY, TileType.BROKEN_ARCH, TileType.SHRINE, TileType.STATUE_RUIN),
                        fieldStructures = listOf(TileType.CRYPT_ENTRY, TileType.SHRINE, TileType.MEMORIAL_COURT, TileType.OSSUARY)
                    ),
                    GraveyardTheme(
                        path = listOf(Blocks.TUFF, Blocks.COARSE_DIRT, Blocks.COBBLED_DEEPSLATE),
                        grave = listOf(Blocks.POLISHED_ANDESITE, Blocks.TUFF, Blocks.COBBLED_DEEPSLATE),
                        structure = listOf(Blocks.POLISHED_ANDESITE, Blocks.ANDESITE, Blocks.COBBLED_DEEPSLATE),
                        decorations = listOf(Blocks.DEAD_BUSH, Blocks.FLOWER_POT, Blocks.YELLOW_CANDLE, Blocks.LANTERN),
                        walls = listOf(Blocks.ANDESITE_WALL, Blocks.COBBLED_DEEPSLATE_WALL),
                        headstones = listOf(Blocks.CHISELED_STONE_BRICKS, Blocks.ANDESITE_WALL),
                        graveStyles = listOf(
                            GraveStyle(Blocks.POLISHED_ANDESITE, Blocks.CHISELED_STONE_BRICKS, Blocks.YELLOW_CANDLE),
                            GraveStyle(Blocks.TUFF, Blocks.ANDESITE_WALL, Blocks.FLOWER_POT)
                        ),
                        focalStructures = listOf(TileType.MEMORIAL_COURT, TileType.BROKEN_ARCH, TileType.SHRINE, TileType.STATUE_RUIN),
                        clusterStructures = listOf(TileType.MEMORIAL_COURT, TileType.BROKEN_ARCH, TileType.STATUE_RUIN, TileType.MAUSOLEUM_SMALL),
                        edgeStructures = listOf(TileType.BROKEN_ARCH, TileType.SHRINE, TileType.OSSUARY, TileType.STATUE_RUIN),
                        fieldStructures = listOf(TileType.MEMORIAL_COURT, TileType.BROKEN_ARCH, TileType.SHRINE, TileType.CRYPT_ENTRY)
                    ),
                    GraveyardTheme(
                        path = listOf(Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.COARSE_DIRT, Blocks.SOUL_SOIL),
                        grave = listOf(Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.SOUL_SOIL),
                        structure = listOf(Blocks.MUD, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.BASALT),
                        decorations = listOf(Blocks.BONE_BLOCK, Blocks.SKELETON_SKULL, Blocks.WHITE_CANDLE, Blocks.DEAD_BUSH),
                        walls = listOf(Blocks.COBBLED_DEEPSLATE_WALL, Blocks.COBBLESTONE_WALL),
                        headstones = listOf(Blocks.BONE_BLOCK, Blocks.BROWN_TERRACOTTA),
                        graveStyles = listOf(
                            GraveStyle(Blocks.MUD, Blocks.BROWN_TERRACOTTA, Blocks.WHITE_CANDLE),
                            GraveStyle(Blocks.MUD, Blocks.BONE_BLOCK, Blocks.SKELETON_SKULL)
                        ),
                        focalStructures = listOf(TileType.OSSUARY, TileType.CRYPT_ENTRY, TileType.MEMORIAL_COURT, TileType.SHRINE),
                        clusterStructures = listOf(TileType.OSSUARY, TileType.MEMORIAL_COURT, TileType.CRYPT_ENTRY, TileType.STATUE_RUIN),
                        edgeStructures = listOf(TileType.OSSUARY, TileType.BROKEN_ARCH, TileType.SHRINE, TileType.TREE_STUMP),
                        fieldStructures = listOf(TileType.OSSUARY, TileType.CRYPT_ENTRY, TileType.MEMORIAL_COURT, TileType.SHRINE)
                    )
                )

                fun from(definition: ObeliskDefinition, center: BlockPos): GraveyardPalette {
                    val configured = definition.graveyardPalette
                    val trophyIds = configured?.trophyBlocks ?: definition.trophyBlocks
                    val hash = center.x * 73428767 xor center.y * 912271 xor center.z * 4235437 xor definition.id.hashCode()
                    val theme = THEMES[Math.floorMod(hash, THEMES.size)]
                    return GraveyardPalette(
                        pedestal = block(configured?.pedestalBlock ?: definition.pedestalBlock, Blocks.POLISHED_ANDESITE),
                        path = pathBlocks(configured?.pathBlocks ?: definition.pathBlocks, theme.path),
                        grave = graveBlocks(configured?.graveBlocks ?: definition.graveBlocks, theme.grave),
                        structure = blocks(configured?.structureBlocks ?: definition.structureBlocks, theme.structure),
                        decorations = blocks(configured?.decorations ?: definition.decorations, theme.decorations),
                        trophies = blocksOrEmpty(trophyIds),
                        walls = theme.walls,
                        headstones = theme.headstones,
                        graveStyles = theme.graveStyles,
                        focalStructures = theme.focalStructures,
                        clusterStructures = theme.clusterStructures,
                        edgeStructures = theme.edgeStructures,
                        fieldStructures = theme.fieldStructures
                    )
                }
            }
        }

        private data class GraveyardTheme(
            val path: List<Block>,
            val grave: List<Block>,
            val structure: List<Block>,
            val decorations: List<Block>,
            val walls: List<Block>,
            val headstones: List<Block>,
            val graveStyles: List<GraveStyle>,
            val focalStructures: List<TileType>,
            val clusterStructures: List<TileType>,
            val edgeStructures: List<TileType>,
            val fieldStructures: List<TileType>
        )

        private data class GraveStyle(
            val ground: Block,
            val headstone: Block,
            val lowMarker: Block
        )

        private data class GravePlacement(
            val head: BlockPos,
            val direction: Direction,
            val headstone: Block
        )

        private enum class TileType {
            FONT_PEDESTAL,
            PATH,
            GRAVE_SINGLE,
            GRAVE_DOUBLE,
            MAUSOLEUM_SMALL,
            SHRINE,
            STATUE_RUIN,
            CRYPT_ENTRY,
            BROKEN_ARCH,
            MEMORIAL_COURT,
            OSSUARY,
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

            fun offset(dx: Int, dz: Int): TileCoord = TileCoord(x + dx, z + dz)
        }

        private data class TilePlan(
            val coord: TileCoord,
            val groundPos: BlockPos,
            val type: TileType,
            val zone: TileZone,
            val pathExits: Set<Direction>
        )

        private data class ChunkSitePlan(
            val tiles: Map<TileCoord, TilePlan>,
            val radius: Int,
            val footprintSize: Int
        )

        private data class BuiltSite(
            val fontPos: BlockPos,
            val maxBlood: Double,
            val placedInChunk: Boolean = false
        )
    }

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != Level.OVERWORLD) return false

        val chunk = ChunkPos(context.origin())
        var placedAny = false
        val chunkCellX = Math.floorDiv(chunk.minBlockX, SITE_GRID_BLOCKS)
        val chunkCellZ = Math.floorDiv(chunk.minBlockZ, SITE_GRID_BLOCKS)
        for (cellX in chunkCellX - SITE_GRID_SCAN_RADIUS..chunkCellX + SITE_GRID_SCAN_RADIUS) {
            for (cellZ in chunkCellZ - SITE_GRID_SCAN_RADIUS..chunkCellZ + SITE_GRID_SCAN_RADIUS) {
                if (!shouldMaterializeSite(cellX, cellZ)) continue
                val siteSeed = siteSeed(cellX, cellZ)
                val definition = pickDeterministicObelisk(RandomSource.create(siteSeed)) ?: return false
                val center = siteAnchorForCell(cellX, cellZ).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
                if (!siteMayIntersectChunk(center, chunk)) continue
                val site = buildSite(level, center, definition, siteSeed, chunk) ?: continue
                placedAny = placedAny || site.placedInChunk
                if (isInsideChunk(site.fontPos, chunk)) {
                    placedAny = placeGeneratedFont(level, site, definition) || placedAny
                }
            }
        }
        return placedAny
    }

    private fun generateChunkSlicedSiteForTestsInternal(
        level: ServerLevel,
        center: BlockPos,
        definitionId: String,
        seed: Long
    ): Boolean {
        val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
        val siteCenter = snapToUniversalGrid(center).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
        val extent = MAX_TILE_RADIUS * TILE_SIZE + ALTAR_RADIUS + 8
        val minChunkX = Math.floorDiv(siteCenter.x - extent, 16)
        val maxChunkX = Math.floorDiv(siteCenter.x + extent, 16)
        val minChunkZ = Math.floorDiv(siteCenter.z - extent, 16)
        val maxChunkZ = Math.floorDiv(siteCenter.z + extent, 16)
        var placedFont = false
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                val chunk = ChunkPos(chunkX, chunkZ)
                val site = buildSite(level, siteCenter, definition, seed, chunk) ?: continue
                if (isInsideChunk(site.fontPos, chunk)) {
                    placedFont = placeGeneratedFont(level, site, definition) || placedFont
                }
            }
        }
        return placedFont
    }

    private fun generatePlacedSitesForChunkForTests(level: ServerLevel, chunk: ChunkPos): List<BlockPos> {
        val placedFonts = mutableListOf<BlockPos>()
        val chunkCellX = Math.floorDiv(chunk.minBlockX, SITE_GRID_BLOCKS)
        val chunkCellZ = Math.floorDiv(chunk.minBlockZ, SITE_GRID_BLOCKS)
        for (cellX in chunkCellX - SITE_GRID_SCAN_RADIUS..chunkCellX + SITE_GRID_SCAN_RADIUS) {
            for (cellZ in chunkCellZ - SITE_GRID_SCAN_RADIUS..chunkCellZ + SITE_GRID_SCAN_RADIUS) {
                if (!shouldMaterializeSite(cellX, cellZ)) continue
                val siteSeed = siteSeed(cellX, cellZ)
                val definition = pickDeterministicObelisk(RandomSource.create(siteSeed)) ?: continue
                val center = siteAnchorForCell(cellX, cellZ).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
                if (!siteMayIntersectChunk(center, chunk)) continue
                val site = buildSite(level, center, definition, siteSeed, chunk) ?: continue
                if (isInsideChunk(site.fontPos, chunk) && placeGeneratedFont(level, site, definition)) {
                    placedFonts += site.fontPos
                }
            }
        }
        return placedFonts
    }

        private fun buildSite(level: WorldGenLevel, center: BlockPos, definition: ObeliskDefinition, siteSeed: Long, chunk: ChunkPos): BuiltSite? {
            var placedInChunk = false
            val chunkLocalSetBlock = { pos: BlockPos, state: BlockState, flags: Int ->
                if (isInsideChunk(pos, chunk)) {
                val placed = level.setBlock(pos, state, flags)
                placedInChunk = placedInChunk || placed
                placed
            } else {
                false
                }
            }
            val palette = GraveyardPalette.from(definition, center)
            val fullPlan = planTiles(level, center, palette, RandomSource.create(siteSeed xor SITE_LAYOUT_SALT))
            val tiles = fullPlan.filterValues { tile -> isInsideChunk(tile.groundPos, chunk) }
            val centerGround = tileGround(level, center, TileCoord(0, 0)) ?: return null
            val altarCenter = findNearestViableAltarCenter(level, centerGround, centerGround.y + ALTAR_MAX_FOUNDATION_DROP) { true } ?: return null
            val altarSurface = altarSurfaceMap(level, altarCenter) ?: return null
            if (!canPlaceElevatedAltarAndFont(level, altarCenter, altarSurface)) return null
            if (tiles.isEmpty() && !altarIntersectsChunk(altarCenter, chunk)) return null
        val graveStyle = palette.graveStyle(center, definition.id)
        val graveFootprints = mutableSetOf<BlockPos>()
        val graveRecords = mutableListOf<GravePlacement>()
            val detailRandom = RandomSource.create(chunkPlacementSeed(siteSeed, chunk))
            tiles.values.sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z }).forEach { tile ->
                placeTile(level, chunkLocalSetBlock, tile, fullPlan, palette, graveStyle, graveFootprints, graveRecords, detailRandom)
            }
            placeIntersectionTrophies(level, chunkLocalSetBlock, fullPlan, palette, detailRandom)
            placeBoundaryAccents(level, chunkLocalSetBlock, tiles, palette, detailRandom)
            graveRecords.forEach { enforceGraveLine(level, chunkLocalSetBlock, it) }

            val fontPos = placeElevatedAltar(level, chunkLocalSetBlock, altarCenter, altarSurface, palette, detailRandom)
            val tileRadius = fullPlan.keys.maxOfOrNull { maxOf(abs(it.x), abs(it.z)) } ?: MIN_TILE_RADIUS
            return BuiltSite(fontPos, generatedCapacityForSite(definition, tileRadius, fullPlan.size), placedInChunk)
        }

    private fun altarIntersectsChunk(center: BlockPos, chunk: ChunkPos): Boolean =
        center.x - ALTAR_RADIUS <= chunk.maxBlockX &&
            center.x + ALTAR_RADIUS >= chunk.minBlockX &&
            center.z - ALTAR_RADIUS <= chunk.maxBlockZ &&
            center.z + ALTAR_RADIUS >= chunk.minBlockZ

    private fun siteMayIntersectChunk(center: BlockPos, chunk: ChunkPos): Boolean =
        center.x - SITE_MAX_BLOCK_RADIUS <= chunk.maxBlockX &&
            center.x + SITE_MAX_BLOCK_RADIUS >= chunk.minBlockX &&
            center.z - SITE_MAX_BLOCK_RADIUS <= chunk.maxBlockZ &&
            center.z + SITE_MAX_BLOCK_RADIUS >= chunk.minBlockZ

    private fun chunkPlacementSeed(siteSeed: Long, chunk: ChunkPos): Long =
        siteSeed xor SITE_CHUNK_DETAIL_SALT xor (chunk.x.toLong() shl 32) xor (chunk.z.toLong() and 0xffffffffL)

    private fun pickDeterministicObelisk(random: RandomSource): ObeliskDefinition? {
        val enabled = ObeliskDataManager.enabledObelisks().filter { it.worldgenWeight > 0.0 }
        if (enabled.isEmpty()) return null
        val total = enabled.sumOf { it.worldgenWeight }
        var cursor = random.nextDouble() * total
        for (definition in enabled) {
            cursor -= definition.worldgenWeight
            if (cursor <= 0.0) return definition
        }
        return enabled.last()
    }

    private fun isInsideChunk(pos: BlockPos, chunk: ChunkPos): Boolean =
        pos.x >= chunk.minBlockX &&
            pos.x <= chunk.maxBlockX &&
            pos.z >= chunk.minBlockZ &&
            pos.z <= chunk.maxBlockZ
}
