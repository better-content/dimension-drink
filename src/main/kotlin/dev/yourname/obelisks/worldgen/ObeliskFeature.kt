package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ConfigManager
import dev.yourname.obelisks.config.DimensionConfig
import dev.yourname.obelisks.config.ObeliskTypeRegistry
import dev.yourname.obelisks.dimension.DimensionBaseType
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration

/**
 * Custom feature that places obelisks with proper pillars during worldgen.
 */
class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val pos = context.origin()
        val random = context.random()

        // Don't spawn obelisks in run dimensions (run_slot_X)
        val dimensionLocation = level.level.dimension().location()
        if (dimensionLocation.namespace == "obelisks" && dimensionLocation.path.startsWith("run_slot_")) {
            return false
        }

        // Find ground level at this position
        val groundPos = findGroundPosition(level, pos) ?: return false

        // Check if ground is water - don't spawn
        val groundBlock = level.getBlockState(groundPos.below())
        if (!groundBlock.fluidState.isEmpty) {
            return false
        }

        // Choose random obelisk config with weighted rarity
        val config = ObeliskTypeRegistry.getRandomWeightedConfig(random) ?: return false

        // Build stem UPWARD from ground (same as ObeliskPlacer)
        // Stem goes from ground level to (ground + pillarHeight)
        for (i in 0 until config.pillarHeight) {
            val stemPos = groundPos.above(i)
            level.setBlock(stemPos, config.pillarBlock.defaultBlockState(), 3)
        }

        // Place obelisk cap on TOP of stem
        val capPos = groundPos.above(config.pillarHeight)
        val obeliskState = ModBlocks.OBELISK.get().defaultBlockState()
        level.setBlock(capPos, obeliskState, 3)

        // Configure the block entity with the chosen dimension
        // Note: Block entity still uses baseType temporarily, but config comes from JSON
        val blockEntity = level.getBlockEntity(capPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
        if (blockEntity != null) {
            // Map dimension ID back to baseType for now (until block entity is refactored)
            blockEntity.baseType = when (config.dimensionConfig.dimensionId) {
                "minecraft:the_nether" -> DimensionBaseType.NETHER
                "minecraft:the_end" -> DimensionBaseType.END
                "minecraft:overworld" -> DimensionBaseType.OVERWORLD
                else -> null
            }
            blockEntity.setChanged()
        }

        // Scatter flavor blocks around the obelisk for decoration
        scatterFlavorBlocks(level, groundPos, config.dimensionConfig, random)

        return true
    }

    /**
     * Scatters flavor blocks around the obelisk base for decoration.
     * Uses dimension-specific blocks from config.
     */
    private fun scatterFlavorBlocks(
        level: WorldGenLevel,
        centerPos: BlockPos,
        dimConfig: DimensionConfig,
        random: RandomSource
    ) {
        // Get flavor blocks for this dimension type
        val flavorBlocks = dimConfig.flavorBlocks ?: emptyList()

        if (flavorBlocks.isEmpty()) {
            return // No flavor blocks configured
        }

        // Scatter 8-15 flavor blocks in a radius around the obelisk
        val blockCount = 8 + random.nextInt(8)
        val radius = 5

        for (i in 0 until blockCount) {
            // Random offset from center
            val xOffset = random.nextInt(radius * 2 + 1) - radius
            val zOffset = random.nextInt(radius * 2 + 1) - radius

            // Skip if too close to obelisk (within 2 blocks)
            if (Math.abs(xOffset) <= 1 && Math.abs(zOffset) <= 1) continue

            // Place flavor block one block BELOW ground level (replaces what was there)
            val targetPos = centerPos.offset(xOffset, -1, zOffset)

            // Pick random flavor block
            val flavorBlockId = flavorBlocks[random.nextInt(flavorBlocks.size)]
            val block = BuiltInRegistries.BLOCK.get(ResourceLocation(flavorBlockId))

            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                // Replace whatever was there
                level.setBlock(targetPos, block.defaultBlockState(), 3)
            }
        }
    }

    /**
     * Finds the ground position by looking downward from the given position.
     * Returns null if no solid ground is found within reasonable range.
     */
    private fun findGroundPosition(level: WorldGenLevel, startPos: BlockPos): BlockPos? {
        var currentPos = startPos

        // Look down to find ground
        for (i in 0..ObelisksConstants.GROUND_SEARCH_DEPTH_WORLDGEN) {
            val blockBelow = level.getBlockState(currentPos.below())
            if (blockBelow.isSolidRender(level, currentPos.below())) {
                // Found solid ground
                return currentPos
            }
            currentPos = currentPos.below()
        }

        return null
    }
}
