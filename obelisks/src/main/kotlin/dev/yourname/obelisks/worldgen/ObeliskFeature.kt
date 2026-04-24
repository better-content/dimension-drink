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
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
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
        val definition = ObeliskDataManager.pickRandomObelisk(random.asKotlinRandom()) ?: return false
        val family = meteorFamily(definition)
            ?: WorldgenFamilyDefinition(id = "meteor")
        val surfacePos = surfaceAt(level, context.origin()) ?: return false

        return generateSite(level, surfacePos, random, definition, family)
    }

    private fun generateSite(
        level: WorldGenLevel,
        surfacePos: BlockPos,
        random: RandomSource,
        definition: ObeliskDefinition,
        family: WorldgenFamilyDefinition
    ): Boolean {
        val materials = resolveMaterials(definition)
        val meteorRadius = random.nextIntBetweenInclusive(
            family.coreRadiusMin,
            family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin)
        )
        val crater = carveCrater(level, surfacePos, random, family, materials, meteorRadius)
        return buildSite(level, crater, random, definition, materials, meteorRadius)
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
                val fillBlock = craterFill.random(random.asKotlinRandom())

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
                    level.setBlock(BlockPos(columnPos.x, floorY, columnPos.z), fillBlock.defaultBlockState(), 3)
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
                    level.setBlock(BlockPos(columnPos.x, y, columnPos.z), fillBlock.defaultBlockState(), 3)
                }
            }
        }

        carveMeteorRevealShaft(level, center, centerSurfaceY, meteorTopY + 1)
        val baseObeliskY = impactY - depth + 1
        val obeliskOffsetFromMeteor = baseObeliskY - baseMeteorCenterY
        val obeliskY = (meteorCenterY + obeliskOffsetFromMeteor)
            .coerceIn(level.minBuildHeight + 1, level.maxBuildHeight - 2)
        val obeliskPos = BlockPos(center.x, obeliskY, center.z)
        val meteorCenter = BlockPos(center.x, meteorCenterY, center.z)
        return CraterProfile(
            impactY = impactY,
            radius = radius,
            depth = depth,
            obeliskPos = obeliskPos,
            meteorCenter = meteorCenter
        )
    }

    private fun buildSite(
        level: WorldGenLevel,
        crater: CraterProfile,
        random: RandomSource,
        definition: ObeliskDefinition,
        materials: WorldgenMaterials,
        meteorRadius: Int
    ): Boolean {
        buildMeteor(level, crater.meteorCenter, crater.obeliskPos, random, materials, meteorRadius)
        if (!level.setBlock(crater.obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)) {
            return false
        }
        val obelisk = level.getBlockEntity(crater.obeliskPos) as? ObeliskBlockEntity ?: return false
        obelisk.setDefinition(definition.id)
        obelisk.fillToCapacity()
        obelisk.syncToClients()
        return obelisk.definitionId == definition.id
    }

    private fun buildMeteor(
        level: WorldGenLevel,
        meteorCenter: BlockPos,
        protectedPos: BlockPos,
        random: RandomSource,
        materials: WorldgenMaterials,
        meteorRadius: Int
    ) {
        for (dx in -meteorRadius..meteorRadius) {
            for (dy in -meteorRadius..meteorRadius) {
                for (dz in -meteorRadius..meteorRadius) {
                    if (!isInsideSphere(dx, dy, dz, meteorRadius)) continue
                    val targetPos = meteorCenter.offset(dx, dy, dz)
                    if (targetPos == protectedPos) continue

                    val block = if (isShellBlock(dx, dy, dz, meteorRadius)) {
                        materials.meteorShell
                    } else {
                        materials.meteorInterior.random(random.asKotlinRandom())
                    }
                    level.setBlock(targetPos, block.defaultBlockState(), 3)
                }
            }
        }
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

    private fun carveMeteorRevealShaft(level: WorldGenLevel, center: BlockPos, fromY: Int, toY: Int) {
        val startY = min(level.maxBuildHeight - 1, fromY)
        val endY = max(level.minBuildHeight, toY)
        if (startY < endY) {
            return
        }
        for (y in startY downTo endY) {
            level.setBlock(BlockPos(center.x, y, center.z), Blocks.AIR.defaultBlockState(), 3)
        }
    }

    private fun resolveMaterials(definition: ObeliskDefinition): WorldgenMaterials {
        val skyStone = if (ModList.get().isLoaded("ae2")) {
            blockOrNull("ae2:sky_stone_block")
        } else {
            null
        }
        val meteorShell = skyStone ?: Blocks.STONE
        val flavorBlocks = (
            listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock) + definition.craterFillBlocks
            ).mapNotNull(::blockOrNull)
            .filter { it != meteorShell }
            .ifEmpty {
                listOf(Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE)
            }
        val craterFallback = (
            definition.craterFillBlocks + listOfNotNull(definition.meteorCoreBlock, definition.meteorShellBlock)
            ).mapNotNull(::blockOrNull).ifEmpty {
            listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT)
        }
        return WorldgenMaterials(meteorShell, flavorBlocks, craterFallback)
    }

    private fun blockOrNull(id: String): Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private data class WorldgenMaterials(
        val meteorShell: Block,
        val meteorInterior: List<Block>,
        val craterFallback: List<Block>
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
        val obeliskPos: BlockPos,
        val meteorCenter: BlockPos
    )

    private fun RandomSource.asKotlinRandom(): kotlin.random.Random = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = this@asKotlinRandom.nextInt() ushr (32 - bitCount)
    }

    companion object {
        private const val MAX_PRE_EXCAVATION_EXPOSED_METEOR_BLOCKS = 6
        private const val MAX_RIM_WALL_HEIGHT = 6

        fun generateDefinitionSiteForTests(
            level: WorldGenLevel,
            surfacePos: BlockPos,
            definitionId: String,
            random: RandomSource
        ): Boolean {
            val feature = ObeliskFeature(NoneFeatureConfiguration.CODEC)
            val definition = ObeliskDataManager.getObelisk(definitionId) ?: return false
            val family = feature.meteorFamily(definition)
                ?: WorldgenFamilyDefinition(id = "meteor")
            return feature.generateSite(level, surfacePos, random, definition, family)
        }
    }

    private fun meteorFamily(definition: ObeliskDefinition): WorldgenFamilyDefinition? {
        return ObeliskDataManager.getWorldgenFamily(definition.worldgenFamilyId ?: "meteor")?.takeIf { it.enabled }
    }
}
