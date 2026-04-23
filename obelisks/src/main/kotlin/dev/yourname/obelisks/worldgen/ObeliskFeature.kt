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
        val crater = carveCrater(level, surfacePos, random, family, materials.craterFill)
        return buildSite(level, crater, random, definition, family, materials)
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
        craterFill: List<Block>
    ): CraterProfile {
        val radius = random.nextIntBetweenInclusive(family.craterRadiusMin, family.craterRadiusMax.coerceAtLeast(family.craterRadiusMin))
        val depth = random.nextIntBetweenInclusive(family.craterDepthMin, family.craterDepthMax.coerceAtLeast(family.craterDepthMin))
        val impactY = sampleImpactSurfaceY(level, center)
        val rimWidth = max(2, family.debrisRadius)
        val rimHeight = max(1, depth / 2)
        val outerRadius = radius + rimWidth

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
                    val floorY = impactY - actualDepth
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
                for (y in (localSurfaceY + 1)..targetRimY) {
                    level.setBlock(BlockPos(columnPos.x, y, columnPos.z), fillBlock.defaultBlockState(), 3)
                }
            }
        }

        val meteorSink = max(1, (depth * 0.5).roundToInt())
        val obeliskPos = BlockPos(center.x, impactY - depth + 1, center.z)
        val meteorCenter = BlockPos(center.x, impactY - meteorSink, center.z)
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
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ): Boolean {
        buildMeteor(level, crater.meteorCenter, crater.obeliskPos, random, family, materials)
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
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        val radius = random.nextIntBetweenInclusive(family.coreRadiusMin, family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin))
        val coreThreshold = radius * family.shellIntegrity
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    if (distance > radius) continue
                    val targetPos = meteorCenter.offset(dx, dy, dz)
                    if (targetPos == protectedPos) continue

                    val block = if (distance <= coreThreshold) materials.meteorCore else materials.meteorShell
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

    private fun resolveMaterials(definition: ObeliskDefinition): WorldgenMaterials {
        val configuredMeteor = definition.meteorCoreBlock?.let(::blockOrNull)
        val ae2Skystone = if (definition.useAe2Skystone && ModList.get().isLoaded("ae2")) {
            blockOrNull("ae2:sky_stone_block")
        } else {
            null
        }
        val meteorCore = configuredMeteor ?: ae2Skystone ?: Blocks.STONE
        val meteorShell = definition.meteorShellBlock?.let(::blockOrNull) ?: meteorCore
        val craterFill = definition.craterFillBlocks.mapNotNull(::blockOrNull).ifEmpty {
            listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT)
        }
        return WorldgenMaterials(meteorCore, meteorShell, craterFill)
    }

    private fun blockOrNull(id: String): Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private data class WorldgenMaterials(
        val meteorCore: Block,
        val meteorShell: Block,
        val craterFill: List<Block>
    )

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
