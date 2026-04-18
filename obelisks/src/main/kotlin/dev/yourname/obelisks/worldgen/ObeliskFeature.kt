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
        val family = ObeliskDataManager.getWorldgenFamily(definition.worldgenFamilyId)?.takeIf { it.enabled }
            ?: ObeliskDataManager.getWorldgenFamily("meteor")
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
        when (family.siteShape.lowercase()) {
            "spire" -> buildSpire(level, obeliskPos, random, family, materials)
            "ruin" -> buildRuin(level, obeliskPos, random, family, materials)
            else -> buildMeteor(level, obeliskPos, random, family, materials)
        }
        placePedestal(level, obeliskPos, materials, if (family.siteShape.equals("spire", ignoreCase = true)) 2 else 1)
        scatterDebris(level, obeliskPos, random, family, materials)
        clearHeadroom(level, obeliskPos)
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
                    if (random.nextDouble() > family.shellIntegrity) continue

                    val block = if (distance > radius - 0.8) materials.shell else materials.core
                    level.setBlock(obeliskPos.offset(dx, dy, dz), block.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun buildRuin(
        level: WorldGenLevel,
        obeliskPos: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        val radius = random.nextIntBetweenInclusive(family.coreRadiusMin, family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin))
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (random.nextDouble() > family.shellIntegrity) continue
                level.setBlock(obeliskPos.offset(dx, 0, dz), materials.shell.defaultBlockState(), 3)
                if (random.nextBoolean()) {
                    level.setBlock(obeliskPos.offset(dx, -1, dz), materials.core.defaultBlockState(), 3)
                }
            }
        }

        val pillarCount = random.nextIntBetweenInclusive(family.pillarCountMin, family.pillarCountMax.coerceAtLeast(family.pillarCountMin))
        repeat(pillarCount) {
            val offsetX = random.nextIntBetweenInclusive(-radius - 2, radius + 2)
            val offsetZ = random.nextIntBetweenInclusive(-radius - 2, radius + 2)
            if (offsetX == 0 && offsetZ == 0) return@repeat
            val height = random.nextIntBetweenInclusive(family.pillarHeightMin, family.pillarHeightMax.coerceAtLeast(family.pillarHeightMin))
            for (y in 0 until height) {
                val block = if (y == height - 1 || random.nextBoolean()) materials.shell else materials.core
                level.setBlock(obeliskPos.offset(offsetX, y, offsetZ), block.defaultBlockState(), 3)
            }
        }
    }

    private fun buildSpire(
        level: WorldGenLevel,
        obeliskPos: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        val height = random.nextIntBetweenInclusive(family.pillarHeightMin, family.pillarHeightMax.coerceAtLeast(family.pillarHeightMin))
        for (y in 0 until height) {
            val width = if (y < height / 2) 1 else 0
            for (dx in -width..width) {
                for (dz in -width..width) {
                    if (dx == 0 && dz == 0 && y < 2) continue
                    val block = if (dx == 0 && dz == 0) materials.core else materials.shell
                    level.setBlock(obeliskPos.offset(dx, y, dz), block.defaultBlockState(), 3)
                }
            }
        }
    }

    private fun placePedestal(level: WorldGenLevel, obeliskPos: BlockPos, materials: WorldgenMaterials, radius: Int) {
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                level.setBlock(obeliskPos.offset(dx, -1, dz), materials.pedestal.defaultBlockState(), 3)
            }
        }
    }

    private fun scatterDebris(
        level: WorldGenLevel,
        obeliskPos: BlockPos,
        random: RandomSource,
        family: WorldgenFamilyDefinition,
        materials: WorldgenMaterials
    ) {
        if (family.debrisRadius <= 0 || family.debrisChance <= 0.0) {
            return
        }
        for (dx in -family.debrisRadius..family.debrisRadius) {
            for (dz in -family.debrisRadius..family.debrisRadius) {
                if (dx == 0 && dz == 0) continue
                if (random.nextDouble() > family.debrisChance) continue
                val targetPos = obeliskPos.offset(dx, 0, dz)
                val block = if (random.nextBoolean()) materials.shell else materials.core
                level.setBlock(targetPos, block.defaultBlockState(), 3)
            }
        }
    }

    private fun clearHeadroom(level: WorldGenLevel, pos: BlockPos) {
        for (dy in 0..3) {
            level.setBlock(pos.above(dy), Blocks.AIR.defaultBlockState(), 3)
        }
    }

    private fun resolveMaterials(definition: ObeliskDefinition): WorldgenMaterials {
        val ae2Skystone = if (definition.useAe2Skystone && ModList.get().isLoaded("ae2")) {
            blockOrNull("ae2:sky_stone_block")
        } else {
            null
        }
        val shell = ae2Skystone ?: blockOrDefault(definition.meteorShellBlock, Blocks.CRYING_OBSIDIAN)
        val core = ae2Skystone ?: blockOrDefault(definition.meteorCoreBlock, Blocks.OBSIDIAN)
        val pedestal = ae2Skystone ?: blockOrDefault(definition.pedestalBlock, Blocks.OBSIDIAN)
        val craterFill = definition.craterFillBlocks.mapNotNull(::blockOrNull).ifEmpty {
            listOf(Blocks.GRAVEL, Blocks.COARSE_DIRT)
        }
        return WorldgenMaterials(core, shell, pedestal, craterFill)
    }

    private fun blockOrDefault(id: String, fallback: Block): Block = blockOrNull(id) ?: fallback

    private fun blockOrNull(id: String): Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private data class WorldgenMaterials(
        val core: Block,
        val shell: Block,
        val pedestal: Block,
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
            val family = ObeliskDataManager.getWorldgenFamily(definition.worldgenFamilyId)?.takeIf { it.enabled }
                ?: ObeliskDataManager.getWorldgenFamily("meteor")
                ?: WorldgenFamilyDefinition(id = "meteor")
            return feature.generateSite(level, surfacePos, random, definition, family)
        }
    }
}
