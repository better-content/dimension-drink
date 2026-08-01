package dev.yourname.dimensiondrink.worldgen

import com.mojang.serialization.Codec
import dev.yourname.dimensiondrink.ObeliskConstants
import dev.yourname.dimensiondrink.content.ObeliskBlockEntity
import dev.yourname.dimensiondrink.data.ObeliskDataManager
import dev.yourname.dimensiondrink.data.ObeliskDefinition
import dev.yourname.dimensiondrink.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BiomeTags
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
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.common.Tags
import net.minecraftforge.fml.ModList
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
        private const val ALTAR_HEIGHT = 1
        private const val ALTAR_RADIUS = 3
        private const val COURT_MIN_HALF_EXTENT = 5
        private const val COURT_MAX_HALF_EXTENT = 7
        private const val COURT_PATH_SCAN_RADIUS = 8
        private const val COURT_ENTRY_SCAN_DISTANCE = 12
        private const val RELIQUARY_MIN_RADIUS = 8
        private const val RELIQUARY_RADIUS = 136
        private const val ALTAR_MAX_FOUNDATION_DROP = 2
        private const val TERRAIN_SCAN_UP = 28
        private const val SITE_GRID_CHUNKS = 6
        private const val SITE_GRID_BLOCKS = SITE_GRID_CHUNKS * 16
        private const val SITE_GRID_SCAN_RADIUS = 2
        private const val TARGET_RARITY_CHUNKS = 3200
        private const val ACTIVE_SITE_THRESHOLD = 64
        private const val ALTAR_CENTER_CHUNK_MARGIN = ALTAR_RADIUS + 2
        private const val SITE_MAX_BLOCK_RADIUS = RELIQUARY_RADIUS + ALTAR_RADIUS + 8
        private const val SITE_LAYOUT_SALT = -0x61c8864680b583ebL
        private const val SITE_CHUNK_DETAIL_SALT = 0x2545f4914f6cdd1dL
        private const val SITE_PRIORITY_SALT = 0x13a5ba1d7c4e9f21L
        const val STRUCTURE_SITE_MAX_BLOCK_RADIUS = SITE_MAX_BLOCK_RADIUS
        const val STRUCTURE_MIN_Y = -64
        const val STRUCTURE_MAX_Y = 320
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
                    val origin = probe.atY(surfaceY)
                    val placementCenter = findNearestViableAltarCenter(level, origin, origin.y + ALTAR_MAX_FOUNDATION_DROP) { true } ?: return@forEach
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
                    anchors += siteAltarAnchorForCell(cellX, cellZ)
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

        private fun siteAltarAnchorForCell(cellX: Int, cellZ: Int): BlockPos {
            val anchor = siteAnchorForCell(cellX, cellZ)
            val chunk = ChunkPos(anchor)
            val random = RandomSource.create(siteSeed(cellX, cellZ) xor SITE_LAYOUT_SALT)
            val localX = ALTAR_CENTER_CHUNK_MARGIN + random.nextInt(16 - ALTAR_CENTER_CHUNK_MARGIN * 2)
            val localZ = ALTAR_CENTER_CHUNK_MARGIN + random.nextInt(16 - ALTAR_CENTER_CHUNK_MARGIN * 2)
            return BlockPos(chunk.minBlockX + localX, anchor.y, chunk.minBlockZ + localZ)
        }

        fun structureAnchorForChunk(chunk: ChunkPos, random: RandomSource): BlockPos {
            val localX = ALTAR_CENTER_CHUNK_MARGIN + random.nextInt(16 - ALTAR_CENTER_CHUNK_MARGIN * 2)
            val localZ = ALTAR_CENTER_CHUNK_MARGIN + random.nextInt(16 - ALTAR_CENTER_CHUNK_MARGIN * 2)
            return BlockPos(chunk.minBlockX + localX, 0, chunk.minBlockZ + localZ)
        }

        fun placeStructureSiteForChunk(
            level: WorldGenLevel,
            centerX: Int,
            centerZ: Int,
            siteSeed: Long,
            chunk: ChunkPos
        ): BlockPos? {
            if (level.level.dimension() != Level.OVERWORLD) return null
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            val definition = feature.pickDeterministicObelisk(RandomSource.create(siteSeed)) ?: return null
            val center = BlockPos(centerX, level.maxBuildHeight - TERRAIN_SCAN_UP - 2, centerZ)
            val site = feature.buildSite(level, center, definition, siteSeed, chunk) ?: return null
            if (feature.isInsideChunk(site.fontPos, chunk) && level.ensureCanWrite(site.fontPos)) {
                placeGeneratedFont(level, site, definition)
            }
            return if (site.placedInChunk || feature.isInsideChunk(site.fontPos, chunk)) site.fontPos else null
        }

        fun placeStructureSiteForBox(
            level: WorldGenLevel,
            centerX: Int,
            centerZ: Int,
            siteSeed: Long,
            box: BoundingBox,
            chunk: ChunkPos = ChunkPos(Math.floorDiv(box.minX(), 16), Math.floorDiv(box.minZ(), 16))
        ): BlockPos? {
            if (level.level.dimension() != Level.OVERWORLD) return null
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            val definition = feature.pickDeterministicObelisk(RandomSource.create(siteSeed)) ?: return null
            val center = BlockPos(centerX, level.maxBuildHeight - TERRAIN_SCAN_UP - 2, centerZ)
            return feature.placeStructureSiteBox(level, center, definition, siteSeed, box, chunk)
        }

        fun generateStructureSiteChunkBoxesForTests(
            level: ServerLevel,
            center: BlockPos,
            definitionId: String,
            siteSeed: Long
        ): Boolean {
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            var placedAny = false
            val minChunkX = Math.floorDiv(center.x - SITE_MAX_BLOCK_RADIUS, 16)
            val maxChunkX = Math.floorDiv(center.x + SITE_MAX_BLOCK_RADIUS, 16)
            val minChunkZ = Math.floorDiv(center.z - SITE_MAX_BLOCK_RADIUS, 16)
            val maxChunkZ = Math.floorDiv(center.z + SITE_MAX_BLOCK_RADIUS, 16)
            for (chunkX in minChunkX..maxChunkX) {
                for (chunkZ in minChunkZ..maxChunkZ) {
                    val chunk = ChunkPos(chunkX, chunkZ)
                    val box = BoundingBox(
                        chunk.minBlockX,
                        level.minBuildHeight,
                        chunk.minBlockZ,
                        chunk.maxBlockX,
                        level.maxBuildHeight - 1,
                        chunk.maxBlockZ
                    )
                    placedAny = feature.placeStructureSiteBox(level, center, definition, siteSeed, box, chunk) != null || placedAny
                }
            }
            return placedAny
        }

        private fun chunkInteriorAnchor(pos: BlockPos): BlockPos {
            val chunk = ChunkPos(pos)
            return BlockPos(chunk.minBlockX + 8, pos.y, chunk.minBlockZ + 8)
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

        private fun shouldMaterializeSite(cellX: Int, cellZ: Int): Boolean =
            shouldGenerateSite(cellX, cellZ)

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

        private fun isInsideChunkBounds(pos: BlockPos, chunk: ChunkPos): Boolean =
            pos.x >= chunk.minBlockX &&
                pos.x <= chunk.maxBlockX &&
                pos.z >= chunk.minBlockZ &&
                pos.z <= chunk.maxBlockZ

        private fun buildSiteBlocks(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            definition: ObeliskDefinition,
            random: RandomSource
        ): BuiltSite? {
            val palette = CultivationPalette.from(definition, center)
            val tiles = planTiles(level, center, palette, random)
            val fontTile = tiles[TileCoord(0, 0)] ?: return null
            val altarCenter = findNearestViableAltarCenter(level, fontTile.groundPos, fontTile.groundPos.y + ALTAR_MAX_FOUNDATION_DROP) { true } ?: return null
            val altarSurface = altarSurfaceMap(level, altarCenter) ?: return null
            if (!canPlaceElevatedAltarAndFont(level, altarCenter, altarSurface)) return null

            val pathColumns = plannedPathColumns(tiles)
            tiles.values.sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z }).forEach { tile ->
                placeTile(level, setBlock, tile, tiles, palette, pathColumns, random)
            }
            placeIntersectionTrophies(level, setBlock, tiles, palette, random)
            placeBoundaryAccents(level, setBlock, tiles, palette, random)
            placeWetBiomeOvergrowth(level, setBlock, tiles.values.toList(), random)

            val courtLayout = shapeReliquaryCourt(level, setBlock, altarCenter, palette, pathColumns)
            val fontPos = placeElevatedAltar(level, setBlock, altarCenter, altarSurface, palette, courtLayout, random)
            ensureCourtTrophyDisplay(level, setBlock, altarCenter, courtLayout, palette, pathColumns, random)
            ensureReliquaryTrophyDisplay(level, setBlock, altarCenter, palette, pathColumns, random) { rough ->
                terrainGroundNear(level, rough, altarCenter.y + 5, 4)
            }
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

        private fun planTiles(level: LevelAccessor, center: BlockPos, palette: CultivationPalette, random: RandomSource): Map<TileCoord, TilePlan> {
            val radius = MIN_TILE_RADIUS + random.nextInt(MAX_TILE_RADIUS - MIN_TILE_RADIUS + 1)
            val candidates = linkedMapOf<TileCoord, TilePlan>()
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val coord = TileCoord(x, z)
                    if (!insideOrganicShape(coord, radius)) continue
                    val ground = tileGround(level, center, coord) ?: continue
                    if (!canUseTileGround(level, ground, clearance = 2)) continue
                    candidates[coord] = TilePlan(coord, ground, TileType.CULTIVATION_DECOR, TileZone.CULTIVATION_FIELD, emptySet())
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
                    coord in focalTiles -> TileZone.PROCESSING_RUIN
                    coord in trophyTiles -> TileZone.SPECIMEN_DISPLAY
                    nearestDistance(coord, denseCenters) <= 2 -> TileZone.GROWING_CLUSTER
                    nearestDistance(coord, quietCenters) <= 2 -> TileZone.FALLOW_EDGE
                    manhattan(coord) > radius - 4 && random.nextInt(100) < 22 -> TileZone.OVERGROWN_BREAK
                    else -> TileZone.CULTIVATION_FIELD
                }
                planned[coord] = tile.copy(type = typeForZone(zone, coord, paths, palette, random), zone = zone, pathExits = exitsForTile)
            }
            return planned
        }

        private fun planChunkTiles(level: LevelAccessor, center: BlockPos, chunk: ChunkPos, palette: CultivationPalette, random: RandomSource): ChunkSitePlan {
            val radius = MIN_TILE_RADIUS + random.nextInt(MAX_TILE_RADIUS - MIN_TILE_RADIUS + 1)
            val shapeCoords = linkedSetOf<TileCoord>()
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val coord = TileCoord(x, z)
                    if (insideOrganicShape(coord, radius)) shapeCoords += coord
                }
            }
            val dummyCandidates = shapeCoords.associateWith { coord ->
                TilePlan(coord, BlockPos.ZERO, TileType.CULTIVATION_DECOR, TileZone.CULTIVATION_FIELD, emptySet())
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
                    coord in focalTiles -> TileZone.PROCESSING_RUIN
                    coord in trophyTiles -> TileZone.SPECIMEN_DISPLAY
                    nearestDistance(coord, denseCenters) <= 2 -> TileZone.GROWING_CLUSTER
                    nearestDistance(coord, quietCenters) <= 2 -> TileZone.FALLOW_EDGE
                    manhattan(coord) > radius - 4 && random.nextInt(100) < 22 -> TileZone.OVERGROWN_BREAK
                    else -> TileZone.CULTIVATION_FIELD
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

        private fun typeForZone(zone: TileZone, coord: TileCoord, paths: Set<TileCoord>, palette: CultivationPalette, random: RandomSource): TileType {
            if (coord == TileCoord(0, 0)) return TileType.FONT_PEDESTAL
            if (zone == TileZone.APPROACH_PATH) return TileType.PATH
            return when (zone) {
                TileZone.PROCESSING_RUIN -> palette.focalStructure(random)
                TileZone.SPECIMEN_DISPLAY -> TileType.SPECIMEN_DISPLAY
                TileZone.GROWING_CLUSTER -> when (random.nextInt(100)) {
                    in 0..27 -> TileType.EXTRACTOR_COURT
                    in 28..45 -> TileType.CULTIVATION_BED
                    else -> palette.clusterStructure(random)
                }
                TileZone.FALLOW_EDGE -> when (random.nextInt(100)) {
                    in 0..19 -> TileType.CULTIVATION_DECOR
                    in 20..29 -> TileType.CULTIVATION_DECOR
                    in 30..57 -> TileType.CULTIVATION_BED
                    else -> palette.edgeStructure(random)
                }
                TileZone.OVERGROWN_BREAK -> TileType.CULTIVATION_DECOR
                TileZone.CULTIVATION_FIELD -> {
                    val nearPath = paths.any { chebyshevDistance(coord, it) <= 2 }
                    val roll = random.nextInt(100)
                    when {
                        nearPath && roll < 30 -> TileType.EXTRACTOR_COURT
                        roll < 28 -> TileType.CULTIVATION_BED
                        roll < 46 -> TileType.EXTRACTOR_COURT
                        roll < 95 -> palette.fieldStructure(random)
                        roll < 99 -> TileType.CULTIVATION_DECOR
                        else -> TileType.CULTIVATION_DECOR
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
            if (!level.hasChunk(x shr 4, z shr 4)) return null
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
            palette: CultivationPalette,
            pathColumns: Set<Long>,
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
                TileType.CULTIVATION_BED -> buildCultivationBed(level, setBlock, tile.groundPos, palette, pathColumns, random)
                TileType.EXTRACTOR_COURT -> buildSpecimenCourt(level, setBlock, tile.groundPos, palette, pathColumns, random)
                TileType.SEED_VAULT -> buildMausoleum(level, setBlock, tile.groundPos, palette, random)
                TileType.SHRINE -> buildShrine(level, setBlock, tile.groundPos, palette, random)
                TileType.TRELLIS_RUIN -> buildRuin(level, setBlock, tile.groundPos, palette, random)
                TileType.IRRIGATION_GATE -> buildCryptEntry(level, setBlock, tile.groundPos, palette, random)
                TileType.BROKEN_TRELLIS -> buildBrokenArch(level, setBlock, tile.groundPos, palette, random)
                TileType.CULTIVATION_COURT -> buildMemorialCourt(level, setBlock, tile.groundPos, palette, pathColumns, random)
                TileType.COMPOSTER_RUIN -> buildOssuary(level, setBlock, tile.groundPos, palette, random)
                TileType.ROOT_STOCK -> buildStump(level, setBlock, tile.groundPos, random)
                TileType.SPECIMEN_DISPLAY -> buildSpecimenCourt(level, setBlock, tile.groundPos, palette, pathColumns, random)
                TileType.CULTIVATION_DECOR -> buildCultivationBed(level, setBlock, tile.groundPos, palette, pathColumns, random)
            }
            if (tile.zone == TileZone.GROWING_CLUSTER && tile.type != TileType.PATH && tile.type != TileType.FONT_PEDESTAL) {
                reinforceDenseCluster(level, setBlock, tile.groundPos, palette, random)
            }
        }

        private fun reinforceDenseCluster(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            palette: CultivationPalette,
            random: RandomSource
        ) {
            val directions = Direction.Plane.HORIZONTAL.toList().shuffled(random)
            directions.take(3).forEachIndexed { index, direction ->
                val rough = base.relative(direction)
                val pos = terrainGroundNear(level, rough, base.y + 4, 1) ?: return@forEachIndexed
                if (!canUseTileGround(level, pos, 2)) return@forEachIndexed
                placeGround(level, setBlock, pos, if (index == 0 || random.nextBoolean()) palette.structure(random) else palette.path(random))
                when (index) {
                    0 -> placeSupportedAbove(level, setBlock, pos.above(), palette.marker)
                    1 -> {
                        placeSupportedAbove(level, setBlock, pos.above(), cultivationPlantBlock(pos, random))
                        if (random.nextInt(100) < 65) {
                            placeSupportedAbove(
                                level,
                                setBlock,
                                pos.above(2),
                                if (random.nextBoolean()) occultTorchBlock() else occultCandleBlock(random)
                            )
                        }
                    }
                    else -> placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(100) < 65) cultivationPlantBlock(pos, random) else palette.decoration(random))
                }
            }
        }

        private fun placePathArm(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tile: TilePlan, neighbor: TilePlan?, direction: Direction, palette: CultivationPalette, random: RandomSource) {
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
                val supportPos = lower.relative(if (lower == tile.groundPos) direction else direction.opposite, TILE_SIZE / 2)
                val slabPos = supportPos.above()
                if (canUseTileGround(level, supportPos, clearance = 1) && canReplaceDecoration(level, slabPos)) {
                    placeMinimalPath(level, setBlock, supportPos)
                    setBlock(slabPos, cutCopperSlabState(slabPos), 3)
                }
            }
        }

        private fun placeSlopeStep(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, a: BlockPos, b: BlockPos) {
            val lower = if (a.y < b.y) a else b
            val slabPos = lower.above()
            if (canUseTileGround(level, lower, clearance = 1) && canReplaceDecoration(level, slabPos)) {
                placeMinimalPath(level, setBlock, lower)
                setBlock(slabPos, cutCopperSlabState(slabPos), 3)
            }
        }

        private fun cutCopperSlabState(pos: BlockPos): BlockState =
            generatedState(Blocks.CUT_COPPER_SLAB, pos).setValue(SlabBlock.TYPE, SlabType.BOTTOM)

        private fun plannedPathColumns(tiles: Map<TileCoord, TilePlan>): Set<Long> {
            val columns = mutableSetOf<Long>()
            tiles.values
                .filter { it.type == TileType.PATH || it.type == TileType.FONT_PEDESTAL }
                .forEach { tile ->
                    columns += columnKey(tile.groundPos)
                    tile.pathExits.forEach { direction ->
                        for (step in 1 until TILE_SIZE) {
                            columns += columnKey(tile.groundPos.relative(direction, step))
                        }
                    }
                }
            return columns
        }

        private fun columnKey(pos: BlockPos): Long =
            (pos.x.toLong() shl 32) xor (pos.z.toLong() and 0xffffffffL)

        private fun columnX(key: Long): Int = (key shr 32).toInt()

        private fun columnZ(key: Long): Int = key.toInt()

        private fun buildMausoleum(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
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
                        placeSupportedAbove(level, setBlock, pos.above(), Blocks.STRIPPED_WARPED_STEM)
                        if (random.nextInt(3) != 0) placeSupportedAbove(level, setBlock, pos.above(2), Blocks.STRIPPED_WARPED_STEM)
                    }
                }
            }
            placeSupportedAbove(level, setBlock, base.above(), palette.marker)
            placeSupportedAbove(level, setBlock, base.above(2), if (random.nextBoolean()) Blocks.SMOOTH_STONE_SLAB else palette.structure(random))
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildLargeCrypt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource): Boolean {
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
                    placeSupportedAbove(level, setBlock, pos.above(), Blocks.STRIPPED_WARPED_STEM)
                    if (random.nextInt(100) < 65) placeSupportedAbove(level, setBlock, pos.above(2), Blocks.STRIPPED_WARPED_STEM)
                }
            }
            surfaces[0 to 0]?.let { center ->
                placeSupportedAbove(level, setBlock, center.above(), palette.marker)
                placeSupportedAbove(level, setBlock, center.above(2), if (random.nextBoolean()) occultTorchBlock() else occultCandleBlock(random))
            }
            scatterTileDetails(level, setBlock, base, palette, random, 6)
            return true
        }

        private fun buildShrine(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
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
                if (canUseTileGround(level, corner, 2)) placeSupportedAbove(level, setBlock, corner.above(), Blocks.STRIPPED_WARPED_STEM)
            }
            placeSupportedAbove(level, setBlock, base.above(), palette.marker)
            placeSupportedAbove(level, setBlock, base.above(2), if (random.nextBoolean()) occultTorchBlock() else occultCandleBlock(random))
            scatterTileDetails(level, setBlock, base, palette, random, 4)
        }

        private fun buildRuin(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeGround(level, setBlock, base, palette.structure(random))
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                for (step in 1..4) {
                    val pos = terrainGroundNear(level, base.relative(direction, step), base.y + 5, 2) ?: continue
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextBoolean()) placeGround(level, setBlock, pos, palette.structure(random))
                        placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) palette.marker else Blocks.STRIPPED_WARPED_STEM)
                        if (random.nextInt(3) == 0) placeSupportedAbove(level, setBlock, pos.above(2), Blocks.STRIPPED_WARPED_STEM)
                    }
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildCryptEntry(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
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
                    placeSupportedAbove(level, setBlock, flank.above(), Blocks.STRIPPED_WARPED_STEM)
                    if (random.nextBoolean()) placeSupportedAbove(level, setBlock, flank.above(2), Blocks.STRIPPED_WARPED_STEM)
                }
            }
            val lintelLeft = base.relative(side).above(2)
            val lintelRight = base.relative(side.opposite).above(2)
            val lintelCenter = base.above(3)
            placeSupportedAbove(level, setBlock, base.above(), palette.marker)
            if (canReplaceDecoration(level, lintelLeft) && canReplaceDecoration(level, lintelRight) && canReplaceDecoration(level, lintelCenter)) {
                setBlock(lintelLeft, generatedState(Blocks.STRIPPED_WARPED_STEM, lintelLeft), 3)
                setBlock(lintelRight, generatedState(Blocks.STRIPPED_WARPED_STEM, lintelRight), 3)
                setBlock(lintelCenter, generatedState(palette.structure(random), lintelCenter), 3)
            }
            scatterTileDetails(level, setBlock, base, palette, random, 4)
        }

        private fun buildBrokenArch(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
            val axis = if (random.nextBoolean()) Direction.EAST else Direction.NORTH
            val side = if (axis.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            val piers = listOf(base.relative(axis, 2), base.relative(axis.opposite, 2))
            if (!piers.all { canUseTileGround(level, it, 3) }) return
            piers.forEach { pier ->
                placeGround(level, setBlock, pier, palette.structure(random))
                placeSupportedAbove(level, setBlock, pier.above(), Blocks.STRIPPED_WARPED_STEM)
                placeSupportedAbove(level, setBlock, pier.above(2), Blocks.STRIPPED_WARPED_STEM)
            }
            if (random.nextBoolean()) {
                val cap = piers.shuffled(random).first().above(3)
                if (canReplaceDecoration(level, cap)) {
                    setBlock(cap, generatedState(palette.structure(random), cap), 3)
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
                    placeSupportedAbove(level, setBlock, flank.above(), palette.marker)
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 3)
        }

        private fun buildMemorialCourt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, pathColumns: Set<Long>, random: RandomSource) {
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
            placeSupportedAbove(level, setBlock, base.above(), palette.marker)
            if (random.nextBoolean()) {
                placeSupportedAbove(level, setBlock, base.above(2), palette.decoration(random))
            }
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val trophyBase = base.relative(direction, 2)
                if (canUseTileGround(level, trophyBase, 2) && random.nextInt(100) < 75) {
                    placeTrophyDisplay(level, setBlock, trophyBase, palette, pathColumns, random)
                }
            }
        }

        private fun buildOssuary(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource) {
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
                        index == 1 && random.nextBoolean() -> palette.marker
                        random.nextBoolean() -> palette.decoration(random)
                        else -> palette.wall(random)
                    }
                    setBlock(pos, generatedState(block, pos), 3)
                }
            }
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(2).forEach { direction ->
                val scatter = base.relative(direction)
                if (canUseTileGround(level, scatter, 1)) {
                    placeGround(level, setBlock, scatter, if (random.nextBoolean()) palette.structure(random) else palette.path(random))
                    if (random.nextBoolean()) placeSupportedAbove(level, setBlock, scatter.above(), palette.marker)
                }
            }
        }

        private fun buildSpecimenCourt(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, pathColumns: Set<Long>, random: RandomSource) {
            if (!canUseTileGround(level, base, 2)) return
            placeTrophyDisplay(level, setBlock, base, palette, pathColumns, random)
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

        private fun cultivationBedBlock(random: RandomSource): Block =
            when (random.nextInt(6)) {
                0 -> Blocks.MOSS_BLOCK
                1 -> Blocks.ROOTED_DIRT
                2 -> Blocks.PODZOL
                3 -> Blocks.MYCELIUM
                4 -> Blocks.MUD
                else -> Blocks.PACKED_MUD
            }

        private fun cultivationPlantBlock(pos: BlockPos, random: RandomSource): Block =
            when (Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7 + random.nextInt(29), 14)) {
                0 -> Blocks.POTTED_FERN
                1 -> Blocks.POTTED_AZALEA
                2 -> Blocks.POTTED_FLOWERING_AZALEA
                3 -> Blocks.POTTED_ALLIUM
                4 -> Blocks.POTTED_BLUE_ORCHID
                5 -> Blocks.POTTED_DANDELION
                6 -> Blocks.POTTED_POPPY
                7 -> Blocks.POTTED_OXEYE_DAISY
                8 -> Blocks.FERN
                9 -> Blocks.BROWN_MUSHROOM
                10 -> Blocks.RED_MUSHROOM
                11 -> Blocks.CRIMSON_ROOTS
                12 -> Blocks.WARPED_ROOTS
                else -> Blocks.FLOWERING_AZALEA
            }

        private fun buildCultivationBed(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, pathColumns: Set<Long>, random: RandomSource) {
            if (!canUseTileGround(level, base, 1)) return
            placeGround(level, setBlock, base, if (random.nextInt(3) == 0) palette.structure(random) else cultivationBedBlock(random))
            val placedAnchor = if (random.nextInt(100) < 68) {
                placeSupportedAbove(level, setBlock, base.above(), cultivationPlantBlock(base, random))
            } else if (random.nextInt(3) == 0) {
                placeTrophyDisplay(level, setBlock, base, palette, pathColumns, random)
            } else {
                placeSupportedAbove(level, setBlock, base.above(), palette.decoration(random))
            }
            if (placedAnchor) {
                Direction.Plane.HORIZONTAL.toList().shuffled(random).take(2).forEach { direction ->
                    val flank = base.relative(direction)
                    if (canUseTileGround(level, flank, 1)) {
                        placeGround(level, setBlock, flank, if (random.nextBoolean()) palette.path(random) else cultivationBedBlock(random))
                        placeSupportedAbove(level, setBlock, flank.above(), if (random.nextInt(100) < 72) cultivationPlantBlock(flank, random) else palette.decoration(random))
                    }
                }
            }
            scatterTileDetails(level, setBlock, base, palette, random, 8)
        }

        private fun scatterTileDetails(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, random: RandomSource, attempts: Int) {
            repeat(attempts) {
                val rough = base.offset(random.nextInt(5) - 2, 0, random.nextInt(5) - 2)
                val pos = terrainGroundNear(level, rough, base.y + 4, 1) ?: return@repeat
                if (canUseTileGround(level, pos, 1)) {
                    if (random.nextInt(4) == 0) placeGround(level, setBlock, pos, palette.path(random))
                    when (random.nextInt(8)) {
                        1, 2, 3, 4 -> placeSupportedAbove(level, setBlock, pos.above(), cultivationPlantBlock(pos, random))
                        5 -> placeSupportedAbove(level, setBlock, pos.above(), palette.decoration(random))
                    }
                }
            }
        }

        private fun placeIntersectionTrophies(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: CultivationPalette, random: RandomSource) {
            val pathCoords = tiles.values.filter { it.type == TileType.PATH }.map { it.coord }.toSet()
            val pathColumns = plannedPathColumns(tiles)
            val candidateTiles = tiles.values
                .filter { it.type == TileType.PATH }
                .filter { pathNeighborCount(it.coord, pathCoords) >= 3 }
                .shuffled(random)
                .take(12)
            for (tile in candidateTiles) {
                for (direction in Direction.Plane.HORIZONTAL.toList().shuffled(random)) {
                    val pos = terrainGroundNear(level, tile.groundPos.relative(direction, 2), tile.groundPos.y + 4, 2) ?: continue
                    if (placeBloodCatchment(level, setBlock, pos, random) || placeConduitRun(level, setBlock, pos, direction.opposite, random) || placeTrophyDisplay(level, setBlock, pos, palette, pathColumns, random)) {
                        break
                    }
                }
            }
        }

        private fun placeBoundaryAccents(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, tiles: Map<TileCoord, TilePlan>, palette: CultivationPalette, random: RandomSource) {
            val pathColumns = plannedPathColumns(tiles)
            tiles.values.forEach { tile ->
                for (direction in Direction.Plane.HORIZONTAL) {
                    if (tile.coord.relative(direction) in tiles || random.nextInt(100) >= 18) continue
                    val pos = tile.groundPos.relative(direction, TILE_SIZE / 2)
                    if (canUseTileGround(level, pos, 2)) {
                        if (random.nextInt(100) < 45) {
                            placeValveShrine(level, setBlock, pos, direction.opposite, random) || placeTrophyDisplay(level, setBlock, pos, palette, pathColumns, random)
                        } else {
                            placeSupportedAbove(level, setBlock, pos.above(), if (random.nextInt(4) == 0) cultivationPlantBlock(pos, random) else palette.wall(random))
                        }
                    }
                }
            }
        }

        private fun ensureReliquaryTrophyDisplay(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            palette: CultivationPalette,
            pathColumns: Set<Long>,
            random: RandomSource,
            locateGround: (BlockPos) -> BlockPos?
        ) {
            if (palette.trophies.isEmpty()) return
            for (direction in Direction.Plane.HORIZONTAL) {
                val sides = pathSideDirections(direction).shuffled(random)
                for (distance in listOf(10, 14, 18)) {
                    for (side in sides) {
                        val rough = center.relative(direction, distance).relative(side, 3)
                        val base = locateGround(rough) ?: continue
                        if (placeReliquaryTrophyStructure(level, setBlock, base, palette, pathColumns, random)) {
                            return
                        }
                    }
                }
            }
        }

        private fun ensureCourtTrophyDisplay(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            layout: CourtLayout,
            palette: CultivationPalette,
            pathColumns: Set<Long>,
            random: RandomSource
        ) {
            if (palette.trophies.isEmpty()) return
            if (placeFallbackCourtTrophy(setBlock, altarCenter, palette, random)) return
            val courtCandidates = layout.groundByColumn.values
                .filter { ground ->
                    when (layout.zoneFor(ground, altarCenter)) {
                        CourtZone.PERIMETER_POCKET, CourtZone.CORNER, CourtZone.EDGE -> true
                        else -> false
                    }
                }
                .sortedWith(
                    compareByDescending<BlockPos> { maxOf(abs(it.x - altarCenter.x), abs(it.z - altarCenter.z)) }
                        .thenBy { abs(it.x - altarCenter.x) + abs(it.z - altarCenter.z) }
                )
            for (ground in courtCandidates) {
                if (placeReliquaryTrophyStructure(level, setBlock, ground, palette, pathColumns, random)) {
                    return
                }
                val trophy = palette.trophy(random) ?: return
                if (level.getBlockState(ground.above()).`is`(ModBlocks.OBELISK.get()) || level.getBlockState(ground.above(2)).`is`(ModBlocks.OBELISK.get())) continue
                if (!setBlock(ground, generatedState(palette.structure(random), ground), 3)) continue
                if (!setBlock(ground.above(), generatedState(Blocks.WAXED_EXPOSED_CUT_COPPER, ground.above()), 3)) continue
                if (setBlock(ground.above(2), generatedState(trophy, ground.above(2)), 3)) {
                    return
                }
            }
        }

        private fun placeFallbackCourtTrophy(
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            palette: CultivationPalette,
            random: RandomSource
        ): Boolean {
            val trophy = palette.trophy(random) ?: return false
            val baseY = altarCenter.y - 2
            val candidates = listOf(
                altarCenter.offset(6, 0, 6),
                altarCenter.offset(-6, 0, 6),
                altarCenter.offset(6, 0, -6),
                altarCenter.offset(-6, 0, -6)
            )
            for (candidate in candidates) {
                val base = BlockPos(candidate.x, baseY, candidate.z)
                if (!setBlock(base, generatedState(palette.structure(random), base), 3)) continue
                val plinth = base.above()
                if (!setBlock(plinth, generatedState(Blocks.WAXED_EXPOSED_CUT_COPPER, plinth), 3)) continue
                val display = base.above(2)
                if (setBlock(display, generatedState(trophy, display), 3)) {
                    return true
                }
            }
            return false
        }

        private fun placeWetBiomeOvergrowth(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            tiles: List<TilePlan>,
            random: RandomSource
        ) {
            val wetTiles = tiles.filter { isWetBiome(level, it.groundPos) }
            if (wetTiles.isEmpty()) return
            val attempts = (wetTiles.size * 2).coerceIn(24, 180)
            repeat(attempts) {
                val tile = wetTiles[random.nextInt(wetTiles.size)]
                val rough = tile.groundPos.offset(random.nextInt(TILE_SIZE * 2 + 1) - TILE_SIZE, 0, random.nextInt(TILE_SIZE * 2 + 1) - TILE_SIZE)
                val ground = terrainGroundNear(level, rough, tile.groundPos.y + 5, 1) ?: return@repeat
                placeWetOvergrowthAt(level, setBlock, ground, random)
            }
        }

        private fun placeWetBiomeOvergrowthAround(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            chunk: ChunkPos,
            center: BlockPos,
            radius: Int,
            random: RandomSource
        ) {
            if (!isWetBiome(level, center) { pos -> isInsideChunkBounds(pos, chunk) }) return
            repeat(96) {
                val rough = center.offset(random.nextInt(radius * 2 + 1) - radius, 0, random.nextInt(radius * 2 + 1) - radius)
                val ground = terrainGroundInChunk(level, rough, chunk, center.y + 8, 1) ?: return@repeat
                placeWetOvergrowthAt(level, setBlock, ground, random)
            }
        }

        private fun placeWetOvergrowthAt(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            ground: BlockPos,
            random: RandomSource
        ) {
            val groundState = level.getBlockState(ground)
            val above = ground.above()
            if (!canReplaceDecoration(level, above)) return
            if (random.nextInt(100) < 18 && canReplaceWithMossyGround(groundState)) {
                setBlock(ground, generatedState(Blocks.MOSS_BLOCK, ground), 3)
            }
            val overgrowth = when (random.nextInt(12)) {
                0, 1, 2, 3, 4, 5 -> Blocks.MOSS_CARPET
                6, 7 -> Blocks.FERN
                8 -> Blocks.GRASS
                9 -> Blocks.AZALEA
                10 -> Blocks.FLOWERING_AZALEA
                else -> Blocks.MANGROVE_PROPAGULE
            }
            setBlock(above, generatedState(overgrowth, above), 3)
        }

        internal fun canWetOvergrowthReplaceGroundForTests(state: BlockState): Boolean =
            canReplaceWithMossyGround(state)

        private fun canReplaceWithMossyGround(state: BlockState): Boolean {
            val block = state.block
            if (block == Blocks.DIRT ||
                block == Blocks.COARSE_DIRT ||
                block == Blocks.ROOTED_DIRT ||
                block == Blocks.GRASS_BLOCK ||
                block == Blocks.PODZOL ||
                block == Blocks.MYCELIUM ||
                block == Blocks.MUD ||
                block == Blocks.CLAY ||
                state.`is`(BlockTags.MOSS_REPLACEABLE) ||
                state.`is`(BlockTags.BASE_STONE_OVERWORLD) ||
                state.`is`(BlockTags.BASE_STONE_NETHER)
            ) {
                return true
            }

            val path = BuiltInRegistries.BLOCK.getKey(block).path
            if (path.contains("brick") ||
                path.contains("tile") ||
                path.contains("plank") ||
                path.contains("slab") ||
                path.contains("stair") ||
                path.contains("wall") ||
                path.contains("fence") ||
                path.contains("ore")
            ) {
                return false
            }

            return path == "dirt" ||
                path.endsWith("_dirt") ||
                path.contains("grass_block") ||
                path.endsWith("_grass") ||
                path.contains("podzol") ||
                path.contains("mycelium") ||
                path.contains("mud") ||
                path.contains("soil") ||
                path.contains("loam") ||
                path.contains("silt") ||
                path.contains("peat") ||
                path.contains("humus") ||
                path.contains("regolith") ||
                path.contains("clay") ||
                path.endsWith("_stone") ||
                path.contains("limestone") ||
                path.contains("shale") ||
                path.contains("slate") ||
                path.contains("gneiss") ||
                path.contains("granite") ||
                path.contains("diorite") ||
                path.contains("andesite") ||
                path.contains("basalt")
        }

        private fun isWetBiome(level: LevelAccessor, pos: BlockPos, allowed: ((BlockPos) -> Boolean)? = null): Boolean {
            if (allowed != null && !allowed(pos)) return false
            val biome = level.getBiome(pos)
            if (biome.`is`(Tags.Biomes.IS_DRY) || biome.`is`(Tags.Biomes.IS_DRY_OVERWORLD)) return false
            if (
                biome.`is`(Tags.Biomes.IS_WET) ||
                biome.`is`(Tags.Biomes.IS_WET_OVERWORLD) ||
                biome.`is`(Tags.Biomes.IS_SWAMP) ||
                biome.`is`(Tags.Biomes.IS_LUSH) ||
                biome.`is`(Tags.Biomes.IS_WATER) ||
                biome.`is`(BiomeTags.IS_OCEAN) ||
                biome.`is`(BiomeTags.IS_RIVER) ||
                biome.`is`(BiomeTags.IS_JUNGLE)
            ) {
                return true
            }
            return hasLocalMoisture(level, pos, allowed)
        }

        private fun isDryBiome(level: LevelAccessor, pos: BlockPos, allowed: ((BlockPos) -> Boolean)? = null): Boolean {
            if (allowed != null && !allowed(pos)) return false
            val biome = level.getBiome(pos)
            if (biome.`is`(Tags.Biomes.IS_DRY) || biome.`is`(Tags.Biomes.IS_DRY_OVERWORLD)) return true
            val biomePath = biome.unwrapKey().map { it.location().path.lowercase() }.orElse("")
            return biomePath.contains("desert") ||
                biomePath.contains("savanna") ||
                biomePath.contains("badlands") ||
                biomePath.contains("arid") ||
                biomePath.contains("dunes") ||
                biomePath.contains("wasteland")
        }

        private fun hasLocalMoisture(level: LevelAccessor, center: BlockPos, allowed: ((BlockPos) -> Boolean)? = null): Boolean {
            var moistureScore = 0
            for (dx in -2..2) {
                for (dz in -2..2) {
                    if (dx == 0 && dz == 0) continue
                    val rough = center.offset(dx, 0, dz)
                    val sample = if (allowed != null) {
                        terrainGroundAllowed(level, rough, center.y + 4, 2, allowed)
                    } else {
                        terrainGroundNear(level, rough, center.y + 4, 2)
                    } ?: continue
                    if (allowed != null && !allowed(sample.above())) continue
                    val groundState = level.getBlockState(sample)
                    val aboveState = level.getBlockState(sample.above())
                    if (isWaterLike(groundState, aboveState)) moistureScore += 3
                    if (isMoistGroundPath(groundState)) moistureScore += 2
                    if (isWetFoliagePath(aboveState)) moistureScore += 1
                    if (moistureScore >= 4) return true
                }
            }
            return false
        }

        private fun isWaterLike(groundState: BlockState, aboveState: BlockState): Boolean =
            !groundState.fluidState.isEmpty ||
                !aboveState.fluidState.isEmpty ||
                groundState.`is`(Blocks.WATER) ||
                aboveState.`is`(Blocks.WATER) ||
                groundState.`is`(Blocks.SEAGRASS) ||
                aboveState.`is`(Blocks.SEAGRASS) ||
                groundState.`is`(Blocks.TALL_SEAGRASS) ||
                aboveState.`is`(Blocks.TALL_SEAGRASS) ||
                groundState.`is`(Blocks.LILY_PAD) ||
                aboveState.`is`(Blocks.LILY_PAD)

        private fun isMoistGroundPath(state: BlockState): Boolean {
            val path = BuiltInRegistries.BLOCK.getKey(state.block).path
            return path.contains("mud") ||
                path.contains("clay") ||
                path.contains("peat") ||
                path.contains("silt") ||
                path.contains("moss") ||
                path.contains("mangrove") ||
                path.contains("mycel") ||
                path.contains("podzol")
        }

        private fun isWetFoliagePath(state: BlockState): Boolean {
            val path = BuiltInRegistries.BLOCK.getKey(state.block).path
            return path.contains("reed") ||
                path.contains("cattail") ||
                path.contains("mangrove") ||
                path.contains("willow") ||
                path.contains("fern") ||
                path.contains("vine") ||
                path.contains("lily") ||
                path.contains("mushroom") ||
                path.contains("azalea")
        }

        private fun placeTrophyDisplay(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, base: BlockPos, palette: CultivationPalette, pathColumns: Set<Long>, random: RandomSource): Boolean {
            val trophy = palette.trophy(random) ?: return false
            if (!canUseTileGround(level, base, 3)) return false
            if (columnKey(base) in pathColumns) return false
            return placeReliquaryCage(level, setBlock, base, trophy, pathColumns, random)
        }

        private fun placeReliquaryCage(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            trophy: Block,
            pathColumns: Set<Long>,
            random: RandomSource
        ): Boolean {
            if (!canUseTileGround(level, base, 4)) return false
            if (!canPlaceTrophyColumn(level, base, pathColumns)) return false
            placeGround(level, setBlock, base, copperContainmentBlock(base))
            val support = base.above()
            val trophyPos = base.above(2)
            if (!placeSupportedAbove(level, setBlock, support, Blocks.WAXED_EXPOSED_CUT_COPPER)) return false
            if (!placeSupportedAbove(level, setBlock, trophyPos, trophy)) return false
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(2).forEach { direction ->
                val barPos = base.relative(direction).above()
                if (isSupportedGround(level, barPos.below(), level.getBlockState(barPos.below())) && canReplaceDecoration(level, barPos)) {
                    setBlock(barPos, generatedState(copperBarsBlock(barPos), barPos), 3)
                }
            }
            val lampPos = base.relative(Direction.Plane.HORIZONTAL.toList().shuffled(random).first()).above()
            if (isSupportedGround(level, lampPos.below(), level.getBlockState(lampPos.below())) && canReplaceDecoration(level, lampPos)) {
                setBlock(lampPos, generatedState(copperLanternBlock(lampPos, random.nextBoolean()), lampPos), 3)
            }
            return true
        }

        private fun canPlaceTrophyColumn(level: LevelAccessor, base: BlockPos, pathColumns: Set<Long>): Boolean =
            columnKey(base) !in pathColumns &&
                canReplaceDecoration(level, base.above()) &&
                canReplaceDecoration(level, base.above(2)) &&
                canReplaceDecoration(level, base.above(3))

        private fun placeBloodCatchment(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            random: RandomSource
        ): Boolean {
            if (!canUseTileGround(level, base, 3)) return false
            placeGround(level, setBlock, base, copperContainmentBlock(base))
            val basin = base.above()
            if (!canReplaceDecoration(level, basin)) return false
            setBlock(basin, generatedState(copperCauldronBlock(basin), basin), 3)
            Direction.Plane.HORIZONTAL.toList().shuffled(random).take(3).forEach { direction ->
                val rim = base.relative(direction)
                if (canUseTileGround(level, rim, 1)) {
                    placeGround(level, setBlock, rim, copperTileBlock(rim))
                    if (random.nextBoolean()) {
                        placeSupportedAbove(level, setBlock, rim.above(), copperChainBlock(rim.above()))
                    }
                }
            }
            val lamp = base.above(2)
            placeHangingLantern(level, setBlock, lamp, copperLanternBlock(lamp, true))
            return true
        }

        private fun placeValveShrine(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            facing: Direction,
            random: RandomSource
        ): Boolean {
            if (!canUseTileGround(level, base, 3)) return false
            val side = if (facing.axis == Direction.Axis.X) Direction.NORTH else Direction.EAST
            val left = base.relative(side)
            val right = base.relative(side.opposite)
            if (!canUseTileGround(level, left, 2) || !canUseTileGround(level, right, 2)) return false
            placeGround(level, setBlock, base, copperTileBlock(base))
            placeGround(level, setBlock, left, copperContainmentBlock(left))
            placeGround(level, setBlock, right, copperContainmentBlock(right))
            placeSupportedAbove(level, setBlock, left.above(), Blocks.STRIPPED_WARPED_STEM)
            placeSupportedAbove(level, setBlock, right.above(), Blocks.STRIPPED_WARPED_STEM)
            val valve = base.above()
            if (canReplaceDecoration(level, valve)) {
                setBlock(valve, directionalState(copperValveBlock(valve), valve, facing), 3)
            }
            val roof = base.above(2)
            if (canReplaceDecoration(level, roof)) {
                setBlock(roof, generatedState(copperRoofSlab(roof), roof), 3)
            }
            if (random.nextBoolean()) {
                val access = base.relative(facing.opposite).above()
                if (canReplaceDecoration(level, access)) setBlock(access, directionalState(copperVerticalAccessBlock(), access, facing), 3)
            }
            return true
        }

        private fun placeConduitRun(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            direction: Direction,
            random: RandomSource
        ): Boolean {
            var placed = false
            for (step in 0..3) {
                val ground = terrainGroundNear(level, base.relative(direction, step), base.y + 4, 1) ?: continue
                if (!canUseTileGround(level, ground, 1)) continue
                if (step == 0) placeGround(level, setBlock, ground, copperControlPlateBlock(ground)) else placeGround(level, setBlock, ground, Blocks.PACKED_MUD)
                val conduit = ground.above()
                if (canReplaceDecoration(level, conduit)) {
                    val block = if (random.nextBoolean()) copperRailBlock(conduit) else copperChainBlock(conduit)
                    setBlock(conduit, directionalState(block, conduit, direction), 3)
                    placed = true
                }
            }
            return placed
        }

        private fun directionalState(block: Block, pos: BlockPos, facing: Direction): BlockState {
            var state = generatedState(block, pos)
            if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, if (facing.axis.isHorizontal) facing else Direction.NORTH)
            }
            if (state.hasProperty(BlockStateProperties.FACING)) {
                state = state.setValue(BlockStateProperties.FACING, facing)
            }
            return state
        }

        private fun placeHangingLantern(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            pos: BlockPos,
            block: Block
        ): Boolean {
            if (!canReplaceDecoration(level, pos)) return false
            val supportPos = pos.above()
            val supportState = level.getBlockState(supportPos)
            if (!supportState.isFaceSturdy(level, supportPos, Direction.DOWN)) return false
            var state = generatedState(block, pos)
            if (state.hasProperty(BlockStateProperties.HANGING)) {
                state = state.setValue(BlockStateProperties.HANGING, true)
            }
            return setBlock(pos, state, 3)
        }

        private fun placeWallMountedDetail(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            pos: BlockPos,
            facing: Direction,
            block: Block
        ): Boolean {
            if (!canReplaceDecoration(level, pos)) return false
            val supportPos = pos.relative(facing.opposite)
            val supportState = level.getBlockState(supportPos)
            if (!supportState.isFaceSturdy(level, supportPos, facing)) return false
            var state = directionalState(block, pos, facing)
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
            }
            return setBlock(pos, state, 3)
        }

        private fun terrainGroundNear(level: LevelAccessor, rough: BlockPos, maxY: Int, clearance: Int): BlockPos? {
            val y = findSurfaceY(level, rough.x, rough.z, maxY, clearance) ?: return null
            return BlockPos(rough.x, y, rough.z)
        }

        private fun placeGround(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            if (!canUseTileGround(level, pos, clearance = 1)) return false
            return setBlock(pos, generatedState(block, pos), 3)
        }

        private fun placeSupportedAbove(level: LevelAccessor, setBlock: (BlockPos, BlockState, Int) -> Boolean, pos: BlockPos, block: Block): Boolean {
            val below = pos.below()
            if (!isSupportedGround(level, below, level.getBlockState(below))) return false
            if (!canReplaceDecoration(level, pos)) return false
            return setBlock(pos, generatedState(resolveDecorDisplayBlock(block), pos), 3)
        }

        private fun resolveDecorDisplayBlock(block: Block): Block {
            if (!isPottablePlantDisplayBlock(block)) return block
            val id = BuiltInRegistries.BLOCK.getKey(block)
            val pottedId = ResourceLocation.tryParse("${id.namespace}:potted_${id.path}") ?: return block
            val potted = BuiltInRegistries.BLOCK.get(pottedId)
            return if (potted == Blocks.AIR && pottedId.path != "air") block else potted
        }

        private fun isPottablePlantDisplayBlock(block: Block): Boolean {
            val path = BuiltInRegistries.BLOCK.getKey(block).path
            if (path.startsWith("potted_")) return false
            if (path == "dead_bush") return false
            if (block is BushBlock) return true
            return path == "chorus_flower" ||
                path.endsWith("_flower") ||
                path.endsWith("_flowers") ||
                path.endsWith("_mushroom") ||
                path.endsWith("_roots") ||
                path.endsWith("_sapling") ||
                path.endsWith("_bush") ||
                path.endsWith("_shrub") ||
                path.endsWith("_fern") ||
                path.endsWith("_sprout") ||
                path.endsWith("_shoots")
        }

        private fun terrainGroundInChunk(level: LevelAccessor, rough: BlockPos, chunk: ChunkPos, maxY: Int, clearance: Int): BlockPos? {
            if (!isInsideChunkBounds(rough, chunk)) return null
            val y = findSurfaceY(level, rough.x, rough.z, maxY, clearance) ?: return null
            val pos = BlockPos(rough.x, y, rough.z)
            return if (isInsideChunkBounds(pos, chunk)) pos else null
        }

        private fun terrainGroundAllowed(
            level: LevelAccessor,
            rough: BlockPos,
            maxY: Int,
            clearance: Int,
            allowed: (BlockPos) -> Boolean
        ): BlockPos? {
            if (!allowed(rough)) return null
            val ground = terrainGroundNear(level, rough, maxY, clearance) ?: return null
            return if (allowed(ground)) ground else null
        }

        private fun pathSideDirections(direction: Direction): List<Direction> =
            if (direction.axis == Direction.Axis.X) listOf(Direction.NORTH, Direction.SOUTH) else listOf(Direction.EAST, Direction.WEST)

        private fun placeMinimalPath(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            pos: BlockPos
        ): Boolean {
            return placeGround(level, setBlock, pos, Blocks.PACKED_MUD)
        }

        private fun placeThreeWidePath(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            chunk: ChunkPos,
            center: BlockPos,
            direction: Direction,
            step: Int,
            maxY: Int
        ) {
            val main = center.relative(direction, step)
            val previousMain = center.relative(direction, step - 1)
            val lanes = listOf<Direction?>(null) + pathSideDirections(direction)
            lanes.forEach { side ->
                val rough = side?.let { main.relative(it) } ?: main
                val previousRough = side?.let { previousMain.relative(it) } ?: previousMain
                val ground = terrainGroundInChunk(level, rough, chunk, maxY, 1) ?: return@forEach
                val previousGround = terrainGroundInChunk(level, previousRough, chunk, maxY, 1)
                if (previousGround != null && abs(ground.y - previousGround.y) > 1) return@forEach
                placeMinimalPath(level, setBlock, ground)
                if (previousGround != null && abs(ground.y - previousGround.y) == 1) {
                    placeSlopeStep(level, setBlock, previousGround, ground)
                }
            }
        }

        private fun placeAltarApproachStairs(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            chunk: ChunkPos,
            center: BlockPos,
            direction: Direction,
            maxY: Int
        ) {
            val lanes = listOf<Direction?>(null) + pathSideDirections(direction)
            lanes.forEach { side ->
                val placedYs = mutableSetOf<Int>()
                for (step in 1..ALTAR_MAX_FOUNDATION_DROP) {
                    val main = center.relative(direction, ALTAR_RADIUS + step)
                    val rough = side?.let { main.relative(it) } ?: main
                    val ground = terrainGroundInChunk(level, rough, chunk, maxY, 1) ?: return@forEach
                    val stairY = altarApproachStairY(center.y, ground.y, ALTAR_MAX_FOUNDATION_DROP) ?: continue
                    if (!placedYs.add(stairY)) continue
                    val stairPos = BlockPos(ground.x, stairY, ground.z)
                    if (!canReplaceDecoration(level, stairPos)) return@forEach
                    setBlock(
                        stairPos,
                        directionalState(Blocks.CUT_COPPER_STAIRS, stairPos, direction.opposite),
                        3
                    )
                }
            }
        }

        private fun placeReliquaryTrophyStructure(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            base: BlockPos,
            palette: CultivationPalette,
            pathColumns: Set<Long>,
            random: RandomSource
        ): Boolean {
            if (!canUseTileGround(level, base, 4)) return false
            if (!canPlaceTrophyColumn(level, base, pathColumns)) return false
            val trophy = palette.trophy(random) ?: return false
            placeGround(level, setBlock, base, palette.structure(random))
            listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST).forEach { direction ->
                val arm = base.relative(direction)
                if (canUseTileGround(level, arm, 2)) {
                    placeGround(level, setBlock, arm, palette.path(random))
                    placeSupportedAbove(level, setBlock, arm.above(), palette.decoration(random))
                }
            }
            placeSupportedAbove(level, setBlock, base.above(), Blocks.WAXED_EXPOSED_CUT_COPPER)
            placeSupportedAbove(level, setBlock, base.above(2), trophy)
            return true
        }

        private fun placeMinimalReliquaryDressing(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            chunk: ChunkPos,
            center: BlockPos,
            palette: CultivationPalette,
            random: RandomSource,
            pathPlan: MinimalReliquaryPathPlan = planMinimalReliquaryPaths(center, random)
        ) {
            val pathColumns = minimalReliquaryPathColumns(center, pathPlan.pathLengths).toMutableSet()
            val maxY = level.maxBuildHeight - 2
            val pathLengths = pathPlan.pathLengths
            val allowed: (BlockPos) -> Boolean = { pos -> isInsideChunkBounds(pos, chunk) }

            Direction.Plane.HORIZONTAL.forEach { direction ->
                val pathLength = pathLengths[direction] ?: RELIQUARY_MIN_RADIUS
                placeAltarApproachStairs(level, setBlock, chunk, center, direction, maxY)
                for (step in 4..pathLength) {
                    placeThreeWidePath(level, setBlock, chunk, center, direction, step, maxY)
                }
            }

            for (direction in Direction.Plane.HORIZONTAL) {
                val side = pathSideDirections(direction).shuffled(random).first()
                for (distance in listOf(10, 18, 26, 33)) {
                    val rough = center.relative(direction, distance).relative(side, 3)
                    val displayBase = terrainGroundInChunk(level, rough, chunk, maxY, 4) ?: continue
                    placeReliquaryTrophyStructure(level, setBlock, displayBase, palette, pathColumns, random)
                }
            }

            listOf(
                -18 to -18, -18 to 18, 18 to -18, 18 to 18,
                -28 to -8, -28 to 8, 28 to -8, 28 to 8,
                -8 to -28, 8 to -28, -8 to 28, 8 to 28,
                -12 to -4, 12 to 4, -4 to 12, 4 to -12
            ).forEach { (dx, dz) ->
                val ground = terrainGroundInChunk(level, center.offset(dx, 0, dz), chunk, maxY, 1) ?: return@forEach
                placeSupportedAbove(level, setBlock, ground.above(), palette.decoration(random))
            }
            val courtLayout = shapeReliquaryCourt(level, setBlock, center, palette, pathColumns, allowed)
            ensureCourtTrophyDisplay(level, setBlock, center, courtLayout, palette, pathColumns, random)
            ensureReliquaryTrophyDisplay(level, setBlock, center, palette, pathColumns, random) { rough ->
                terrainGroundInChunk(level, rough, chunk, maxY, 4)
            }
        }

        private fun planMinimalReliquaryPaths(center: BlockPos, random: RandomSource): MinimalReliquaryPathPlan {
            val pathLengths = Direction.Plane.HORIZONTAL.associateWith {
                RELIQUARY_MIN_RADIUS + random.nextInt(RELIQUARY_RADIUS - RELIQUARY_MIN_RADIUS + 1)
            }
            return MinimalReliquaryPathPlan(pathLengths)
        }

        private fun minimalReliquaryPathColumns(center: BlockPos, pathLengths: Map<Direction, Int>): Set<Long> {
            val pathColumns = mutableSetOf<Long>()
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val pathLength = pathLengths[direction] ?: RELIQUARY_MIN_RADIUS
                for (step in 4..pathLength) {
                    val pathCenter = center.relative(direction, step)
                    pathColumns += columnKey(pathCenter)
                    pathSideDirections(direction).forEach { sideDirection ->
                        pathColumns += columnKey(pathCenter.relative(sideDirection))
                    }
                }
            }
            return pathColumns
        }

        private fun altarReservedColumns(center: BlockPos): Set<Long> {
            val reserved = mutableSetOf<Long>()
            val radius = ALTAR_RADIUS + 4
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    reserved += columnKey(center.offset(dx, 0, dz))
                }
            }
            return reserved
        }

        private fun generatedState(block: Block): BlockState {
            var state = ageCopperBlock(block, null).defaultBlockState()
            if (state.hasProperty(BlockStateProperties.LIT)) {
                state = state.setValue(BlockStateProperties.LIT, true)
            }
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
            }
            if (state.hasProperty(BlockStateProperties.FACING) && state.getOptionalValue(BlockStateProperties.FACING).isPresent) {
                state = state.setValue(BlockStateProperties.FACING, Direction.UP)
            }
            return state
        }

        private fun generatedState(block: Block, pos: BlockPos, altarCenter: BlockPos? = null): BlockState {
            var state = ageCopperBlock(block, pos, altarCenter).defaultBlockState()
            if (state.hasProperty(BlockStateProperties.LIT)) {
                state = state.setValue(BlockStateProperties.LIT, true)
            }
            if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
            }
            if (state.hasProperty(BlockStateProperties.FACING) && state.getOptionalValue(BlockStateProperties.FACING).isPresent) {
                state = state.setValue(BlockStateProperties.FACING, Direction.UP)
            }
            return state
        }

        private fun ageCopperBlock(block: Block, pos: BlockPos?, altarCenter: BlockPos? = null): Block {
            val ageBias = pos?.let { copperAgeBias(it, altarCenter) } ?: CopperAgeBias.FRESH
            return when (block) {
                Blocks.RAW_COPPER_BLOCK,
                Blocks.COPPER_BLOCK,
                Blocks.EXPOSED_COPPER,
                Blocks.WEATHERED_COPPER,
                Blocks.OXIDIZED_COPPER -> when (ageBias) {
                    CopperAgeBias.FRESH -> Blocks.COPPER_BLOCK
                    CopperAgeBias.EXPOSED -> Blocks.EXPOSED_COPPER
                    CopperAgeBias.WEATHERED -> Blocks.WEATHERED_COPPER
                }
                Blocks.CUT_COPPER,
                Blocks.EXPOSED_CUT_COPPER,
                Blocks.WEATHERED_CUT_COPPER,
                Blocks.OXIDIZED_CUT_COPPER -> when (ageBias) {
                    CopperAgeBias.FRESH -> Blocks.CUT_COPPER
                    CopperAgeBias.EXPOSED -> Blocks.EXPOSED_CUT_COPPER
                    CopperAgeBias.WEATHERED -> Blocks.WEATHERED_CUT_COPPER
                }
                Blocks.CUT_COPPER_STAIRS,
                Blocks.EXPOSED_CUT_COPPER_STAIRS,
                Blocks.WEATHERED_CUT_COPPER_STAIRS,
                Blocks.OXIDIZED_CUT_COPPER_STAIRS -> when (ageBias) {
                    CopperAgeBias.FRESH -> Blocks.CUT_COPPER_STAIRS
                    CopperAgeBias.EXPOSED -> Blocks.EXPOSED_CUT_COPPER_STAIRS
                    CopperAgeBias.WEATHERED -> Blocks.WEATHERED_CUT_COPPER_STAIRS
                }
                Blocks.CUT_COPPER_SLAB,
                Blocks.EXPOSED_CUT_COPPER_SLAB,
                Blocks.WEATHERED_CUT_COPPER_SLAB,
                Blocks.OXIDIZED_CUT_COPPER_SLAB -> when (ageBias) {
                    CopperAgeBias.FRESH -> Blocks.CUT_COPPER_SLAB
                    CopperAgeBias.EXPOSED -> Blocks.EXPOSED_CUT_COPPER_SLAB
                    CopperAgeBias.WEATHERED -> Blocks.WEATHERED_CUT_COPPER_SLAB
                }
                else -> block
            }
        }

        private enum class CopperAgeBias {
            FRESH,
            EXPOSED,
            WEATHERED
        }

        private fun copperAgeBias(pos: BlockPos, altarCenter: BlockPos? = null): CopperAgeBias {
            if (altarCenter != null) {
                val radius = maxOf(abs(pos.x - altarCenter.x), abs(pos.z - altarCenter.z))
                return when {
                    radius <= 1 -> CopperAgeBias.FRESH
                    radius <= 3 -> CopperAgeBias.EXPOSED
                    else -> CopperAgeBias.WEATHERED
                }
            }
            return if (useWeatheredCopper(pos)) CopperAgeBias.WEATHERED else CopperAgeBias.EXPOSED
        }

        private fun useWeatheredCopper(pos: BlockPos): Boolean {
            var value = pos.asLong() xor 0x4f1bbcdc4d1a49d3L
            value = value xor (value ushr 33)
            value *= -49064778989728563L
            value = value xor (value ushr 33)
            return (value and 3L) == 0L
        }

        private fun placeElevatedAltar(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            surfaceByOffset: Map<Pair<Int, Int>, Int>,
            palette: CultivationPalette,
            courtLayout: CourtLayout,
            random: RandomSource,
            allowed: ((BlockPos) -> Boolean)? = null
        ): BlockPos {
            val baseY = center.y
            surfaceByOffset.forEach { (offset, surfaceY) ->
                val dx = offset.first
                val dz = offset.second
                val distance = maxOf(abs(dx), abs(dz))
                val topY = baseY + when {
                    distance <= 1 -> ALTAR_HEIGHT
                    distance == 2 -> 1
                    else -> 0
                }
                val block = when {
                    distance == 0 -> Blocks.RAW_COPPER_BLOCK
                    distance <= 1 -> Blocks.COPPER_BLOCK
                    distance == 2 -> if ((abs(dx) + abs(dz)) % 2 == 0) Blocks.CUT_COPPER else Blocks.COPPER_BLOCK
                    else -> if (random.nextInt(4) == 0) Blocks.RAW_COPPER_BLOCK else Blocks.CUT_COPPER
                }
                for (y in surfaceY + 1..topY) {
                    val pos = BlockPos(center.x + dx, y, center.z + dz)
                    setBlock(pos, generatedState(block, pos, center), 3)
                }
            }
            val pedestalPos = center.above(ALTAR_HEIGHT)
            setBlock(pedestalPos, generatedState(Blocks.RAW_COPPER_BLOCK, pedestalPos, center), 3)
            placeAltarWelcomeDetails(level, setBlock, center, palette, courtLayout, allowed)
            val fontPos = center.above(ALTAR_HEIGHT + 1)
            for (dy in 1..FONT_CLEARANCE) {
                setBlock(fontPos.above(dy), Blocks.AIR.defaultBlockState(), 3)
            }
            return fontPos
        }

        private fun shapeReliquaryCourt(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            palette: CultivationPalette,
            pathColumns: Set<Long>,
            allowed: (BlockPos) -> Boolean = { true }
        ): CourtLayout {
            val layout = planCourtLayout(altarCenter, pathColumns)
            val groundByColumn = mutableMapOf<Long, BlockPos>()
            for (x in layout.minX..layout.maxX) {
                for (z in layout.minZ..layout.maxZ) {
                    if (!allowed(BlockPos(x, altarCenter.y, z))) continue
                    val ground = findCourtGround(level, altarCenter, x, z) ?: continue
                    groundByColumn[columnKey(ground)] = ground.immutable()
                    reclaimCourtColumn(level, setBlock, ground, altarCenter)
                    val zone = layout.zoneFor(ground, altarCenter)
                    val block = courtFloorBlock(ground, palette, zone)
                    setBlock(ground, generatedState(block, ground, altarCenter), 3)
                }
            }
            val groundedLayout = layout.copy(groundByColumn = groundByColumn)
            blendCourtEntries(level, setBlock, altarCenter, groundedLayout, allowed)
            placeCourtPerimeterPots(level, setBlock, altarCenter, groundedLayout, allowed)
            reinforceCourtReadability(level, setBlock, altarCenter, allowed)
            return groundedLayout
        }

        private fun reinforceCourtReadability(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            allowed: (BlockPos) -> Boolean
        ) {
            reinforceCourtCorners(setBlock, altarCenter, allowed)
            placeFallbackCourtPot(level, setBlock, altarCenter, allowed)
            var reinforcedEntries = 0
            for (direction in Direction.Plane.HORIZONTAL) {
                if (reinforceCourtEntry(setBlock, altarCenter, direction, allowed)) {
                    reinforcedEntries++
                }
                if (reinforcedEntries >= 2) return
            }
        }

        private fun reinforceCourtCorners(
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            allowed: (BlockPos) -> Boolean
        ) {
            val floorY = altarCenter.y - 2
            for (dx in listOf(-5, 5)) {
                for (dz in listOf(-5, 5)) {
                    val corner = BlockPos(altarCenter.x + dx, floorY, altarCenter.z + dz)
                    if (allowed(corner)) {
                        setBlock(corner, generatedState(Blocks.CUT_COPPER, corner), 3)
                    }
                }
            }
        }

        private fun placeFallbackCourtPot(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            allowed: (BlockPos) -> Boolean
        ) {
            val dry = isDryBiome(level, altarCenter, allowed)
            val baseY = altarCenter.y - 2
            val candidates = listOf(
                altarCenter.offset(5, 0, 5),
                altarCenter.offset(-5, 0, 5),
                altarCenter.offset(5, 0, -5),
                altarCenter.offset(-5, 0, -5),
                altarCenter.offset(5, 0, 0),
                altarCenter.offset(-5, 0, 0),
                altarCenter.offset(0, 0, 5),
                altarCenter.offset(0, 0, -5)
            )
            for (candidate in candidates) {
                if (!allowed(candidate)) continue
                val ground = BlockPos(candidate.x, baseY, candidate.z)
                val potPos = ground.above(3)
                if (!allowed(ground) || !allowed(potPos)) continue
                if (maxOf(abs(ground.x - altarCenter.x), abs(ground.z - altarCenter.z)) !in 4..6) continue
                if (level.getBlockState(potPos).`is`(ModBlocks.OBELISK.get())) continue
                setBlock(potPos.below(), generatedState(Blocks.CUT_COPPER, potPos.below()), 3)
                setBlock(potPos, generatedState(pickCourtPotBlock(dry, potPos), potPos), 3)
                return
            }
        }

        private fun reinforceCourtEntry(
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            direction: Direction,
            allowed: (BlockPos) -> Boolean
        ): Boolean {
            var placed = false
            val floorY = altarCenter.y - 2
            for (step in 4..6) {
                for (side in -1..1) {
                    val rough = altarCenter.relative(direction, step)
                    val column = if (direction.axis == Direction.Axis.X) {
                        rough.relative(Direction.SOUTH, side)
                    } else {
                        rough.relative(Direction.EAST, side)
                    }
                    val interior = BlockPos(column.x, floorY, column.z)
                    if (!allowed(interior)) continue
                    setBlock(interior, generatedState(Blocks.CUT_COPPER, interior), 3)
                }
            }
            for (step in 7..9) {
                for (side in -1..1) {
                    val rough = altarCenter.relative(direction, step)
                    val column = if (direction.axis == Direction.Axis.X) {
                        rough.relative(Direction.SOUTH, side)
                    } else {
                        rough.relative(Direction.EAST, side)
                    }
                    val exterior = BlockPos(column.x, floorY, column.z)
                    if (!allowed(exterior)) continue
                    setBlock(exterior, generatedState(Blocks.PACKED_MUD, exterior), 3)
                    placed = true
                }
            }
            return placed
        }

        private fun planCourtLayout(altarCenter: BlockPos, pathColumns: Set<Long>): CourtLayout {
            var minX = altarCenter.x - (ALTAR_RADIUS + 1)
            var maxX = altarCenter.x + (ALTAR_RADIUS + 1)
            var minZ = altarCenter.z - (ALTAR_RADIUS + 1)
            var maxZ = altarCenter.z + (ALTAR_RADIUS + 1)
            pathColumns.forEach { key ->
                val x = columnX(key)
                val z = columnZ(key)
                if (abs(x - altarCenter.x) > COURT_PATH_SCAN_RADIUS || abs(z - altarCenter.z) > COURT_PATH_SCAN_RADIUS) return@forEach
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minZ = minOf(minZ, z)
                maxZ = maxOf(maxZ, z)
            }
            val targetSpan = (maxOf(maxX - minX, maxZ - minZ) + 2).coerceIn(COURT_MIN_HALF_EXTENT * 2, COURT_MAX_HALF_EXTENT * 2)
            while (maxX - minX < targetSpan) {
                minX--
                if (maxX - minX < targetSpan) maxX++
            }
            while (maxZ - minZ < targetSpan) {
                minZ--
                if (maxZ - minZ < targetSpan) maxZ++
            }
            val detectedEntryOffsets = Direction.Plane.HORIZONTAL.associateWith { direction ->
                detectCourtEntryOffsets(altarCenter, direction, minX, maxX, minZ, maxZ, pathColumns)
                    .let { offsets -> if (offsets.isEmpty()) offsets else offsets + 0 }
            }.filterValues { it.isNotEmpty() }
            val entryOffsets = ensureMinimumCourtEntryOffsets(altarCenter, pathColumns, detectedEntryOffsets)
            return CourtLayout(minX, maxX, minZ, maxZ, entryOffsets)
        }

        private fun ensureMinimumCourtEntryOffsets(
            altarCenter: BlockPos,
            pathColumns: Set<Long>,
            detected: Map<Direction, Set<Int>>
        ): Map<Direction, Set<Int>> {
            if (detected.size >= 2) return detected
            val augmented = detected.toMutableMap()
            val candidateDirections = Direction.Plane.HORIZONTAL
                .associateWith { direction ->
                    scoreCenteredCourtEntryDirection(altarCenter, direction, pathColumns)
                }
                .filterValues { it > 0 }
                .toList()
                .sortedByDescending { it.second }
                .map { it.first }
            for (direction in candidateDirections) {
                augmented.putIfAbsent(direction, setOf(0))
                if (augmented.size >= 2) break
            }
            return augmented
        }

        private fun scoreCenteredCourtEntryDirection(altarCenter: BlockPos, direction: Direction, pathColumns: Set<Long>): Int {
            var score = 0
            for (distance in ALTAR_RADIUS + 1..COURT_ENTRY_SCAN_DISTANCE) {
                val probe = altarCenter.relative(direction, distance)
                for (side in -1..1) {
                    val lane = if (direction.axis == Direction.Axis.X) probe.offset(0, 0, side) else probe.offset(side, 0, 0)
                    if (columnKey(lane) in pathColumns) {
                        score++
                    }
                }
            }
            return score
        }

        private fun detectCourtEntryOffsets(
            altarCenter: BlockPos,
            direction: Direction,
            minX: Int,
            maxX: Int,
            minZ: Int,
            maxZ: Int,
            pathColumns: Set<Long>
        ): Set<Int> {
            val offsets = mutableSetOf<Int>()
            for (distance in ALTAR_RADIUS + 1..COURT_ENTRY_SCAN_DISTANCE) {
                val probe = altarCenter.relative(direction, distance)
                val offset = if (direction.axis == Direction.Axis.X) probe.z - altarCenter.z else probe.x - altarCenter.x
                val withinCourtWidth = if (direction.axis == Direction.Axis.X) {
                    probe.z in (minZ - 1)..(maxZ + 1)
                } else {
                    probe.x in (minX - 1)..(maxX + 1)
                }
                if (!withinCourtWidth) continue
                if (listOf(0, -1, 1).any { side ->
                        val lane = if (direction.axis == Direction.Axis.X) probe.offset(0, 0, side) else probe.offset(side, 0, 0)
                        columnKey(lane) in pathColumns
                    }
                ) {
                    offsets += offset
                }
            }
            return offsets
        }

        private fun findCourtGround(level: LevelAccessor, altarCenter: BlockPos, x: Int, z: Int): BlockPos? {
            val top = altarCenter.y + ALTAR_MAX_FOUNDATION_DROP + 2
            val bottom = (altarCenter.y - (ALTAR_MAX_FOUNDATION_DROP + 4)).coerceAtLeast(level.minBuildHeight + 1)
            for (y in top downTo bottom) {
                val pos = BlockPos(x, y, z)
                val state = level.getBlockState(pos)
                if (canReclaimCourtClutter(state)) continue
                if (isSupportedGround(level, pos, state) && hasClearance(level, pos, 3)) return pos
            }
            return null
        }

        private fun reclaimCourtColumn(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            ground: BlockPos,
            altarCenter: BlockPos
        ) {
            if (maxOf(abs(ground.x - altarCenter.x), abs(ground.z - altarCenter.z)) <= ALTAR_RADIUS) return
            for (dy in 1..3) {
                val pos = ground.above(dy)
                val state = level.getBlockState(pos)
                if (state.isAir) continue
                if (canReclaimCourtClutter(state)) {
                    setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        private fun canReclaimCourtClutter(state: BlockState): Boolean {
            if (canReplaceSiteAirspace(state)) return true
            if (state.`is`(Blocks.PACKED_MUD) || state.`is`(Blocks.MUD) || state.`is`(Blocks.MOSS_CARPET)) return true
            if (state.`is`(Blocks.DARK_OAK_WALL_SIGN)) return true
            return isDisplayDetailBlock(state.block)
        }

        private fun courtFloorBlock(pos: BlockPos, palette: CultivationPalette, zone: CourtZone): Block =
            when (zone) {
                CourtZone.CORNER -> palette.courtAnchorBlock(pos)
                CourtZone.EDGE -> palette.courtSupportBlock(pos)
                CourtZone.ENTRY_THRESHOLD -> if (((pos.x + pos.z) and 1) == 0) palette.courtAnchorBlock(pos) else palette.courtSupportBlock(pos)
                CourtZone.ALTAR_THRESHOLD -> if (Math.floorMod(pos.x * 17 + pos.z * 13, 5) == 0) palette.courtSupportBlock(pos) else palette.courtPrimaryBlock(pos)
                CourtZone.CROSSROADS -> when (Math.floorMod(pos.x * 19 + pos.z * 31, 7)) {
                    0 -> palette.courtSupportBlock(pos)
                    1 -> palette.courtAnchorBlock(pos)
                    else -> palette.courtPrimaryBlock(pos)
                }
                CourtZone.PERIMETER_POCKET -> if (Math.floorMod(pos.x * 11 + pos.z * 7, 4) == 0) palette.courtAnchorBlock(pos) else palette.courtPrimaryBlock(pos)
            }

        private fun blendCourtEntries(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            layout: CourtLayout,
            allowed: (BlockPos) -> Boolean
        ) {
            for ((direction, offsets) in layout.entryOffsets) {
                for (offset in offsets) {
                    val edge = layout.edgePos(direction, offset, altarCenter)
                    if (!allowed(edge)) continue
                    val edgeGround = layout.groundByColumn[columnKey(edge)] ?: terrainGroundAllowed(level, edge, altarCenter.y + 5, 1, allowed) ?: continue
                    for (step in 1..2) {
                        val outside = edge.relative(direction, step)
                        if (!allowed(outside)) continue
                        val outsideGround = terrainGroundAllowed(level, outside, altarCenter.y + 5, 1, allowed) ?: continue
                        if (!canUseTileGround(level, outsideGround, 1)) continue
                        placeMinimalPath(level, setBlock, outsideGround)
                        if (abs(outsideGround.y - edgeGround.y) == 1) {
                            placeSlopeStep(level, setBlock, edgeGround, outsideGround)
                        }
                    }
                }
            }
        }

        private fun placeCourtPerimeterPots(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            altarCenter: BlockPos,
            layout: CourtLayout,
            allowed: (BlockPos) -> Boolean
        ) {
            val dry = isDryBiome(level, altarCenter, allowed)
            val pockets = listOf(
                BlockPos(layout.minX + 1, altarCenter.y, layout.minZ + 1),
                BlockPos(layout.minX + 1, altarCenter.y, layout.maxZ - 1),
                BlockPos(layout.maxX - 1, altarCenter.y, layout.minZ + 1),
                BlockPos(layout.maxX - 1, altarCenter.y, layout.maxZ - 1),
                BlockPos(altarCenter.x, altarCenter.y, layout.minZ + 1),
                BlockPos(altarCenter.x, altarCenter.y, layout.maxZ - 1),
                BlockPos(layout.minX + 1, altarCenter.y, altarCenter.z),
                BlockPos(layout.maxX - 1, altarCenter.y, altarCenter.z)
            )
            pockets.distinctBy { it.x to it.z }.forEach { rough ->
                val candidateOffsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
                val placed = candidateOffsets.any { (dx, dz) ->
                    val candidate = rough.offset(dx, 0, dz)
                    if (!allowed(candidate)) return@any false
                    val zone = layout.zoneFor(candidate, altarCenter)
                    if (zone != CourtZone.PERIMETER_POCKET && zone != CourtZone.CORNER) return@any false
                    val ground = layout.groundByColumn[columnKey(candidate)] ?: terrainGroundAllowed(level, candidate, altarCenter.y + 5, 1, allowed) ?: return@any false
                    if (!canUseTileGround(level, ground, 1) || !canReplaceDecoration(level, ground.above())) return@any false
                    setBlock(ground.above(), generatedState(courtPotBlock(dry, ground.above()), ground.above()), 3)
                }
                if (!placed) return@forEach
            }
        }

        private fun placeAltarWelcomeDetails(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos,
            palette: CultivationPalette,
            courtLayout: CourtLayout,
            allowed: ((BlockPos) -> Boolean)? = null
        ) {
            val baseY = center.y
            Direction.Plane.HORIZONTAL.forEach { direction ->
                val mid = center.relative(direction, 2)
                val outer = center.relative(direction, 3)
                val midPos = BlockPos(mid.x, baseY + 1, mid.z)
                val isActiveEntry = direction in courtLayout.entryOffsets.keys
                val midState = altarTopThresholdState(midPos, direction, isActiveEntry, palette)
                setBlock(midPos, midState, 3)
                val outerThreshold = altarLowerThreshold(level, center, outer, direction, isActiveEntry, palette, allowed)
                setBlock(outerThreshold.pos, outerThreshold.state, 3)
            }
            placeAltarCopperRoof(level, setBlock, center)
        }

        private fun altarTopThresholdState(
            midPos: BlockPos,
            direction: Direction,
            isActiveEntry: Boolean,
            palette: CultivationPalette
        ): BlockState =
            if (shouldUseAltarUpperThresholdStair(isActiveEntry)) {
                generatedState(Blocks.CUT_COPPER_STAIRS, midPos).setValue(BlockStateProperties.HORIZONTAL_FACING, direction.opposite)
            } else {
                generatedState(palette.courtPrimaryBlock(midPos), midPos)
            }

        private fun altarLowerThreshold(
            level: LevelAccessor,
            altarCenter: BlockPos,
            outerColumn: BlockPos,
            direction: Direction,
            isActiveEntry: Boolean,
            palette: CultivationPalette,
            allowed: ((BlockPos) -> Boolean)? = null
        ): ThresholdPlacement {
            val fallbackPos = BlockPos(outerColumn.x, altarCenter.y + 1, outerColumn.z)
            if (!isActiveEntry) {
                return ThresholdPlacement(fallbackPos, generatedState(palette.courtSupportBlock(fallbackPos), fallbackPos))
            }
            val outerGround = if (allowed != null) {
                terrainGroundAllowed(level, outerColumn, altarCenter.y + ALTAR_MAX_FOUNDATION_DROP + 2, 1, allowed)
            } else {
                terrainGroundNear(level, outerColumn, altarCenter.y + ALTAR_MAX_FOUNDATION_DROP + 2, 1)
            }
            val thresholdY = altarLowerThresholdY(altarCenter.y, outerGround?.y, isActiveEntry = true)
            val thresholdPos = if (thresholdY != null) BlockPos(outerColumn.x, thresholdY, outerColumn.z) else fallbackPos
            return if (thresholdY != null) {
                ThresholdPlacement(
                    thresholdPos,
                    generatedState(Blocks.CUT_COPPER_STAIRS, thresholdPos).setValue(BlockStateProperties.HORIZONTAL_FACING, direction.opposite)
                )
            } else {
                ThresholdPlacement(thresholdPos, Blocks.AIR.defaultBlockState())
            }
        }

        private fun courtPotBlock(dry: Boolean, pos: BlockPos): Block =
            pickCourtPotBlock(dry, pos)

        private fun placeAltarCopperRoof(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            center: BlockPos
        ) {
            val supportTopY = center.y + ALTAR_HEIGHT + 2
            listOf(-2 to -2, -2 to 2, 2 to -2, 2 to 2).forEach { (dx, dz) ->
                for (y in center.y + ALTAR_HEIGHT + 1..supportTopY) {
                    val pos = BlockPos(center.x + dx, y, center.z + dz)
                    val state = generatedState(Blocks.STRIPPED_WARPED_STEM, pos).let { support ->
                        if (support.hasProperty(BlockStateProperties.AXIS)) {
                            support.setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                        } else {
                            support
                        }
                    }
                    setBlock(pos, state, 3)
                }
            }
            val roofY = supportTopY + 1
            for (dx in -2..2) {
                for (dz in -2..2) {
                    if (dx == 0 && dz == 0) continue
                    if (maxOf(abs(dx), abs(dz)) != 2) continue
                    val pos = BlockPos(center.x + dx, roofY, center.z + dz)
                    val block = when {
                        abs(dx) == 2 && abs(dz) == 2 -> copperRoofSlab(pos)
                        dx == -2 -> copperRoofStair(pos)
                        dx == 2 -> copperRoofStair(pos)
                        dz == -2 -> copperRoofStair(pos)
                        dz == 2 -> copperRoofStair(pos)
                        else -> copperRoofBlock(pos)
                    }
                    val facing = when {
                        dx == -2 -> Direction.WEST
                        dx == 2 -> Direction.EAST
                        dz == -2 -> Direction.NORTH
                        dz == 2 -> Direction.SOUTH
                        else -> Direction.NORTH
                    }
                    setBlock(pos, directionalState(block, pos, facing), 3)
                }
            }
            placeAltarSideCopperLanterns(level, setBlock, center, supportTopY)
        }

        private fun placeAltarSideCopperLanterns(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
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
                placeWallMountedDetail(level, setBlock, pos, facing, altarSconceBlock(pos))
            }
        }

        private fun placeWetSupportVine(
            level: LevelAccessor,
            setBlock: (BlockPos, BlockState, Int) -> Boolean,
            support: BlockPos
        ) {
            Direction.Plane.HORIZONTAL.forEach { direction ->
                if (((support.asLong() xor direction.ordinal.toLong()) and 3L) != 0L) return@forEach
                val vinePos = support.relative(direction)
                if (!canReplaceDecoration(level, vinePos)) return@forEach
                val property = when (direction) {
                    Direction.NORTH -> BlockStateProperties.SOUTH
                    Direction.SOUTH -> BlockStateProperties.NORTH
                    Direction.WEST -> BlockStateProperties.EAST
                    Direction.EAST -> BlockStateProperties.WEST
                    else -> return@forEach
                }
                setBlock(vinePos, Blocks.VINE.defaultBlockState().setValue(property, true), 3)
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
                        maxOf(abs(dx), abs(dz)) == 2 -> 1
                        else -> 0
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
                abs(dx) == 2 && abs(dz) == 2 -> 2
                abs(dx) == 3 && abs(dz) == 3 -> 1
                (abs(dx) == 2 && dz == 0) || (dx == 0 && abs(dz) == 2) -> 1
                (abs(dx) == 3 && dz == 0) || (dx == 0 && abs(dz) == 3) -> 1
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

        private fun optionalModBlocks(modId: String, ids: List<String>): List<Block> {
            if (!ModList.get().isLoaded(modId)) return emptyList()
            return blocksOrEmpty(ids)
        }

        private fun optionalBlock(modId: String, id: String): Block? {
            if (!ModList.get().isLoaded(modId)) return null
            val location = ResourceLocation.tryParse(id) ?: return null
            val value = BuiltInRegistries.BLOCK.get(location)
            return if (value == Blocks.AIR && location.path != "air") null else value
        }

        private fun optionalBlock(modId: String, ids: List<String>): Block? =
            ids.firstNotNullOfOrNull { optionalBlock(modId, it) }

        private fun agedOptionalBlock(modId: String, exposedId: String, weatheredId: String, pos: BlockPos, altarCenter: BlockPos? = null): Block? =
            optionalBlock(modId, if (copperAgeBias(pos, altarCenter) == CopperAgeBias.WEATHERED) weatheredId else exposedId)

        private fun copperRoofStair(pos: BlockPos): Block =
            agedOptionalBlock("create", "create:exposed_copper_shingle_stairs", "create:weathered_copper_shingle_stairs", pos)
                ?: ageCopperBlock(Blocks.CUT_COPPER_STAIRS, pos)

        private fun copperRoofSlab(pos: BlockPos): Block =
            agedOptionalBlock("create", "create:exposed_copper_shingle_slab", "create:weathered_copper_shingle_slab", pos)
                ?: ageCopperBlock(Blocks.CUT_COPPER_SLAB, pos)

        private fun copperRoofBlock(pos: BlockPos): Block =
            agedOptionalBlock("create", "create:exposed_copper_shingles", "create:weathered_copper_shingles", pos)
                ?: ageCopperBlock(Blocks.CUT_COPPER, pos)

        private fun copperTileBlock(pos: BlockPos): Block =
            agedOptionalBlock("create", "create:exposed_copper_tiles", "create:weathered_copper_tiles", pos)
                ?: ageCopperBlock(Blocks.CUT_COPPER, pos)

        private fun copperContainmentBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_chiseled_copper", "everythingcopper:weathered_chiseled_copper", pos)
                ?: ageCopperBlock(Blocks.CUT_COPPER, pos)

        private fun copperBarsBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_bars", "everythingcopper:weathered_copper_bars", pos)
                ?: optionalBlock("create", "create:copper_bars")
                ?: Blocks.IRON_BARS

        private fun copperCauldronBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_cauldron", "everythingcopper:weathered_copper_cauldron", pos)
                ?: Blocks.CAULDRON

        private fun copperLanternBlock(pos: BlockPos, soul: Boolean): Block =
            if (soul) {
                agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_soul_lantern", "everythingcopper:weathered_copper_soul_lantern", pos)
                    ?: Blocks.SOUL_LANTERN
            } else {
                agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_lantern", "everythingcopper:weathered_copper_lantern", pos)
                    ?: Blocks.LANTERN
            }

        private fun altarSconceBlock(pos: BlockPos): Block =
            optionalBlock("supplementaries", preferredAltarSconceBlockIds())
                ?: copperLanternBlock(pos, soul = false)

        private fun copperChainBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_chain", "everythingcopper:weathered_copper_chain", pos)
                ?: Blocks.CHAIN

        private fun copperRailBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_rail", "everythingcopper:weathered_copper_rail", pos)
                ?: Blocks.RAIL

        private fun copperValveBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_button", "everythingcopper:weathered_copper_button", pos)
                ?: Blocks.LEVER

        private fun copperControlPlateBlock(pos: BlockPos): Block =
            agedOptionalBlock("everythingcopper", "everythingcopper:exposed_copper_pressure_plate", "everythingcopper:weathered_copper_pressure_plate", pos)
                ?: Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE

        private fun copperVerticalAccessBlock(): Block =
            optionalBlock("create", "create:copper_scaffolding")
                ?: Blocks.CHAIN

        private fun pathBlocks(ids: List<String>?, fallback: List<Block>): List<Block> {
            val safeFallback = fallback.filterNot(::isForbiddenPathBlock).takeIf { it.isNotEmpty() } ?: listOf(Blocks.PACKED_MUD)
            return blocks(ids, safeFallback).filterNot(::isForbiddenPathBlock).takeIf { it.isNotEmpty() } ?: safeFallback
        }

        private fun detailBlocks(ids: List<String>?, fallback: List<Block>): List<Block> {
            val safeFallback = fallback.filter(::isDisplayDetailBlock).takeIf { it.isNotEmpty() }
                ?: listOf(occultTorchBlock(), Blocks.LIME_CANDLE, Blocks.WHITE_CANDLE)
            return blocks(ids, safeFallback).filter(::isDisplayDetailBlock).takeIf { it.isNotEmpty() } ?: safeFallback
        }

        private fun trophyBlocks(ids: List<String>?): List<Block> =
            blocksOrEmpty(ids).filter(::isDisplayDetailBlock)

        private fun isDisplayDetailBlock(block: Block): Boolean {
            val path = BuiltInRegistries.BLOCK.getKey(block).path
            if (path == "dragon_head" || path == "dragon_wall_head") return false
            if (path == "dead_bush" || path == "potted_dead_bush") return false
            if (path.endsWith("_skull") || path.endsWith("_head")) return false
            if (path.contains("sculk_sensor") || path.contains("sculk_shrieker")) return false
            if (path.endsWith("_wall") || path.endsWith("_fence") || path.endsWith("_fence_gate")) return false
            if (path.endsWith("_slab") || path.endsWith("_stairs") || path.endsWith("_pillar")) return false
            if (path.endsWith("_bricks") || path.endsWith("_tiles") || path.endsWith("_planks")) return false
            if (path.endsWith("_block") || path.endsWith("_ore")) return false
            return path == "copper_bars" ||
                path == "copper_scaffolding" ||
                path == "copper_chain" ||
                path == "copper_rail" ||
                path == "copper_button" ||
                path == "copper_pressure_plate" ||
                path == "lime_candle" ||
                path == "white_candle" ||
                path == "candle" ||
                path == "lantern" ||
                path.endsWith("_lantern") ||
                path == "shard_torch" ||
                path == "shard_wall_torch" ||
                path == "iridescent_ether_torch" ||
                path == "iridescent_wall_ether_torch" ||
                path == "chorus_flower" ||
                path == "flower_pot" ||
                path == "blue_orchid" ||
                path == "allium" ||
                path.startsWith("potted_") ||
                path.endsWith("_mushroom") ||
                path.endsWith("_roots") ||
                path.endsWith("_fern") ||
                path.endsWith("_flower") ||
                path.endsWith("_sapling")
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

        private fun cultivationRowDirection(center: BlockPos, definitionId: String): Direction {
            val hash = center.x * 91815541 xor center.z * 689287499 xor definitionId.hashCode()
            return if ((hash and 1) == 0) Direction.NORTH else Direction.EAST
        }

        private fun cultivationMarker(center: BlockPos, definitionId: String, markers: List<Block>): Block {
            val hash = center.x * 19349663 xor center.y * 83492791 xor center.z * 297121507 xor definitionId.hashCode()
            return markers[Math.floorMod(hash, markers.size)]
        }

        private data class CultivationPalette(
            val pedestal: Block,
            val path: List<Block>,
            val structure: List<Block>,
            val decorations: List<Block>,
            val trophies: List<Block>,
            val walls: List<Block>,
            val marker: Block,
            val focalStructures: List<TileType>,
            val clusterStructures: List<TileType>,
            val edgeStructures: List<TileType>,
            val fieldStructures: List<TileType>
        ) {
            fun path(random: RandomSource): Block = path[random.nextInt(path.size)]
            fun structure(random: RandomSource): Block = structure[random.nextInt(structure.size)]
            fun decoration(random: RandomSource): Block = decorations[random.nextInt(decorations.size)]
            fun trophy(random: RandomSource): Block? = trophies.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
            fun wall(random: RandomSource): Block = walls[random.nextInt(walls.size)]
            fun focalStructure(random: RandomSource): TileType = focalStructures[random.nextInt(focalStructures.size)]
            fun clusterStructure(random: RandomSource): TileType = clusterStructures[random.nextInt(clusterStructures.size)]
            fun edgeStructure(random: RandomSource): TileType = edgeStructures[random.nextInt(edgeStructures.size)]
            fun fieldStructure(random: RandomSource): TileType = fieldStructures[random.nextInt(fieldStructures.size)]
            fun courtPrimaryBlock(pos: BlockPos): Block =
                structure.firstOrNull { it == Blocks.CUT_COPPER || it == Blocks.EXPOSED_CUT_COPPER || it == Blocks.WEATHERED_CUT_COPPER }
                    ?: ageCopperBlock(Blocks.CUT_COPPER, pos)
            fun courtSupportBlock(pos: BlockPos): Block =
                structure.firstOrNull { it == Blocks.COPPER_BLOCK || it == Blocks.EXPOSED_COPPER || it == Blocks.WEATHERED_COPPER }
                    ?: ageCopperBlock(Blocks.COPPER_BLOCK, pos)
            fun courtAnchorBlock(pos: BlockPos): Block =
                structure.firstOrNull { it == Blocks.RAW_COPPER_BLOCK }
                    ?: ageCopperBlock(Blocks.RAW_COPPER_BLOCK, pos)

            companion object {
                private val THEMES = listOf(
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_CUT_COPPER),
                        decorations = listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.SHRINE, TileType.TRELLIS_RUIN, TileType.BROKEN_TRELLIS, TileType.CULTIVATION_COURT),
                        clusterStructures = listOf(TileType.TRELLIS_RUIN, TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.BROKEN_TRELLIS),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN, TileType.SHRINE, TileType.SPECIMEN_DISPLAY),
                        fieldStructures = listOf(TileType.TRELLIS_RUIN, TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.CULTIVATION_DECOR)
                    ),
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.WEATHERED_CUT_COPPER),
                        decorations = listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.BROKEN_TRELLIS, TileType.CULTIVATION_COURT, TileType.TRELLIS_RUIN, TileType.SHRINE),
                        clusterStructures = listOf(TileType.BROKEN_TRELLIS, TileType.CULTIVATION_COURT, TileType.TRELLIS_RUIN, TileType.SPECIMEN_DISPLAY),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN, TileType.SPECIMEN_DISPLAY, TileType.SHRINE),
                        fieldStructures = listOf(TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN, TileType.SHRINE)
                    ),
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_COPPER),
                        decorations = listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.SHRINE, TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN),
                        clusterStructures = listOf(TileType.SHRINE, TileType.CULTIVATION_COURT, TileType.SPECIMEN_DISPLAY, TileType.TRELLIS_RUIN),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN, TileType.SHRINE, TileType.SPECIMEN_DISPLAY),
                        fieldStructures = listOf(TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN)
                    ),
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.WEATHERED_COPPER),
                        decorations = listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE, Blocks.CRIMSON_ROOTS),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.SHRINE, TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN),
                        clusterStructures = listOf(TileType.SHRINE, TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.SPECIMEN_DISPLAY),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.SHRINE, TileType.TRELLIS_RUIN, TileType.SPECIMEN_DISPLAY),
                        fieldStructures = listOf(TileType.SHRINE, TileType.CULTIVATION_COURT, TileType.TRELLIS_RUIN, TileType.CULTIVATION_DECOR)
                    ),
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_CUT_COPPER),
                        decorations = listOf(Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.SHRINE, TileType.TRELLIS_RUIN),
                        clusterStructures = listOf(TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN, TileType.SPECIMEN_DISPLAY),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.SHRINE, TileType.TRELLIS_RUIN, TileType.SPECIMEN_DISPLAY),
                        fieldStructures = listOf(TileType.CULTIVATION_COURT, TileType.BROKEN_TRELLIS, TileType.SHRINE, TileType.CULTIVATION_DECOR)
                    ),
                    CultivationTheme(
                        path = listOf(Blocks.PACKED_MUD),
                        structure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.WEATHERED_CUT_COPPER),
                        decorations = listOf(Blocks.BROWN_MUSHROOM, Blocks.WHITE_CANDLE, Blocks.LIME_CANDLE),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        markers = listOf(Blocks.COPPER_BLOCK),
                        focalStructures = listOf(TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN),
                        clusterStructures = listOf(TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.BROKEN_TRELLIS, TileType.TRELLIS_RUIN),
                        edgeStructures = listOf(TileType.BROKEN_TRELLIS, TileType.SHRINE, TileType.SPECIMEN_DISPLAY, TileType.TRELLIS_RUIN),
                        fieldStructures = listOf(TileType.CULTIVATION_COURT, TileType.SHRINE, TileType.BROKEN_TRELLIS, TileType.CULTIVATION_DECOR)
                    )
                )

                fun from(definition: ObeliskDefinition, center: BlockPos): CultivationPalette {
                    val configured = definition.cultivationPalette ?: definition.graveyardPalette
                    val trophyIds = configured?.trophyBlocks ?: definition.trophyBlocks
                    val hash = center.x * 73428767 xor center.y * 912271 xor center.z * 4235437 xor definition.id.hashCode()
                    val theme = THEMES[Math.floorMod(hash, THEMES.size)]
                    val vanillaPath = listOf(Blocks.PACKED_MUD)
                    val vanillaStructure = listOf(Blocks.RAW_COPPER_BLOCK, Blocks.COPPER_BLOCK, Blocks.CUT_COPPER, Blocks.EXPOSED_CUT_COPPER, Blocks.WEATHERED_CUT_COPPER)
                    val vanillaDecorations = listOf(occultTorchBlock(), Blocks.LIME_CANDLE, Blocks.WHITE_CANDLE)
                    val configuredPaths = pathBlocks(configured?.pathBlocks ?: definition.pathBlocks, vanillaPath)
                    val configuredStructures = blocks(configured?.structureBlocks ?: definition.structureBlocks, vanillaStructure)
                    val configuredDecorations = detailBlocks(configured?.decorations ?: definition.decorations, vanillaDecorations)
                    val configuredMarkers = blocks(
                        configured?.cultivationBlocks ?: configured?.graveBlocks ?: definition.cultivationBlocks ?: definition.graveBlocks,
                        listOf(Blocks.COPPER_BLOCK)
                    )
                    return CultivationPalette(
                        pedestal = Blocks.RAW_COPPER_BLOCK,
                        path = configuredPaths.distinct(),
                        structure = configuredStructures.distinct(),
                        decorations = configuredDecorations.distinct(),
                        trophies = trophyBlocks(trophyIds),
                        walls = listOf(Blocks.CUT_COPPER, Blocks.RAW_COPPER_BLOCK),
                        marker = configuredMarkers.first(),
                        focalStructures = theme.focalStructures,
                        clusterStructures = theme.clusterStructures,
                        edgeStructures = theme.edgeStructures,
                        fieldStructures = theme.fieldStructures
                    )
                }
            }
        }

        private data class CultivationTheme(
            val path: List<Block>,
            val structure: List<Block>,
            val decorations: List<Block>,
            val walls: List<Block>,
            val markers: List<Block>,
            val focalStructures: List<TileType>,
            val clusterStructures: List<TileType>,
            val edgeStructures: List<TileType>,
            val fieldStructures: List<TileType>
        )

        private fun occultTorchBlock(): Block =
            block("undergarden:shard_torch", Blocks.TORCH)

        private fun occultWallTorchBlock(): Block =
            block("undergarden:shard_wall_torch", Blocks.WALL_TORCH)

        private fun occultCandleBlock(random: RandomSource): Block =
            if (random.nextBoolean()) Blocks.WHITE_CANDLE else Blocks.LIME_CANDLE

        private enum class CourtZone {
            ALTAR_THRESHOLD,
            CROSSROADS,
            PERIMETER_POCKET,
            EDGE,
            CORNER,
            ENTRY_THRESHOLD
        }

        private enum class TileType {
            FONT_PEDESTAL,
            PATH,
            CULTIVATION_BED,
            EXTRACTOR_COURT,
            SEED_VAULT,
            SHRINE,
            TRELLIS_RUIN,
            IRRIGATION_GATE,
            BROKEN_TRELLIS,
            CULTIVATION_COURT,
            COMPOSTER_RUIN,
            ROOT_STOCK,
            SPECIMEN_DISPLAY,
            CULTIVATION_DECOR
        }

        private enum class TileZone {
            APPROACH_PATH,
            CULTIVATION_FIELD,
            GROWING_CLUSTER,
            FALLOW_EDGE,
            PROCESSING_RUIN,
            SPECIMEN_DISPLAY,
            OVERGROWN_BREAK
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

        private data class CourtLayout(
            val minX: Int,
            val maxX: Int,
            val minZ: Int,
            val maxZ: Int,
            val entryOffsets: Map<Direction, Set<Int>>,
            val groundByColumn: Map<Long, BlockPos> = emptyMap()
        ) {
            fun zoneFor(pos: BlockPos, altarCenter: BlockPos): CourtZone {
                val onMinX = pos.x == minX
                val onMaxX = pos.x == maxX
                val onMinZ = pos.z == minZ
                val onMaxZ = pos.z == maxZ
                if ((onMinX || onMaxX) && (onMinZ || onMaxZ)) return CourtZone.CORNER
                val edgeDirection = when {
                    onMinZ -> Direction.NORTH
                    onMaxZ -> Direction.SOUTH
                    onMinX -> Direction.WEST
                    onMaxX -> Direction.EAST
                    else -> null
                }
                if (edgeDirection != null) {
                    val offset = if (edgeDirection.axis == Direction.Axis.X) pos.z - altarCenter.z else pos.x - altarCenter.x
                    if (entryOffsets[edgeDirection]?.any { abs(it - offset) <= 1 } == true) return CourtZone.ENTRY_THRESHOLD
                    return CourtZone.EDGE
                }
                val radius = maxOf(abs(pos.x - altarCenter.x), abs(pos.z - altarCenter.z))
                if (radius <= ALTAR_RADIUS + 1) return CourtZone.ALTAR_THRESHOLD
                if (radius >= COURT_MIN_HALF_EXTENT - 1) return CourtZone.PERIMETER_POCKET
                return CourtZone.CROSSROADS
            }

            fun edgePos(direction: Direction, offset: Int, altarCenter: BlockPos): BlockPos =
                when (direction) {
                    Direction.NORTH -> BlockPos(altarCenter.x + offset, altarCenter.y, minZ)
                    Direction.SOUTH -> BlockPos(altarCenter.x + offset, altarCenter.y, maxZ)
                    Direction.WEST -> BlockPos(minX, altarCenter.y, altarCenter.z + offset)
                    Direction.EAST -> BlockPos(maxX, altarCenter.y, altarCenter.z + offset)
                    else -> altarCenter
                }
        }

        private data class BuiltSite(
            val fontPos: BlockPos,
            val maxBlood: Double,
            val placedInChunk: Boolean = false
        )

        private data class ThresholdPlacement(val pos: BlockPos, val state: BlockState)

        private data class MinimalReliquaryPathPlan(
            val pathLengths: Map<Direction, Int>
        )

        private data class PlannedPlacement(
            val pos: BlockPos,
            val state: BlockState,
            val flags: Int
        )

        private data class PlannedSite(
            val fontPos: BlockPos,
            val maxBlood: Double,
            val placements: List<PlannedPlacement>,
            val minX: Int,
            val maxX: Int,
            val minZ: Int,
            val maxZ: Int
        ) {
            fun intersects(chunk: ChunkPos): Boolean =
                minX <= chunk.maxBlockX &&
                    maxX >= chunk.minBlockX &&
                    minZ <= chunk.maxBlockZ &&
                    maxZ >= chunk.minBlockZ
        }
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
                val center = siteAltarAnchorForCell(cellX, cellZ).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
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
        val siteCenter = chunkInteriorAnchor(snapToUniversalGrid(center)).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
        val extent = SITE_MAX_BLOCK_RADIUS
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
        if (!placedFont) {
            val directSurfaceY = findSurfaceY(level, siteCenter.x, siteCenter.z, level.maxBuildHeight - 2, 1)
            val directCenter = directSurfaceY?.let { siteCenter.atY(it) }
            val directSite = directCenter?.let { buildSiteBlocks(level, level::setBlock, it, definition, RandomSource.create(seed)) }
            if (directSite != null) placedFont = placeGeneratedFont(level, directSite, definition)
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
                val center = siteAltarAnchorForCell(cellX, cellZ).atY(level.maxBuildHeight - TERRAIN_SCAN_UP - 2)
                val site = buildSite(level, center, definition, siteSeed, chunk) ?: continue
                if (isInsideChunk(site.fontPos, chunk) && placeGeneratedFont(level, site, definition)) {
                    placedFonts += site.fontPos
                }
            }
        }
        return placedFonts
    }

        private fun planSite(level: LevelAccessor, center: BlockPos, definition: ObeliskDefinition, siteSeed: Long): PlannedSite? {
            val palette = CultivationPalette.from(definition, center)
            val fullPlan = planTiles(level, center, palette, RandomSource.create(siteSeed xor SITE_LAYOUT_SALT))
            val centerGround = tileGround(level, center, TileCoord(0, 0)) ?: return null
            val altarCenter = findNearestViableAltarCenter(level, centerGround, centerGround.y + ALTAR_MAX_FOUNDATION_DROP) { true } ?: return null
            val altarSurface = altarSurfaceMap(level, altarCenter) ?: return null
            if (!canPlaceElevatedAltarAndFont(level, altarCenter, altarSurface)) return null

            val placements = mutableListOf<PlannedPlacement>()
            val plannedSetBlock = { pos: BlockPos, state: BlockState, flags: Int ->
                if (pos.y < level.minBuildHeight || pos.y >= level.maxBuildHeight) {
                    false
                } else {
                    placements += PlannedPlacement(pos.immutable(), state, flags)
                    true
                }
            }

            val pathColumns = plannedPathColumns(fullPlan)
            val detailRandom = RandomSource.create(siteSeed xor SITE_CHUNK_DETAIL_SALT)
            fullPlan.values
                .sortedWith(compareBy<TilePlan> { abs(it.coord.x) + abs(it.coord.z) }.thenBy { it.coord.x }.thenBy { it.coord.z })
                .forEach { tile ->
                    placeTile(level, plannedSetBlock, tile, fullPlan, palette, pathColumns, detailRandom)
                }
            placeIntersectionTrophies(level, plannedSetBlock, fullPlan, palette, detailRandom)
            placeBoundaryAccents(level, plannedSetBlock, fullPlan, palette, detailRandom)
            placeWetBiomeOvergrowth(level, plannedSetBlock, fullPlan.values.toList(), detailRandom)

            val courtLayout = shapeReliquaryCourt(level, plannedSetBlock, altarCenter, palette, pathColumns)
            val fontPos = placeElevatedAltar(level, plannedSetBlock, altarCenter, altarSurface, palette, courtLayout, detailRandom)
            ensureCourtTrophyDisplay(level, plannedSetBlock, altarCenter, courtLayout, palette, pathColumns, detailRandom)
            ensureReliquaryTrophyDisplay(level, plannedSetBlock, altarCenter, palette, pathColumns, detailRandom) { rough ->
                terrainGroundNear(level, rough, altarCenter.y + 5, 4)
            }
            val tileRadius = fullPlan.keys.maxOfOrNull { maxOf(abs(it.x), abs(it.z)) } ?: MIN_TILE_RADIUS
            val allPositions = placements.map { it.pos } + fontPos
            return PlannedSite(
                fontPos = fontPos,
                maxBlood = generatedCapacityForSite(definition, tileRadius, fullPlan.size),
                placements = placements,
                minX = allPositions.minOf { it.x },
                maxX = allPositions.maxOf { it.x },
                minZ = allPositions.minOf { it.z },
                maxZ = allPositions.maxOf { it.z }
            )
        }

        private fun buildSite(level: WorldGenLevel, center: BlockPos, definition: ObeliskDefinition, siteSeed: Long, chunk: ChunkPos): BuiltSite? {
            if (!siteMayIntersectChunk(center, chunk)) return null
            val surfaceY = findSurfaceY(level, center.x, center.z, level.maxBuildHeight - 2, 1) ?: return null
            val origin = center.atY(surfaceY)
            val altarCenter = findNearestViableAltarCenter(level, origin, origin.y + ALTAR_MAX_FOUNDATION_DROP) { true } ?: return null

            val palette = CultivationPalette.from(definition, center)
            var placedInChunk = false
            val chunkLocalSetBlock = { pos: BlockPos, state: BlockState, flags: Int ->
                if (isInsideChunkBounds(pos, chunk) && level.ensureCanWrite(pos)) {
                    val placed = level.setBlock(pos, state, flags)
                    placedInChunk = placedInChunk || placed
                    placed
                } else {
                    false
                }
            }

            val detailRandom = RandomSource.create(siteSeed xor SITE_CHUNK_DETAIL_SALT)
            val pathPlan = planMinimalReliquaryPaths(altarCenter, RandomSource.create(siteSeed xor SITE_CHUNK_DETAIL_SALT))
            val placesAltar = isInsideChunkBounds(altarCenter, chunk)
            var fontPos = center.above(ALTAR_HEIGHT + 1)
            val dressingCenter = if (placesAltar) {
                if (!isChunkInterior(altarCenter, ALTAR_CENTER_CHUNK_MARGIN)) return null
                val altarSurface = altarSurfaceMap(level, altarCenter) ?: return null
                if (!canPlaceElevatedAltarAndFont(level, altarCenter, altarSurface)) return null
                val allowed: (BlockPos) -> Boolean = { pos -> isInsideChunkBounds(pos, chunk) }
                val courtLayout = shapeReliquaryCourt(level, chunkLocalSetBlock, altarCenter, palette, minimalReliquaryPathColumns(altarCenter, pathPlan.pathLengths), allowed)
                fontPos = placeElevatedAltar(level, chunkLocalSetBlock, altarCenter, altarSurface, palette, courtLayout, detailRandom, allowed)
                altarCenter
            } else {
                altarCenter
            }
            placeMinimalReliquaryDressing(level, chunkLocalSetBlock, chunk, dressingCenter, palette, detailRandom, pathPlan)
            placeWetBiomeOvergrowthAround(level, chunkLocalSetBlock, chunk, dressingCenter, RELIQUARY_RADIUS, detailRandom)
            return BuiltSite(
                fontPos = fontPos,
                maxBlood = generatedCapacityForSite(definition, MIN_TILE_RADIUS, 480),
                placedInChunk = placedInChunk
            )
        }

        private fun placeStructureSiteBox(
            level: WorldGenLevel,
            center: BlockPos,
            definition: ObeliskDefinition,
            siteSeed: Long,
            box: BoundingBox,
            chunk: ChunkPos
        ): BlockPos? {
            val site = buildSite(level, center, definition, siteSeed, chunk) ?: return null
            if (isInsideChunkBounds(site.fontPos, chunk) && box.isInside(site.fontPos) && level.ensureCanWrite(site.fontPos)) {
                placeGeneratedFont(level, site, definition)
            }
            return if (site.placedInChunk || box.isInside(site.fontPos)) site.fontPos else null
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
        val enabled = ObeliskDataManager.enabledDimensionDrinks().filter { it.worldgenWeight > 0.0 }
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
