package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.data.WorldgenFamilyDefinition
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraftforge.fml.ModList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return false
        }

        val random = context.random()
        val definitions = pickMeteorDefinitions(random) ?: return false
        val family = meteorFamily(definitions.primary)
            ?: WorldgenFamilyDefinition(id = "meteor")
        if (isIceSurface(level, context.origin())) {
            return false
        }
        val surfacePos = surfaceAt(level, context.origin()) ?: return false

        return generateSite(level, surfacePos, random, definitions, family)
    }

    private fun generateSite(
        level: WorldGenLevel,
        surfacePos: BlockPos,
        random: RandomSource,
        definitions: MeteorDefinitions,
        family: WorldgenFamilyDefinition,
        checkPlacementConflicts: Boolean = true
    ): Boolean {
        val materials = resolveMaterials(definitions.all)
        val meteorRadius = random.nextIntBetweenInclusive(
            family.coreRadiusMin,
            family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin)
        ) + METEOR_RADIUS_PAIR_BONUS
        val maxOuterRadius = family.craterRadiusMax.coerceAtLeast(family.craterRadiusMin) + max(2, family.debrisRadius)
        if (checkPlacementConflicts) {
            if (intersectsExistingStructure(level, surfacePos, maxOuterRadius)) {
                return false
            }
            if (intersectsExistingMeteor(level, surfacePos, maxOuterRadius + ANCHOR_PAIR_HALF_SPACING + 2)) {
                return false
            }
        }
        val crater = carveCrater(level, surfacePos, random, family, materials, meteorRadius)
        return buildSite(level, crater, random, definitions, materials, meteorRadius)
    }

    private fun intersectsExistingStructure(level: WorldGenLevel, center: BlockPos, radius: Int): Boolean {
        val footprint = BoundingBox(
            center.x - radius,
            level.minBuildHeight,
            center.z - radius,
            center.x + radius,
            level.maxBuildHeight - 1,
            center.z + radius
        )
        val minChunkX = (center.x - radius) shr 4
        val maxChunkX = (center.x + radius) shr 4
        val minChunkZ = (center.z - radius) shr 4
        val maxChunkZ = (center.z + radius) shr 4
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                val starts = level.getChunk(chunkX, chunkZ).allStarts.values
                if (starts.any { start -> start != null && start.isValid && start.boundingBox.intersects(footprint) }) {
                    return true
                }
            }
        }
        return false
    }

    private fun intersectsExistingMeteor(level: WorldGenLevel, center: BlockPos, radius: Int): Boolean {
        val radiusSqr = radius * radius
        for (x in (center.x - radius)..(center.x + radius)) {
            for (z in (center.z - radius)..(center.z + radius)) {
                val dx = x - center.x
                val dz = z - center.z
                if ((dx * dx) + (dz * dz) > radiusSqr) continue
                val surfaceY = topSolidY(level, BlockPos(x, center.y, z))
                val minY = max(level.minBuildHeight, surfaceY - 24)
                val maxY = min(level.maxBuildHeight - 1, surfaceY + 4)
                for (y in minY..maxY) {
                    if (level.getBlockState(BlockPos(x, y, z)).`is`(ModBlocks.OBELISK.get())) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun surfaceAt(level: WorldGenLevel, origin: BlockPos): BlockPos? {
        val y = topSolidY(level, origin) + 1
        if (y <= level.minBuildHeight + 4) return null
        return BlockPos(origin.x, y, origin.z)
    }

    private fun carveCrater(
        level: WorldGenLevel,
        center: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials,
        meteorRadius: Int
    ): CraterProfile {
        val radius = random.nextIntBetweenInclusive(family.craterRadiusMin, family.craterRadiusMax.coerceAtLeast(family.craterRadiusMin))
        val depth = random.nextIntBetweenInclusive(family.craterDepthMin, family.craterDepthMax.coerceAtLeast(family.craterDepthMin))
        val impactY = sampleImpactSurfaceY(level, center)
        val rimWidth = max(2, family.debrisRadius)
        val rimHeight = max(1, depth / 2)
        val outerRadius = radius + rimWidth
        val craterFill = sampleCraterPalette(level, center, impactY, outerRadius, materials.craterFallback)
        val craterFillCounts = craterFill.groupingBy { it }.eachCount()
        val eligibleWallBlocks = craterFillCounts
            .filterValues { it >= MIN_NEARBY_BLOCK_COUNT_FOR_WALLS }
            .keys
            .toList()
        val placedCraterBlocks = mutableMapOf<CraterColumn, MutableList<PlacedCraterBlock>>()
        val centerSurfaceY = topSolidY(level, center)
        val meteorSink = max(1, (depth * 0.5).roundToInt())
        val baseMeteorCenterY = impactY - meteorSink
        val meteorCenterY = selectBuriedMeteorCenterY(level, center, impactY, meteorRadius, meteorSink)
        val meteorTopY = meteorCenterY + meteorRadius

        for (dx in -outerRadius..outerRadius) {
            for (dz in -outerRadius..outerRadius) {
                val distance = sqrt((dx * dx + dz * dz).toDouble())
                if (distance > outerRadius) continue

                val columnPos = center.offset(dx, 0, dz)
                val localSurfaceY = topSolidY(level, columnPos)
                val fillBlock = craterFill.randomElement(random)

                if (distance <= radius) {
                    val normalized = distance / radius.toDouble()
                    val depthFactor = 1.0 - (normalized * normalized)
                    val actualDepth = (depth * depthFactor).roundToInt().coerceAtLeast(0)
                    val floorY = min(impactY - actualDepth, meteorTopY - 1)
                    if (localSurfaceY > floorY) {
                        for (y in localSurfaceY downTo (floorY + 1)) {
                            level.setBlock(BlockPos(columnPos.x, y, columnPos.z), Blocks.AIR.defaultBlockState(), 3)
                        }
                    }
                    setCraterBlock(
                        level,
                        BlockPos(columnPos.x, floorY, columnPos.z),
                        sprinkledState(fillBlock, random),
                        placedCraterBlocks
                    )
                    continue
                }

                val rimFactor = 1.0 - ((distance - radius) / rimWidth.toDouble())
                if (rimFactor <= 0.0) {
                    continue
                }
                val targetRimY = impactY + max(1, (rimHeight * rimFactor).roundToInt())
                if (targetRimY <= localSurfaceY) {
                    continue
                }
                val rimStartY = max(localSurfaceY + 1, targetRimY - MAX_RIM_WALL_HEIGHT + 1)
                for (y in rimStartY..targetRimY) {
                    val rimBlock = if (eligibleWallBlocks.isNotEmpty()) {
                        eligibleWallBlocks.randomElement(random)
                    } else {
                        fillBlock
                    }
                    val rimBaseState = if (rimBlock == Blocks.GRASS_BLOCK && y < targetRimY) {
                        Blocks.DIRT.defaultBlockState()
                    } else {
                        rimBlock.defaultBlockState()
                    }
                    val rimState = if (random.nextFloat() < GRAVEL_SPRINKLE_CHANCE) {
                        Blocks.GRAVEL.defaultBlockState()
                    } else {
                        rimBaseState
                    }
                    setCraterBlock(
                        level,
                        BlockPos(columnPos.x, y, columnPos.z),
                        rimState,
                        placedCraterBlocks
                    )
                }
            }
        }

        settleCraterBlocks(level, placedCraterBlocks)
        carveMeteorRevealShaft(level, center, centerSurfaceY, meteorTopY + 1)
        val baseObeliskY = impactY - depth + 1
        val obeliskOffsetFromMeteor = baseObeliskY - baseMeteorCenterY
        val obeliskY = (meteorCenterY + obeliskOffsetFromMeteor)
            .coerceIn(level.minBuildHeight + 1, level.maxBuildHeight - 2)
        val meteorCenter = BlockPos(center.x, meteorCenterY, center.z)
        val obeliskPositions = pairedAnchorPositions(center, obeliskY, random)
        return CraterProfile(
            impactY = impactY,
            radius = radius,
            depth = depth,
            obeliskPositions = obeliskPositions,
            meteorCenter = meteorCenter
        )
    }

    private fun buildSite(
        level: WorldGenLevel,
        crater: CraterProfile,
        random: RandomSource,
        definitions: MeteorDefinitions,
        materials: WorldgenMaterials,
        meteorRadius: Int
    ): Boolean {
        buildMeteor(level, crater.meteorCenter, crater.obeliskPositions.toSet(), random, materials, meteorRadius)
        return definitions.all.zip(crater.obeliskPositions).all { (definition, pos) ->
            if (!level.setBlock(pos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) {
                return@all false
            }
            val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return@all false
            obelisk.setDefinition(definition.id)
            obelisk.fillToCapacity()
            obelisk.syncToClients()
            obelisk.definitionId == definition.id
        }
    }

    private fun buildMeteor(
        level: WorldGenLevel,
        meteorCenter: BlockPos,
        protectedPositions: Set<BlockPos>,
        random: RandomSource,
        materials: WorldgenMaterials,
        meteorRadius: Int
    ) {
        for (dx in -meteorRadius..meteorRadius) {
            for (dy in -meteorRadius..meteorRadius) {
                for (dz in -meteorRadius..meteorRadius) {
                    if (!isInsideSphere(dx, dy, dz, meteorRadius)) continue
                    val targetPos = meteorCenter.offset(dx, dy, dz)
                    if (targetPos in protectedPositions) continue

                    val block = if (isShellBlock(dx, dy, dz, meteorRadius)) {
                        materials.meteorShell
                    } else {
                        materials.meteorInterior.randomElement(random)
                    }
                    level.setBlock(targetPos, block.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun setCraterBlock(
        level: WorldGenLevel,
        pos: BlockPos,
        state: BlockState,
        placedCraterBlocks: MutableMap<CraterColumn, MutableList<PlacedCraterBlock>>
    ) {
        level.setBlock(pos, state, 3)
        val column = CraterColumn(pos.x, pos.z)
        placedCraterBlocks.getOrPut(column) { mutableListOf() }
            .add(PlacedCraterBlock(pos.y, state))
    }

    private fun settleCraterBlocks(
        level: WorldGenLevel,
        placedCraterBlocks: Map<CraterColumn, List<PlacedCraterBlock>>
    ) {
        for ((column, blocks) in placedCraterBlocks) {
            if (blocks.isEmpty()) continue
            val sorted = blocks.sortedBy { it.y }

            for (block in sorted) {
                level.setBlock(
                    BlockPos(column.x, block.y, column.z),
                    Blocks.AIR.defaultBlockState(),
                    3
                )
            }

            val settledY = mutableSetOf<Int>()
            for (block in sorted) {
                var targetY = block.y
                while (
                    targetY > level.minBuildHeight &&
                    isColumnCellPassable(level, column.x, column.z, targetY - 1, settledY)
                ) {
                    targetY--
                }
                settledY += targetY
                level.setBlock(BlockPos(column.x, targetY, column.z), block.state, 3)
            }
        }
    }

    private fun isColumnCellPassable(
        level: WorldGenLevel,
        x: Int,
        z: Int,
        y: Int,
        settledY: Set<Int>
    ): Boolean {
        if (y in settledY) {
            return false
        }
        val pos = BlockPos(x, y, z)
        val state = level.getBlockState(pos)
        return state.isAir || isAlwaysPassThroughCraterBlock(state) || !state.isSolidRender(level, pos)
    }

    private fun isAlwaysPassThroughCraterBlock(state: BlockState): Boolean {
        return state.`is`(Blocks.GRASS) || state.`is`(Blocks.TALL_GRASS) || state.`is`(Blocks.SNOW)
    }

    private fun sampleImpactSurfaceY(level: WorldGenLevel, center: BlockPos): Int {
        val samples = mutableListOf<Int>()
        for (dx in -2..2) {
            for (dz in -2..2) {
                if ((dx * dx) + (dz * dz) > 4) continue
                samples += topSolidY(level, center.offset(dx, 0, dz))
            }
        }
        if (samples.isEmpty()) {
            return center.y - 1
        }
        return samples.sorted()[samples.size / 2]
    }

    private fun selectBuriedMeteorCenterY(
        level: WorldGenLevel,
        center: BlockPos,
        impactY: Int,
        meteorRadius: Int,
        minSink: Int
    ): Int {
        val minimumCenterY = level.minBuildHeight + meteorRadius + 1
        var maxSink = impactY - minimumCenterY
        if (maxSink < minSink) {
            maxSink = minSink
        }

        var chosenY = (impactY - minSink).coerceAtLeast(minimumCenterY)
        for (sink in minSink..maxSink) {
            val candidateY = (impactY - sink).coerceAtLeast(minimumCenterY)
            chosenY = candidateY
            val exposed = countExposedShellBlocks(level, BlockPos(center.x, candidateY, center.z), meteorRadius, MAX_PRE_EXCAVATION_EXPOSED_METEOR_BLOCKS)
            if (exposed <= MAX_PRE_EXCAVATION_EXPOSED_METEOR_BLOCKS) {
                return candidateY
            }
        }
        return chosenY
    }

    private fun countExposedShellBlocks(
        level: WorldGenLevel,
        meteorCenter: BlockPos,
        meteorRadius: Int,
        cap: Int
    ): Int {
        var exposed = 0
        for (dx in -meteorRadius..meteorRadius) {
            for (dy in -meteorRadius..meteorRadius) {
                for (dz in -meteorRadius..meteorRadius) {
                    if (!isShellBlock(dx, dy, dz, meteorRadius)) continue
                    val pos = meteorCenter.offset(dx, dy, dz)
                    if (touchesExteriorAir(level, pos, meteorCenter, meteorRadius)) {
                        exposed++
                        if (exposed > cap) {
                            return exposed
                        }
                    }
                }
            }
        }
        return exposed
    }

    private fun touchesExteriorAir(level: WorldGenLevel, pos: BlockPos, center: BlockPos, radius: Int): Boolean {
        val offsets = arrayOf(
            BlockPos(1, 0, 0),
            BlockPos(-1, 0, 0),
            BlockPos(0, 1, 0),
            BlockPos(0, -1, 0),
            BlockPos(0, 0, 1),
            BlockPos(0, 0, -1)
        )
        for (offset in offsets) {
            val neighbor = pos.offset(offset.x, offset.y, offset.z)
            if (neighbor.y < level.minBuildHeight || neighbor.y >= level.maxBuildHeight) {
                return true
            }
            val ndx = neighbor.x - center.x
            val ndy = neighbor.y - center.y
            val ndz = neighbor.z - center.z
            if (isInsideSphere(ndx, ndy, ndz, radius)) {
                continue
            }
            if (level.getBlockState(neighbor).isAir) {
                return true
            }
        }
        return false
    }

    private fun topSolidY(level: WorldGenLevel, pos: BlockPos): Int {
        val startY = min(level.maxBuildHeight - 1, pos.y + 48)
        val endY = level.minBuildHeight
        for (y in startY downTo endY) {
            val scanPos = BlockPos(pos.x, y, pos.z)
            val state = level.getBlockState(scanPos)
            if (!state.isAir && state.fluidState.isEmpty) {
                return y
            }
        }
        return pos.y - 1
    }

    private fun isIceSurface(level: WorldGenLevel, origin: BlockPos): Boolean {
        val surfaceY = topExposedY(level, origin)
        if (surfaceY < level.minBuildHeight) {
            return false
        }
        val surfacePos = BlockPos(origin.x, surfaceY, origin.z)
        return level.getBlockState(surfacePos).`is`(BlockTags.ICE)
    }

    private fun topExposedY(level: WorldGenLevel, pos: BlockPos): Int {
        val startY = min(level.maxBuildHeight - 1, pos.y + 16)
        for (y in startY downTo level.minBuildHeight) {
            val scanPos = BlockPos(pos.x, y, pos.z)
            if (!level.getBlockState(scanPos).isAir) {
                return y
            }
        }
        return level.minBuildHeight - 1
    }

    private fun carveMeteorRevealShaft(level: WorldGenLevel, center: BlockPos, fromY: Int, toY: Int) {
        val startY = min(level.maxBuildHeight - 1, fromY)
        val endY = max(level.minBuildHeight, toY)
        if (startY < endY) {
            return
        }
        for (y in startY downTo endY) {
            for (dx in -ANCHOR_PAIR_HALF_SPACING..ANCHOR_PAIR_HALF_SPACING) {
                for (dz in -ANCHOR_PAIR_HALF_SPACING..ANCHOR_PAIR_HALF_SPACING) {
                    level.setBlock(BlockPos(center.x + dx, y, center.z + dz), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun resolveMaterials(definitions: List<ObeliskDefinition>): WorldgenMaterials {
        val skyStone = if (ModList.get().isLoaded("ae2")) {
            blockOrNull("ae2:sky_stone_block")
        } else {
            null
        }
        val meteorShell = skyStone ?: Blocks.STONE
        val flavorBlocks = definitions.flatMap { definition ->
            listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock) + definition.craterFillBlocks
        }
            .mapNotNull(::blockOrNull)
            .filter { it != meteorShell }
            .distinct()
            .ifEmpty {
                listOf(Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE)
            }
        val craterFallback = definitions.flatMap { definition ->
            definition.craterFillBlocks + listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock)
        }
            .mapNotNull(::blockOrNull)
            .distinct()
            .ifEmpty {
                listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT)
            }
        return WorldgenMaterials(meteorShell, flavorBlocks, craterFallback)
    }

    private fun pickMeteorDefinitions(random: RandomSource): MeteorDefinitions? {
        val primary = pickWeighted(ObeliskDataManager.enabledObelisks().filter { it.worldgenWeight > 0.0 }, random) ?: return null
        val candidates = ObeliskDataManager.enabledObelisks().filter {
            it.worldgenWeight > 0.0 &&
                it.id != primary.id &&
                it.targetDimension != primary.targetDimension
        }
        val secondary = pickWeighted(candidates, random) ?: return null
        return MeteorDefinitions(primary, secondary)
    }

    private fun pickWeighted(definitions: List<ObeliskDefinition>, random: RandomSource): ObeliskDefinition? {
        val totalWeight = definitions.sumOf { it.worldgenWeight }
        if (totalWeight <= 0.0) return null
        var cursor = random.nextDouble() * totalWeight
        for (definition in definitions) {
            cursor -= definition.worldgenWeight
            if (cursor <= 0.0) {
                return definition
            }
        }
        return definitions.lastOrNull()
    }

    private fun pairedAnchorPositions(center: BlockPos, y: Int, random: RandomSource): List<BlockPos> {
        return if (random.nextBoolean()) {
            listOf(
                BlockPos(center.x - ANCHOR_PAIR_HALF_SPACING, y, center.z),
                BlockPos(center.x + ANCHOR_PAIR_HALF_SPACING, y, center.z)
            )
        } else {
            listOf(
                BlockPos(center.x, y, center.z - ANCHOR_PAIR_HALF_SPACING),
                BlockPos(center.x, y, center.z + ANCHOR_PAIR_HALF_SPACING)
            )
        }
    }

    private fun blockOrNull(id: String): Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private fun sprinkledState(base: Block, random: RandomSource): BlockState {
        return if (random.nextFloat() < GRAVEL_SPRINKLE_CHANCE) {
            Blocks.GRAVEL.defaultBlockState()
        } else {
            base.defaultBlockState()
        }
    }

    private data class WorldgenMaterials(
        val meteorShell: Block,
        val meteorInterior: List<Block>,
        val craterFallback: List<Block>
    )

    private data class PlacedCraterBlock(
        val y: Int,
        val state: BlockState
    )

    private data class CraterColumn(
        val x: Int,
        val z: Int
    )

    private fun isInsideSphere(dx: Int, dy: Int, dz: Int, radius: Int): Boolean {
        return (dx * dx + dy * dy + dz * dz).toDouble() <= radius.toDouble() * radius.toDouble()
    }

    private fun isShellBlock(dx: Int, dy: Int, dz: Int, radius: Int): Boolean {
        if (!isInsideSphere(dx, dy, dz, radius)) return false
        val neighbors = arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(0, 0, -1)
        )
        for (neighbor in neighbors) {
            if (!isInsideSphere(dx + neighbor[0], dy + neighbor[1], dz + neighbor[2], radius)) {
                return true
            }
        }
        return false
    }

    private fun sampleCraterPalette(
        level: WorldGenLevel,
        center: BlockPos,
        impactY: Int,
        outerRadius: Int,
        fallback: List<Block>
    ): List<Block> {
        val sampled = mutableListOf<Block>()
        val sampleRadius = outerRadius + 4
        for (dx in -sampleRadius..sampleRadius step 2) {
            for (dz in -sampleRadius..sampleRadius step 2) {
                if ((dx * dx) + (dz * dz) > sampleRadius * sampleRadius) continue
                val x = center.x + dx
                val z = center.z + dz
                var y = topSolidY(level, BlockPos(x, impactY, z))
                while (y >= level.minBuildHeight) {
                    val pos = BlockPos(x, y, z)
                    val state = level.getBlockState(pos)
                    if (!state.isAir && state.fluidState.isEmpty && !state.`is`(Blocks.BEDROCK) && state.isSolidRender(level, pos)) {
                        sampled += state.block
                        break
                    }
                    y--
                }
            }
        }
        return sampled.ifEmpty { fallback }
    }

    private data class CraterProfile(
        val impactY: Int,
        val radius: Int,
        val depth: Int,
        val obeliskPositions: List<BlockPos>,
        val meteorCenter: BlockPos
    )

    private data class MeteorDefinitions(
        val primary: ObeliskDefinition,
        val secondary: ObeliskDefinition
    ) {
        val all: List<ObeliskDefinition> = listOf(primary, secondary)
    }

    private fun <T> List<T>.randomElement(random: RandomSource): T {
        return this[random.nextInt(size)]
    }

    companion object {
        private const val MAX_PRE_EXCAVATION_EXPOSED_METEOR_BLOCKS = 6
        private const val MAX_RIM_WALL_HEIGHT = 6
        private const val METEOR_RADIUS_PAIR_BONUS = 1
        private const val ANCHOR_PAIR_HALF_SPACING = 1
        private const val GRAVEL_SPRINKLE_CHANCE = 0.14f
        private const val MIN_NEARBY_BLOCK_COUNT_FOR_WALLS = 6

        fun generateDefinitionSiteForTests(
            level: WorldGenLevel,
            surfacePos: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val secondary = ObeliskDataManager.enabledObelisks().firstOrNull {
                it.id != definition.id && it.targetDimension != definition.targetDimension
            } ?: return false
            val family = feature.meteorFamily(definition)
                ?: WorldgenFamilyDefinition(id = "meteor")
            return feature.generateSite(
                level,
                surfacePos,
                random,
                MeteorDefinitions(definition, secondary),
                family,
                checkPlacementConflicts = false
            )
        }
    }

    private fun meteorFamily(definition: ObeliskDefinition): WorldgenFamilyDefinition? {
        return ObeliskDataManager.getWorldgenFamily(definition.worldgenFamilyId ?: "meteor")?.takeIf { it.enabled }
    }
}
