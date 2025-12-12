package dev.yourname.obelisks.util

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ObeliskTypeRegistry
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.dimension.DimensionBaseType
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level

/**
 * Utility for placing obelisks with proper support pillars and configuration.
 * 
 * Obelisk structure:
 * - Obelisk cap (the interactive block) at the TOP
 * - Stem/pillar blocks building DOWN from cap to ground
 * - Bottom of stem sits ON the ground
 */
object ObeliskPlacer {

    /**
     * Places an obelisk at the given position with appropriate pillar support.
     * The obelisk is automatically attuned to a random dimension type.
     * 
     * @param level The world to place in
     * @param pos The position to place at (will find ground below this)
     * @return True if placement succeeded
     */
    fun placeObelisk(level: Level, pos: BlockPos): Boolean {
        // Choose random dimension type
        val baseType = DimensionBaseType.random()

        return placeObelisk(level, pos, baseType)
    }

    /**
     * Places an obelisk at the given position with appropriate pillar support.
     * The obelisk is attuned to the specified dimension type.
     *
     * Structure:
     * 1. Finds ground below the given position
     * 2. Builds stem upward from ground
     * 3. Places obelisk cap on top of stem
     *
     * @param level The world to place in
     * @param pos The position to place at (will find ground below this)
     * @param baseType The dimension type to attune to
     * @return True if placement succeeded
     */
    fun placeObelisk(level: Level, pos: BlockPos, baseType: DimensionBaseType): Boolean {
        val dimConfig = dev.yourname.obelisks.config.ConfigManager.getConfigForBaseType(baseType) ?: return false
        val config = ObeliskTypeRegistry.getConfigForDimension(dimConfig)

        // Find ground below the given position
        val groundPos = findGroundBelow(level, pos) ?: return false

        // Build stem UPWARD from ground
        // Stem goes from ground level to (ground + pillarHeight)
        for (i in 0 until config.pillarHeight) {
            val stemPos = groundPos.above(i)
            level.setBlock(stemPos, config.pillarBlock.defaultBlockState(), 3)
        }

        // Place obelisk cap on TOP of stem
        val capPos = groundPos.above(config.pillarHeight)
        val obeliskState = ModBlocks.OBELISK.get().defaultBlockState()
        level.setBlock(capPos, obeliskState, 3)

        // Configure the block entity
        val blockEntity = level.getBlockEntity(capPos) as? ObeliskBlockEntity
        if (blockEntity != null) {
            blockEntity.baseType = baseType
            blockEntity.setChanged()
            return true
        }

        return false
    }

    /**
     * Finds the ground position by searching downward from the given position.
     * Returns the first air block above solid ground.
     * 
     * @param level The world to search in
     * @param startPos The position to start searching from
     * @return The ground position (air block on top of solid), or null if not found
     */
    private fun findGroundBelow(level: Level, startPos: BlockPos): BlockPos? {
        var currentPos = startPos

        // Search down up to configured depth
        for (i in 0..ObelisksConstants.GROUND_SEARCH_DEPTH_PLACER) {
            val blockAtPos = level.getBlockState(currentPos)
            val blockBelow = level.getBlockState(currentPos.below())

            // Found ground: current position is air (or replaceable), block below is solid
            if ((blockAtPos.isAir || blockAtPos.canBeReplaced()) &&
                blockBelow.isSolidRender(level, currentPos.below()) &&
                blockBelow.fluidState.isEmpty) {
                return currentPos
            }

            currentPos = currentPos.below()
        }

        // No ground found within range
        return null
    }
}
