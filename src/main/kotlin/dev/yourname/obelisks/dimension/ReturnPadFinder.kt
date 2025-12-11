package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.content.ReturnPadBlock
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.random.Random

/**
 * Finds valid return pad locations in a dimension.
 * 
 * Scanning strategy:
 * - Iterates along Y axis first (vertical layers)
 * - Within each Y layer, strongly randomizes XZ coordinates
 * - Looks for valid return pad placement (3x3 obsidian ring + 3x3x3 air cube above)
 */
object ReturnPadFinder {

    /**
     * Finds a valid return pad location near a spawn position.
     * 
     * @param level The dimension to search in
     * @param centerPos The center position to search around
     * @param searchRadius Horizontal search radius (default: 64 blocks)
     * @param yMin Minimum Y level to search (default: 0)
     * @param yMax Maximum Y level to search (default: 128)
     * @return Valid BlockPos for return pad placement, or null if none found
     */
    fun findValidLocation(
        level: ServerLevel,
        centerPos: BlockPos,
        searchRadius: Int = 64,
        yMin: Int = 0,
        yMax: Int = 128
    ): BlockPos? {
        // Generate randomized XZ offsets for each Y layer
        // This ensures we iterate Y first, but XZ is strongly randomized
        val xzOffsets = generateRandomizedXZOffsets(searchRadius)

        // Iterate through Y layers
        for (y in yMin..yMax) {
            // For each Y level, try randomized XZ positions
            for ((dx, dz) in xzOffsets) {
                val testPos = BlockPos(centerPos.x + dx, y, centerPos.z + dz)

                // Check if this position is valid for return pad placement
                if (isValidReturnPadLocation(level, testPos)) {
                    return testPos
                }
            }
        }

        // No valid location found
        return null
    }

    /**
     * Checks if a position is valid for return pad placement.
     * Must have solid ground and meet return pad structure requirements.
     */
    private fun isValidReturnPadLocation(level: ServerLevel, pos: BlockPos): Boolean {
        // Check if position is loaded
        if (!level.isLoaded(pos)) return false

        // Check if block below is solid (ground)
        val blockBelow = level.getBlockState(pos.below())
        if (!blockBelow.isSolidRender(level, pos.below())) {
            return false
        }

        // Check if position itself is replaceable (air or similar)
        val currentBlock = level.getBlockState(pos)
        if (!currentBlock.isAir && !currentBlock.canBeReplaced()) {
            return false
        }

        // Check if obsidian ring exists
        if (!ReturnPadBlock.hasValidObsidianRing(level, pos)) {
            return false
        }

        // Check if air cube exists above
        if (!ReturnPadBlock.hasValidAirCube(level, pos)) {
            return false
        }

        return true
    }

    /**
     * Generates a list of randomized XZ offset pairs within a given radius.
     * This ensures strong XZ randomization while maintaining Y-first iteration.
     * 
     * @param radius Search radius in blocks
     * @return List of (x, z) offset pairs, shuffled randomly
     */
    private fun generateRandomizedXZOffsets(radius: Int): List<Pair<Int, Int>> {
        val offsets = mutableListOf<Pair<Int, Int>>()

        // Generate all XZ positions within circular radius
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                // Only include positions within circular radius
                val distanceSquared = x * x + z * z
                if (distanceSquared <= radius * radius) {
                    offsets.add(Pair(x, z))
                }
            }
        }

        // Strongly randomize the XZ positions
        offsets.shuffle(Random.Default)

        return offsets
    }

    /**
     * Attempts to place a return pad at a found location.
     * Also places the obsidian ring and clears air space if needed.
     * 
     * @param level The dimension to place in
     * @param pos The position to place at
     * @param buildStructure If true, creates the obsidian ring and clears air space
     * @return True if successfully placed, false otherwise
     */
    fun placeReturnPad(
        level: ServerLevel,
        pos: BlockPos,
        buildStructure: Boolean = true
    ): Boolean {
        if (buildStructure) {
            // Build obsidian ring
            for (x in -1..1) {
                for (z in -1..1) {
                    // Skip center
                    if (x == 0 && z == 0) continue

                    val ringPos = pos.offset(x, 0, z)
                    level.setBlock(ringPos, net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState(), 3)
                }
            }

            // Clear air cube above
            for (y in 1..3) {
                for (x in -1..1) {
                    for (z in -1..1) {
                        val airPos = pos.offset(x, y, z)
                        level.setBlock(airPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
                    }
                }
            }
        }

        // Place return pad
        val returnPadState = ModBlocks.RETURN_PAD.get().defaultBlockState()
        return level.setBlock(pos, returnPadState, 3)
    }

    /**
     * Finds and places a return pad near the spawn position.
     * Convenience method that combines finding and placing.
     * 
     * @param level The dimension to place in
     * @param spawnPos The spawn position to search near
     * @param searchRadius Search radius (default: 64)
     * @param buildStructure Whether to build the obsidian ring (default: true)
     * @return The position where the return pad was placed, or null if failed
     */
    fun findAndPlaceReturnPad(
        level: ServerLevel,
        spawnPos: BlockPos,
        searchRadius: Int = 64,
        buildStructure: Boolean = true
    ): BlockPos? {
        val location = findValidLocation(level, spawnPos, searchRadius)
        
        if (location != null) {
            val success = placeReturnPad(level, location, buildStructure)
            return if (success) location else null
        }

        return null
    }
}
