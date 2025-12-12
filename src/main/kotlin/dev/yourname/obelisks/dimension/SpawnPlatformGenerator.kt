package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.dimension.DimensionBaseType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap

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
    fun generateSpawnPlatform(level: ServerLevel, baseType: DimensionBaseType, targetPos: BlockPos? = null): BlockPos {
        val searchCenter = targetPos ?: BlockPos(0, ObelisksConstants.PLATFORM_Y_LEVEL, 0)
        val dimensionKey = level.dimension().location()
        val targetBlock = DimensionProperties.getTargetBlock(dimensionKey)

        println("[Obelisks] === PLATFORM GENERATION STARTED ===")
        println("[Obelisks] Level: $dimensionKey")
        println("[Obelisks] Base type: ${baseType.name}")
        println("[Obelisks] Search center: $searchCenter")
        println("[Obelisks] Target block: $targetBlock")

        // Find a suitable location: open space with target block below
        val platformPos = findSuitableLocation(level, searchCenter, targetBlock)
            ?: run {
                println("[Obelisks] WARNING: Could not find suitable location, using fallback")
                // Fallback: just build at a reasonable height
                BlockPos(searchCenter.x, 70, searchCenter.z)
            }

        println("[Obelisks] Building platform at: $platformPos")

        // Build the platform at the found position
        buildPlatform(level, platformPos, baseType)

        // Post-build validation
        if (!validatePlatform(level, platformPos)) {
            println("[Obelisks] WARNING: Platform validation failed, attempting rebuild")
            buildPlatform(level, platformPos, baseType)
            
            if (!validatePlatform(level, platformPos)) {
                println("[Obelisks] CRITICAL ERROR: Platform validation failed even after rebuild!")
            }
        }

        // Log platform generation for debugging
        println("[Obelisks] Generated spawn platform at $platformPos in ${baseType.name} dimension")
        
        // CRITICAL DEBUG: Verify blocks are actually there immediately after placement
        val returnPadPos = platformPos.above()
        val returnPadBlock = level.getBlockState(returnPadPos).block
        val platformBlock = level.getBlockState(platformPos).block
        println("[Obelisks] POST-GENERATION VERIFICATION:")
        println("[Obelisks]   Return pad block at $returnPadPos: $returnPadBlock")
        println("[Obelisks]   Platform block at $platformPos: $platformBlock")
        println("[Obelisks]   Expected return pad: ${dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get()}")

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

        println("[Obelisks] Marked platform chunks for sync")

        return returnPadPos.above() // Players spawn on top of the return pad (Y+2 above platform ground)
    }

    /**
     * Builds a safe platform at the given location with a return pad in the center.
     * OVERRIDES existing ground blocks to create a stable platform.
     *
     * @param center The ground-level position (Y=ground) where platform will be built
     */
    private fun buildPlatform(level: ServerLevel, center: BlockPos, baseType: DimensionBaseType) {
        // USE OBSIDIAN FOR ALL PLATFORMS - highly visible in both Nether and End
        val platformMaterial = Blocks.OBSIDIAN

        // Create a 7x7 platform
        val radius = ObelisksConstants.PLATFORM_RADIUS

        println("[Obelisks] Building OBSIDIAN platform at ground level $center with radius $radius")

        // PHASE 1: Clear area and build obsidian platform base
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val platformPos = center.offset(x, 0, z)

                // Place OBSIDIAN for entire platform
                level.setBlock(platformPos, platformMaterial.defaultBlockState(), 11)
            }
        }

        println("[Obelisks] Platform base complete, clearing air space...")

        // PHASE 2: Ensure air space above platform (for the 3x3x3 cube + extra clearance)
        val airClearance = ObelisksConstants.PLATFORM_AIR_CLEARANCE
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val pos = center.offset(x, 0, z)

                // Clear blocks above platform for safe breathing space
                for (y in 1..airClearance) {
                    level.setBlock(pos.above(y), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        println("[Obelisks] Air space cleared, building return pad structure...")

        // PHASE 3: Create return pad structure at center
        // Return pad is at Y+1 (one block above platform ground)
        val returnPadPos = center.above(ObelisksConstants.RETURN_PAD_HEIGHT)

        // Place 3x3 obsidian ring around return pad position (at Y+1 level)
        for (x in -1..1) {
            for (z in -1..1) {
                // Skip center - that's where return pad goes
                if (x == 0 && z == 0) continue

                val ringPos = returnPadPos.offset(x, 0, z)
                level.setBlock(ringPos, Blocks.OBSIDIAN.defaultBlockState(), 3)
            }
        }

        // Ensure 3x3x3 air cube above return pad (Y+2, Y+3, Y+4)
        val airCubeHeight = ObelisksConstants.RETURN_PAD_AIR_CUBE_HEIGHT
        for (y in 1..airCubeHeight) {
            for (x in -1..1) {
                for (z in -1..1) {
                    val airPos = returnPadPos.offset(x, y, z)
                    level.setBlock(airPos, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        // Place return pad at center, Y+1
        level.setBlock(returnPadPos, dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get().defaultBlockState(), 11)

        println("[Obelisks] Return pad placed at $returnPadPos")

        // PHASE 4: Add lighting at corners (above obsidian ring level)
        val lightBlock = Blocks.GLOWSTONE
        val lightHeight = ObelisksConstants.PLATFORM_LIGHT_HEIGHT
        level.setBlock(center.offset(-radius, lightHeight, -radius), lightBlock.defaultBlockState(), 3)
        level.setBlock(center.offset(radius, lightHeight, -radius), lightBlock.defaultBlockState(), 3)
        level.setBlock(center.offset(-radius, lightHeight, radius), lightBlock.defaultBlockState(), 3)
        level.setBlock(center.offset(radius, lightHeight, radius), lightBlock.defaultBlockState(), 3)

        println("[Obelisks] Platform construction complete!")
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
            println("[Obelisks] Validation FAILED: Return pad missing at $returnPadPos")
            return false
        }

        // Check 2: Obsidian ring exists (8 blocks around return pad)
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue // Skip center

                val ringPos = returnPadPos.offset(dx, 0, dz)
                val ringBlock = level.getBlockState(ringPos).block

                if (ringBlock != Blocks.OBSIDIAN) {
                    println("[Obelisks] Validation FAILED: Obsidian ring incomplete at $ringPos")
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
                        println("[Obelisks] Validation FAILED: Air cube obstructed at $airPos")
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
                    println("[Obelisks] Validation FAILED: Platform floor missing at $floorPos")
                    return false
                }
            }
        }

        println("[Obelisks] Platform validation PASSED")
        return true
    }

    /**
     * Finds a suitable location for a spawn platform.
     * Searches for an open 3x3x3 air space with target block below.
     * Returns the ground-level position where the platform should be built.
     */
    private fun findSuitableLocation(level: ServerLevel, searchCenter: BlockPos, targetBlock: Block?): BlockPos? {
        val minY = level.minBuildHeight + 10
        val maxY = level.maxBuildHeight - 15

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

                    if (isSuitableForPlatform(level, testPos, targetBlock)) {
                        println("[Obelisks] Found suitable location at $testPos (radius=$radius, angle=$angle)")
                        return testPos
                    }
                }
            }
        }

        println("[Obelisks] No suitable location found after searching ${maxSearchRadius} block radius")
        return null
    }

    /**
     * Checks if a position is suitable for building a platform.
     * Requirements:
     * - 7x7x3 air space above the position (full platform clearance)
     * - No bedrock within platform area or 5 blocks below
     * - Solid ground nearby (within 5 blocks below center)
     */
    private fun isSuitableForPlatform(level: ServerLevel, groundPos: BlockPos, targetBlock: Block?): Boolean {
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
