package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.ObelisksConstants
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
 * Custom feature that places obelisks as meteors in craters during worldgen.
 */
class ObeliskFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val startTime = System.currentTimeMillis()
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

        // Check if ground is water or underwater - don't spawn
        val groundBlock = level.getBlockState(groundPos.below())
        if (!groundBlock.fluidState.isEmpty) {
            return false
        }

        // Check if there's water above the ground position (0..10 blocks up)
        for (i in 0..10) {
            val checkPos = groundPos.above(i)
            if (!level.getBlockState(checkPos).fluidState.isEmpty) {
                return false // Water above, would spawn underwater
            }
        }

        // Validate terrain flatness - check surrounding 5x5 area for narrow cracks/caves
        val centerY = groundPos.y
        var validGroundCount = 0
        var totalChecks = 0

        for (dx in -2..2) {
            for (dz in -2..2) {
                if (dx == 0 && dz == 0) continue // Skip center
                totalChecks++

                val checkPos = groundPos.offset(dx, 0, dz)
                val nearbyGround = findLocalGround(level, checkPos)

                if (nearbyGround != null) {
                    val yDiff = Math.abs(nearbyGround.y - centerY)
                    // Accept ground within 4 blocks of center height
                    if (yDiff <= 4) {
                        validGroundCount++
                    }
                }
            }
        }

        // Require at least 60% of surrounding positions to have reasonable ground
        val validRatio = validGroundCount.toFloat() / totalChecks.toFloat()
        if (validRatio < 0.6f) {
            println("[OBELISKS] Rejected spawn at $groundPos - terrain too uneven ($validGroundCount/$totalChecks valid)")
            return false
        }

        // Choose random obelisk config with weighted rarity
        val config = dev.yourname.obelisks.config.ObeliskTypeRegistry.getRandomWeightedConfig(random) ?: return false

        println("[OBELISKS] Starting meteor at $groundPos (${config.dimensionConfig.dimensionId})")

        // Check if AE2 skystone is available
        val skystoneBlock = try {
            val blockLocation = ResourceLocation.tryParse("ae2:sky_stone_block")
            if (blockLocation != null) {
                val block = BuiltInRegistries.BLOCK.get(blockLocation)
                if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    println("[OBELISKS] AE2 skystone found: $block - will use for meteors")
                    block
                } else {
                    println("[OBELISKS] AE2 skystone not registered")
                    null
                }
            } else {
                println("[OBELISKS] Invalid resource location for skystone")
                null
            }
        } catch (e: Exception) {
            println("[OBELISKS] AE2 check failed: ${e.message}")
            null
        }

        // Generate crater
        generateCrater(level, groundPos, random)
        val afterCraterTime = System.currentTimeMillis()
        println("[OBELISKS] Crater generated in ${afterCraterTime - startTime}ms")

        // Generate meteor with obelisk core
        generateMeteor(level, groundPos, config, random, skystoneBlock)
        val afterMeteorTime = System.currentTimeMillis()
        println("[OBELISKS] Meteor generated in ${afterMeteorTime - afterCraterTime}ms")

        val totalTime = System.currentTimeMillis() - startTime
        println("[OBELISKS] TOTAL meteor generation time: ${totalTime}ms")

        return true
    }

    /**
     * Generates a crater for the meteor impact with gravel fill.
     */
    private fun generateCrater(level: WorldGenLevel, centerPos: BlockPos, random: RandomSource) {
        val craterRadius = 6 + random.nextInt(3) // 6-8 block radius (bigger than meteor)
        val craterDepth = 3 + random.nextInt(2) // 3-4 blocks deep

        for (x in -craterRadius..craterRadius) {
            for (z in -craterRadius..craterRadius) {
                val distance = Math.sqrt((x * x + z * z).toDouble())
                if (distance > craterRadius) continue

                // Calculate depth based on distance from center (deeper in middle)
                val depthFactor = 1.0 - (distance / craterRadius)
                val actualDepth = (craterDepth * depthFactor).toInt()

                for (y in 0 until actualDepth) {
                    val pos = centerPos.offset(x, -y, z)
                    // Clear blocks to create crater depression
                    level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)
                }

                // Fill bottom of crater with gravel (partial fill, not completely full)
                if (actualDepth > 0) {
                    val gravelLayers = (actualDepth * 0.4).toInt().coerceAtLeast(1) // Fill ~40% with gravel
                    for (g in 0 until gravelLayers) {
                        val gravelPos = centerPos.offset(x, -actualDepth + g, z)
                        // Mix gravel with some coarse dirt for variation
                        val block = if (random.nextFloat() < 0.75f) {
                            net.minecraft.world.level.block.Blocks.GRAVEL
                        } else {
                            net.minecraft.world.level.block.Blocks.COARSE_DIRT
                        }
                        level.setBlock(gravelPos, block.defaultBlockState(), 3)
                    }
                }
            }
        }
    }

    /**
     * Generates a meteor with an obelisk core, encased in stone/cobble or skystone.
     */
    private fun generateMeteor(
        level: WorldGenLevel,
        centerPos: BlockPos,
        config: dev.yourname.obelisks.config.ObeliskTypeConfig,
        random: RandomSource,
        skystoneBlock: net.minecraft.world.level.block.Block?
    ) {
        // Determine meteor material - always use skystone if available, otherwise stone
        val meteorMaterial = if (skystoneBlock != null) {
            println("[OBELISKS] Using skystone for meteor")
            Pair(skystoneBlock, skystoneBlock)
        } else {
            println("[OBELISKS] Using stone/cobblestone for meteor")
            Pair(net.minecraft.world.level.block.Blocks.STONE, net.minecraft.world.level.block.Blocks.COBBLESTONE)
        }

        val meteorBlock = meteorMaterial.first
        val meteorShellBlock = meteorMaterial.second

        // Small meteor radius (3-4 blocks)
        val meteorRadius = 3
        val meteorCenterPos = centerPos.below() // Embed in crater

        // Place obelisk at center
        val obeliskState = ModBlocks.OBELISK.get().defaultBlockState()
        level.setBlock(meteorCenterPos, obeliskState, 3)

        // Configure the block entity
        val blockEntity = level.getBlockEntity(meteorCenterPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
        if (blockEntity != null) {
            blockEntity.targetDimensionId = config.dimensionConfig.dimensionId
            blockEntity.dimensionDisplayName = config.dimensionConfig.dimensionName
            blockEntity.syncToClients()
        }

        // Generate spherical meteor around obelisk core
        for (x in -meteorRadius..meteorRadius) {
            for (y in -meteorRadius..meteorRadius) {
                for (z in -meteorRadius..meteorRadius) {
                    val distance = Math.sqrt((x * x + y * y + z * z).toDouble())
                    if (distance > meteorRadius) continue

                    val pos = meteorCenterPos.offset(x, y, z)

                    // Skip the center (obelisk)
                    if (x == 0 && y == 0 && z == 0) continue

                    // Outer layer is cobblestone/skystone shell, inner is stone/skystone
                    val block = if (distance > meteorRadius - 0.8) {
                        meteorShellBlock
                    } else {
                        meteorBlock
                    }

                    // Random gaps for natural look
                    if (random.nextFloat() > 0.9f) continue

                    level.setBlock(pos, block.defaultBlockState(), 3)
                }
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

    /**
     * Finds ground level near a position (smaller search than main findGroundPosition).
     */
    private fun findLocalGround(level: WorldGenLevel, startPos: BlockPos): BlockPos? {
        // First try looking down
        var currentPos = startPos
        for (i in 0..15) {
            val blockBelow = level.getBlockState(currentPos.below())
            if (blockBelow.isSolidRender(level, currentPos.below())) {
                return currentPos
            }
            currentPos = currentPos.below()
        }

        // If not found, try looking up from start position
        currentPos = startPos.above()
        for (i in 0..15) {
            val blockBelow = level.getBlockState(currentPos.below())
            if (blockBelow.isSolidRender(level, currentPos.below())) {
                return currentPos
            }
            currentPos = currentPos.above()
        }

        return null
    }
}
