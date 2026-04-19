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
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import net.minecraftforge.fml.ModList
import kotlin.math.sqrt

class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        if (level.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return false
        }

        val random = context.random()
        val definition = ObeliskDataManager.pickRandomObelisk(random.asKotlinRandom()) ?: return false
        val family = meteorFamily()
            ?: WorldgenFamilyDefinition(id = "meteor")
        val surfacePos = surfaceAt(level, context.origin()) ?: return false
        if (!isValidSurface(level, surfacePos)) {
            return false
        }

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
        carveCrater(level, surfacePos, random, family, materials.craterFill)
        buildSite(level, surfacePos, random, definition, family, materials)
        return true
    }

    private fun surfaceAt(level: WorldGenLevel, origin: BlockPos): BlockPos? {
        val y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, origin.x, origin.z)
        if (y <= level.minBuildHeight + 4) return null
        return BlockPos(origin.x, y, origin.z)
    }

    private fun isValidSurface(level: WorldGenLevel, pos: BlockPos): Boolean {
        val ground = level.getBlockState(pos.below())
        if (ground.isAir || !ground.fluidState.isEmpty) return false
        for (dy in 0..5) {
            if (!level.getBlockState(pos.above(dy)).fluidState.isEmpty) return false
        }

        var validNeighbors = 0
        var checked = 0
        for (dx in -2..2) {
            for (dz in -2..2) {
                if (dx == 0 && dz == 0) continue
                checked++
                val neighborY = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, pos.x + dx, pos.z + dz)
                if (kotlin.math.abs(neighborY - pos.y) <= 4) {
                    validNeighbors++
                }
            }
        }
        return validNeighbors >= (checked * 0.6)
    }

    private fun carveCrater(
        level: WorldGenLevel,
        center: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        craterFill: List<Block>
    ) {
        val radius = random.nextIntBetweenInclusive(family.craterRadiusMin, family.craterRadiusMax.coerceAtLeast(family.craterRadiusMin))
        val depth = random.nextIntBetweenInclusive(family.craterDepthMin, family.craterDepthMax.coerceAtLeast(family.craterDepthMin))
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val distance = sqrt((dx * dx + dz * dz).toDouble())
                if (distance > radius) continue

                val depthFactor = 1.0 - (distance / radius.toDouble())
                val actualDepth = (depth * depthFactor).toInt().coerceAtLeast(1)
                for (dy in 0 until actualDepth) {
                    level.setBlock(center.offset(dx, -dy, dz), Blocks.AIR.defaultBlockState(), 3)
                }
                val fillBlock = craterFill.random(random.asKotlinRandom())
                level.setBlock(center.offset(dx, -actualDepth, dz), fillBlock.defaultBlockState(), 3)
            }
        }
    }

    private fun buildSite(
        level: WorldGenLevel,
        center: BlockPos,
        random: RandomSource,
        definition: ObeliskDefinition,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        val obeliskPos = center.below()
        buildMeteor(level, obeliskPos, random, family, materials)
        level.setBlock(obeliskPos, ModBlocks.OBELISK.get().defaultBlockState(), 3)
        val obelisk = level.getBlockEntity(obeliskPos) as? ObeliskBlockEntity ?: return
        obelisk.setDefinition(definition.id)
        obelisk.fillToCapacity()
        obelisk.syncToClients()
    }

    private fun buildMeteor(
        level: WorldGenLevel,
        obeliskPos: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        val radius = random.nextIntBetweenInclusive(family.coreRadiusMin, family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin))
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    if (distance > radius) continue
                    if (dx == 0 && dy == 0 && dz == 0) continue

                    level.setBlock(obeliskPos.offset(dx, dy, dz), materials.meteor.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun resolveMaterials(definition: ObeliskDefinition): WorldgenMaterials {
        val ae2Skystone = if (ModList.get().isLoaded("ae2")) {
            blockOrNull("ae2:sky_stone_block")
        } else {
            null
        }
        val meteor = ae2Skystone ?: Blocks.STONE
        val craterFill = definition.craterFillBlocks.mapNotNull(::blockOrNull).ifEmpty {
            listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT)
        }
        return WorldgenMaterials(meteor, craterFill)
    }

    private fun blockOrNull(id: String): Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private data class WorldgenMaterials(
        val meteor: Block,
        val craterFill: List<Block>
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
            val family = feature.meteorFamily()
                ?: WorldgenFamilyDefinition(id = "meteor")
            return feature.generateSite(level, surfacePos, random, definition, family)
        }
    }

    private fun meteorFamily(): WorldgenFamilyDefinition? {
        return ObeliskDataManager.getWorldgenFamily("meteor")?.takeIf { it.enabled }
    }
}
