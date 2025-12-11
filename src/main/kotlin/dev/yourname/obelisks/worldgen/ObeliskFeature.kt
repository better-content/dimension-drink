package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.config.ObeliskTypeRegistry
import dev.yourname.obelisks.dimension.DimensionBaseType
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
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

        // Find ground level at this position
        val groundPos = findGroundPosition(level, pos) ?: return false

        // Check if ground is water - don't spawn
        val groundBlock = level.getBlockState(groundPos.below())
        if (!groundBlock.fluidState.isEmpty) {
            return false
        }

        // Choose random dimension type
        val baseType = DimensionBaseType.values()[random.nextInt(DimensionBaseType.values().size)]
        val config = ObeliskTypeRegistry.getConfig(baseType)

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

        // Note: Block entity will be created when chunk loads, but baseType won't be set
        // It will be assigned when a player first activates it

        return true
    }

    /**
     * Finds the ground position by looking downward from the given position.
     * Returns null if no solid ground is found within reasonable range.
     */
    private fun findGroundPosition(level: WorldGenLevel, startPos: BlockPos): BlockPos? {
        var currentPos = startPos

        // Look down to find ground
        for (i in 0..32) {
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
