package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks

/**
 * Generates safe spawn platforms in run dimensions.
 */
object SpawnPlatformGenerator {

    /**
     * Generates a spawn platform at a suitable location in the dimension.
     * Returns the center spawn position (where players should teleport).
     *
     * Strategy: Search for location where platform mostly touches target block type.
     */
    fun generateSpawnPlatform(level: ServerLevel, dimensionId: String, targetPos: BlockPos? = null): BlockPos {
        val searchCenter = targetPos ?: BlockPos(0, ObelisksConstants.PLATFORM_Y_LEVEL, 0)

        // Find a suitable location: open air space
        val platformPos = findSuitableLocation(level, searchCenter)
            ?: run {
                // Fallback: just build at a reasonable height
                BlockPos(searchCenter.x, 70, searchCenter.z)
            }


        // Build the platform at the found position
        buildPlatform(level, platformPos)

        // Post-build validation
        if (!validatePlatform(level, platformPos)) {
            buildPlatform(level, platformPos)

            if (!validatePlatform(level, platformPos)) {
            }
        }

        // Log platform generation for debugging
        
        // CRITICAL DEBUG: Verify blocks are actually there immediately after placement
        val returnPadPos = platformPos.above()
        val returnPadBlock = level.getBlockState(returnPadPos).block
        val platformBlock = level.getBlockState(platformPos).block

        // CRITICAL: Mark chunks as dirty to ensure they're saved and synced to clients
        val chunkX = returnPadPos.x shr 4
        val chunkZ = returnPadPos.z shr 4

        val radius = ObelisksConstants.PLATFORM_CHUNK_LOAD_RADIUS
        for (cx in -radius..radius) {
            for (cz in -radius..radius) {
                val chunk = level.getChunk(chunkX + cx, chunkZ + cz)
                chunk.setUnsaved(true) // Mark chunk as needing to be saved/synced
            }
        }


        return returnPadPos.above() // Players spawn on top of the return pad (Y+2 above platform ground)
    }

    /**
     * Builds a safe flat obsidian bunker with a return pad in the center.
     * Creates a 7x7x4 enclosed bunker with 2x1 doorways on N/S/E/W sides.
     *
     * @param center The ground-level position (Y=ground) where bunker will be built
     */
    private fun buildPlatform(level: ServerLevel, center: BlockPos) {
        val radius = ObelisksConstants.PLATFORM_RADIUS // 3 (creates 7x7)

        // PHASE 1: Build flat 7x7 bedrock floor (to resist dimension decay)
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val floorPos = center.offset(x, 0, z)
                level.setBlock(floorPos, Blocks.BEDROCK.defaultBlockState(), 11)
            }
        }

        // PHASE 2: Build 7x7x4 obsidian walls (hollow interior, no corners)
        for (y in 1..4) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val pos = center.offset(x, y, z)

                    // Check if this is a corner position (remove corners for octagonal shape)
                    val isCorner = (x == -radius || x == radius) && (z == -radius || z == radius)

                    // Check if this is a wall position (edge of 7x7)
                    val isWall = x == -radius || x == radius || z == -radius || z == radius

                    if (isCorner) {
                        // Remove corners - leave as air
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                    } else if (isWall) {
                        // Check for doorway positions (2x1 on each cardinal direction)
                        val isDoorway =
                            // North doorway (z = -radius, x = 0, y = 1-2)
                            (z == -radius && x == 0 && y in 1..2) ||
                            // South doorway (z = radius, x = 0, y = 1-2)
                            (z == radius && x == 0 && y in 1..2) ||
                            // East doorway (x = radius, z = 0, y = 1-2)
                            (x == radius && z == 0 && y in 1..2) ||
                            // West doorway (x = -radius, z = 0, y = 1-2)
                            (x == -radius && z == 0 && y in 1..2)

                        if (isDoorway) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                        } else {
                            level.setBlock(pos, Blocks.OBSIDIAN.defaultBlockState(), 3)
                        }
                    } else {
                        // Interior - fill with air
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
                    }
                }
            }
        }

        // PHASE 3: Build flat obsidian roof (no corners for octagonal shape)
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val roofPos = center.offset(x, 5, z)

                // Check if this is a corner position
                val isCorner = (x == -radius || x == radius) && (z == -radius || z == radius)

                if (!isCorner) {
                    level.setBlock(roofPos, Blocks.OBSIDIAN.defaultBlockState(), 11)
                }
            }
        }

        // PHASE 4: Place return pad at center, Y+1 (on the floor)
        val returnPadPos = center.above(1)
        level.setBlock(returnPadPos, dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get().defaultBlockState(), 11)
    }

    /**
     * Validates that a platform was built correctly.
     * Checks critical structures: platform floor, return pad, obsidian ring, air cube.
     *
     * @param platformGroundPos The ground-level position where platform was built
     * @return True if platform is valid and safe, false otherwise
     */
    private fun validatePlatform(level: ServerLevel, platformGroundPos: BlockPos): Boolean {
        val returnPadPos = platformGroundPos.above()

        // Check 1: Return pad exists
        val returnPadBlock = level.getBlockState(returnPadPos).block
        if (returnPadBlock != dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get()) {
            return false
        }

        // Check 2: Bedrock ring exists (8 blocks around return pad)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue // Skip center

                val ringPos = returnPadPos.offset(dx, 0, dz)
                val ringBlock = level.getBlockState(ringPos).block

                if (ringBlock != Blocks.BEDROCK) {
                    return false
                }
            }
        }

        // Check 3: 3x3x3 air cube above return pad
        val airCubeHeight = ObelisksConstants.RETURN_PAD_AIR_CUBE_HEIGHT
        for (dy in 1..airCubeHeight) {
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val airPos = returnPadPos.offset(dx, dy, dz)
                    val state = level.getBlockState(airPos)

                    if (!state.isAir || !state.fluidState.isEmpty) {
                        return false
                    }
                }
            }
        }

        // Check 4: Platform floor exists (at least center 3x3)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val floorPos = platformGroundPos.offset(dx, 0, dz)
                val floorState = level.getBlockState(floorPos)

                if (!floorState.isSolidRender(level, floorPos)) {
                    return false
                }
            }
        }

        return true
    }

    /**
     * Finds a suitable location for a spawn platform.
     * Searches for an open 3x3x3 air space with most solid ground below.
     * Returns the ground-level position where the platform should be built.
     */
    private fun findSuitableLocation(level: ServerLevel, searchCenter: BlockPos): BlockPos? {
        val minY = level.minBuildHeight + 10
        val maxY = level.maxBuildHeight - 15

        // Collect candidate locations (at least 3, or all suitable ones found)
        val candidates = mutableListOf<Pair<BlockPos, Int>>() // Pair of (position, solid ground score)

        // Search in expanding spiral pattern
        val maxSearchRadius = 32
        val searchStep = 8

        for (radius in 0..maxSearchRadius step searchStep) {
            for (angle in 0 until 360 step 45) {
                val rad = Math.toRadians(angle.toDouble())
                val x = searchCenter.x + (radius * Math.cos(rad)).toInt()
                val z = searchCenter.z + (radius * Math.sin(rad)).toInt()

                // Search vertically for suitable spot
                for (y in minY until maxY) {
                    val testPos = BlockPos(x, y, z)

                    if (isSuitableForPlatform(level, testPos)) {
                        // Calculate solid ground score for this location
                        val solidGroundScore = calculateSolidGroundScore(level, testPos)
                        candidates.add(Pair(testPos, solidGroundScore))

                        // Once we have at least 3 candidates, we can start being selective
                        if (candidates.size >= 3) {
                            // Return best candidate so far if we've searched enough
                            if (radius > 16 || candidates.size >= 10) {
                                return candidates.maxByOrNull { it.second }?.first
                            }
                        }
                    }
                }
            }
        }

        // Return the best candidate we found, or null if none
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * Calculates a score representing how much solid ground exists below a platform position.
     * Higher score = more solid ground = better stability.
     * Checks a 7x7 area up to 5 blocks below the platform.
     */
    private fun calculateSolidGroundScore(level: ServerLevel, groundPos: BlockPos): Int {
        val radius = ObelisksConstants.PLATFORM_RADIUS
        var solidBlockCount = 0

        // Check 7x7 area at multiple depths below platform
        for (y in 0 downTo -5) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val checkPos = groundPos.offset(x, y, z)
                    val state = level.getBlockState(checkPos)

                    if (state.isSolidRender(level, checkPos)) {
                        // Weight blocks closer to the platform more heavily
                        val depthWeight = 6 + y // y ranges from 0 to -5, so weight is 6 to 1
                        solidBlockCount += depthWeight
                    }
                }
            }
        }

        return solidBlockCount
    }

    /**
     * Checks if a position is suitable for building a platform.
     * Requirements:
     * - 7x7x3 air space above the position (full platform clearance)
     * - No bedrock within platform area or 5 blocks below
     * - Solid ground nearby (within 5 blocks below center)
     */
    private fun isSuitableForPlatform(level: ServerLevel, groundPos: BlockPos): Boolean {
        val radius = ObelisksConstants.PLATFORM_RADIUS

        // Check that platform ground level doesn't contain bedrock
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val checkPos = groundPos.offset(x, 0, z)
                val state = level.getBlockState(checkPos)
                if (state.block == Blocks.BEDROCK) {
                    return false
                }
            }
        }

        // Check for 7x7x3 air space above ground level (ensures no suffocation)
        for (y in 1..3) {
            for (x in -radius..radius) {
                for (z in -radius..radius) {
                    val checkPos = groundPos.offset(x, y, z)
                    val state = level.getBlockState(checkPos)

                    // Must be air only (no replaceable blocks like grass)
                    if (!state.isAir) {
                        return false
                    }
                }
            }
        }

        // Check for solid ground within 5 blocks below center (and no bedrock below)
        for (y in 0 downTo -5) {
            val checkPos = groundPos.offset(0, y, 0)
            val state = level.getBlockState(checkPos)

            // Reject if we hit bedrock
            if (state.block == Blocks.BEDROCK) {
                return false
            }

            if (state.isSolidRender(level, checkPos)) {
                return true
            }
        }

        return false
    }

    /**
     * Checks if a platform has already been generated at the given location.
     * Used to make generation idempotent.
     */
    fun isPlatformGenerated(level: ServerLevel, spawnPos: BlockPos): Boolean {
        // Check if the center platform block exists
        val platformPos = spawnPos.below()
        val block = level.getBlockState(platformPos).block

        return block == Blocks.NETHERRACK ||
               block == Blocks.END_STONE ||
               block == Blocks.NETHER_BRICKS ||
               block == Blocks.END_STONE_BRICKS
    }
}
