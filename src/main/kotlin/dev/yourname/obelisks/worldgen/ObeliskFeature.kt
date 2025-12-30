package dev.yourname.obelisks.worldgen

import com.mojang.serialization.Codec
import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.config.ConfigManager
import dev.yourname.obelisks.config.DimensionConfig
import dev.yourname.obelisks.config.ObeliskTypeRegistry
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
        val blockEntity = level.getBlockEntity(capPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
        if (blockEntity != null) {
            // Store dimension ID and display name
            blockEntity.targetDimensionId = config.dimensionConfig.dimensionId
            blockEntity.dimensionDisplayName = config.dimensionConfig.dimensionName
            blockEntity.syncToClients()
        }

        // Place dimension-specific blocks in item frames on pillars
        placeDimensionBlockPillars(level, groundPos, config.dimensionConfig, random)

        // Scatter gravel around obelisk base
        scatterGravel(level, groundPos, random)

        // Generate epic ruins around the obelisk
        println("[OBELISKS] Generating ruins at $groundPos for dimension ${config.dimensionConfig.dimensionId}")
        generateRuins(level, groundPos, config.dimensionConfig, random)
        println("[OBELISKS] Ruins generation complete")

        return true
    }

    /**
     * Places dimension-specific blocks in item frames on pillars around the obelisk.
     * Pillars face toward the center with item frames showing the dimension's blocks.
     */
    private fun placeDimensionBlockPillars(
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

        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val radius = 4 // Closer to obelisk

        // Place 4-6 pillars in a circle around the obelisk
        val pillarCount = (4 + random.nextInt(3)).coerceAtMost(flavorBlocks.size)

        for (i in 0 until pillarCount) {
            // Evenly distribute pillars around circle
            val angle = (i.toDouble() / pillarCount) * Math.PI * 2
            val x = centerPos.x + (radius * Math.cos(angle)).toInt()
            val z = centerPos.z + (radius * Math.sin(angle)).toInt()

            val pillarBase = findLocalGround(level, BlockPos(x, centerPos.y, z)) ?: continue

            // Check if there's at least 1 air block below (requirement)
            val blockBelow = level.getBlockState(pillarBase.below())
            if (!blockBelow.isAir) continue // Skip if no air below

            // Build pillar (2-3 blocks high)
            val pillarHeight = 2 + random.nextInt(2)
            for (h in 0 until pillarHeight) {
                level.setBlock(pillarBase.above(h), baseBlock.defaultBlockState(), 3)
            }

            // Place item frame on top facing toward obelisk center
            val itemFramePos = pillarBase.above(pillarHeight)

            // Calculate direction facing center
            val dx = centerPos.x - x
            val dz = centerPos.z - z
            val facing = when {
                Math.abs(dx) > Math.abs(dz) -> if (dx > 0) net.minecraft.core.Direction.EAST else net.minecraft.core.Direction.WEST
                else -> if (dz > 0) net.minecraft.core.Direction.SOUTH else net.minecraft.core.Direction.NORTH
            }

            // Place item frame
            val itemFrameEntity = net.minecraft.world.entity.decoration.ItemFrame(
                level.level,
                itemFramePos,
                facing
            )

            // Pick a dimension-specific block to display
            val flavorBlockId = flavorBlocks[i % flavorBlocks.size]
            val block = BuiltInRegistries.BLOCK.get(ResourceLocation(flavorBlockId))

            if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                val itemStack = net.minecraft.world.item.ItemStack(block.asItem())
                itemFrameEntity.item = itemStack
                level.level.addFreshEntity(itemFrameEntity)
            }
        }
    }

    /**
     * Scatters gravel around the obelisk base for a natural "excavation" look.
     * MUCH MORE gravel for proper ancient ruin aesthetic.
     */
    private fun scatterGravel(level: WorldGenLevel, centerPos: BlockPos, random: RandomSource) {
        val radius = 8 // Bigger radius
        val blockCount = 50 + random.nextInt(30) // 50-80 gravel blocks (was 15-25)

        for (i in 0 until blockCount) {
            val xOffset = random.nextInt(radius * 2 + 1) - radius
            val zOffset = random.nextInt(radius * 2 + 1) - radius

            // Skip if too close to obelisk center (within 1 block)
            if (Math.abs(xOffset) <= 1 && Math.abs(zOffset) <= 1) continue

            val targetPos = centerPos.offset(xOffset, -1, zOffset) // Place on ground level

            val currentBlock = level.getBlockState(targetPos)

            // Only replace natural blocks (dirt, grass, stone, etc.)
            if (currentBlock.isSolidRender(level, targetPos) &&
                !currentBlock.fluidState.isEmpty.not()) {

                // 85% gravel, 15% coarse dirt for variation
                val block = if (random.nextFloat() < 0.85f) {
                    net.minecraft.world.level.block.Blocks.GRAVEL
                } else {
                    net.minecraft.world.level.block.Blocks.COARSE_DIRT
                }

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

    /**
     * Generates epic ruins around the obelisk with biome-appropriate weathering.
     * Always uses overworld materials (stone bricks) regardless of dimension.
     */
    private fun generateRuins(
        level: WorldGenLevel,
        centerPos: BlockPos,
        dimConfig: DimensionConfig,
        random: RandomSource
    ) {
        // Get biome data for weathering calculations
        val biome = level.getBiome(centerPos)
        val temperature = biome.value().getBaseTemperature()
        val downfall = biome.value().getModifiedClimateSettings().downfall()

        println("[OBELISKS] Biome temp: $temperature, downfall: $downfall")

        // Generate raised dais platform extending to ground
        generateDais(level, centerPos, random, temperature, downfall)

        // Procedurally generate all ruin types with random chance
        if (random.nextFloat() < 0.7f) generateCrumbledWalls(level, centerPos, random, temperature, downfall)
        if (random.nextFloat() < 0.5f) generateBrokenArchway(level, centerPos, random, temperature, downfall)
        if (random.nextFloat() < 0.8f) generateScatteredPillars(level, centerPos, random, temperature, downfall)
        if (random.nextFloat() < 0.6f) generateFoundationRuins(level, centerPos, random, temperature, downfall)
        
        // Add epic decorative features
        generateEpicFeatures(level, centerPos, random, temperature, downfall)

        // Scatter gravel around the ruins
        scatterRuinGravel(level, centerPos, random)
    }

    /**
     * Generates crumbled wall ruins in a partial circle around the obelisk.
     */
    private fun generateCrumbledWalls(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val radius = 10 + random.nextInt(4) // 10-13 blocks (BIGGER)
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS

        // Calculate mossy chance based on humidity - VERY HIGH for wet climates
        val mossyChance = when {
            downfall > 0.8f -> 0.95f // Near 100% in jungle/swamp
            downfall > 0.6f -> 0.85f // 85% in moderate rain
            downfall > 0.4f -> 0.65f // 65% in light rain
            downfall > 0.2f -> 0.40f // 40% in dry-ish areas
            downfall > 0.1f -> 0.15f // 15% in very dry areas
            else -> 0.05f // 5% in true deserts (near 0 rainfall)
        }

        // Generate partial circular wall with gaps
        val angleStep = Math.PI / 12.0 // More segments
        var skipNext = false

        for (i in 0..23) {
            if (skipNext && random.nextFloat() < 0.4f) {
                skipNext = false
                continue // Create gaps in wall
            }

            val angle = i * angleStep
            val x = centerPos.x + (radius * Math.cos(angle)).toInt()
            val z = centerPos.z + (radius * Math.sin(angle)).toInt()

            // Find ground at this position
            val wallGroundPos = findLocalGround(level, BlockPos(x, centerPos.y, z))
            if (wallGroundPos != null) {
                // Build wall segment (2-6 blocks high, deteriorating) - TALLER
                val height = 2 + random.nextInt(5)
                for (h in 0 until height) {
                    val pos = wallGroundPos.above(h)
                    // Mix of blocks for variety - mossy chance increases with humidity
                    val block = when {
                        random.nextFloat() < 0.15f -> crackedBlock
                        random.nextFloat() < mossyChance -> weatheredBlock
                        else -> baseBlock
                    }
                    level.setBlock(pos, block.defaultBlockState(), 3)

                    // Add weathering decoration
                    applyWeathering(level, pos, temperature, downfall, random)
                }

                // Add depth - double thickness walls occasionally
                if (random.nextFloat() < 0.4f) {
                    val innerPos = BlockPos(
                        centerPos.x + ((radius - 1) * Math.cos(angle)).toInt(),
                        wallGroundPos.y,
                        centerPos.z + ((radius - 1) * Math.sin(angle)).toInt()
                    )
                    val innerHeight = (height * 0.7).toInt()
                    for (h in 0 until innerHeight) {
                        level.setBlock(innerPos.above(h), baseBlock.defaultBlockState(), 3)
                    }
                }

                // Randomly skip sections for ruined appearance
                if (random.nextFloat() < 0.3f) skipNext = true
            }
        }
    }

    /**
     * Generates a broken archway entrance to the obelisk.
     */
    private fun generateBrokenArchway(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val distance = 11 // BIGGER
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS
        val stairsBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS

        // Calculate mossy chance based on humidity - VERY HIGH for wet climates
        val mossyChance = when {
            downfall > 0.8f -> 0.95f // Near 100% in jungle/swamp
            downfall > 0.6f -> 0.85f // 85% in moderate rain
            downfall > 0.4f -> 0.65f // 65% in light rain
            downfall > 0.2f -> 0.40f // 40% in dry-ish areas
            downfall > 0.1f -> 0.15f // 15% in very dry areas
            else -> 0.05f // 5% in true deserts (near 0 rainfall)
        }

        // Choose a direction for the archway
        val direction = random.nextInt(4)
        val xOffset = if (direction == 0) distance else if (direction == 2) -distance else 0
        val zOffset = if (direction == 1) distance else if (direction == 3) -distance else 0

        val archBase = centerPos.offset(xOffset, 0, zOffset)
        val groundPos = findLocalGround(level, archBase) ?: return

        // Build EPIC pillars on both sides (3x3 base, taller)
        for (side in -3..3 step 6) { // Left and right pillars, further apart
            val pillarCenter = groundPos.offset(if (xOffset != 0) 0 else side, 0, if (zOffset != 0) 0 else side)
            val pillarHeight = 5 + random.nextInt(4) // 5-8 blocks tall

            // 3x3 pillar base
            for (px in -1..1) {
                for (pz in -1..1) {
                    val pillarPos = pillarCenter.offset(px, 0, pz)
                    for (h in 0 until pillarHeight) {
                        val pos = pillarPos.above(h)
                        val block = when {
                            h > pillarHeight - 2 && random.nextFloat() < 0.5f -> continue // Missing top blocks
                            random.nextFloat() < 0.15f -> crackedBlock
                            random.nextFloat() < mossyChance -> weatheredBlock
                            else -> baseBlock
                        }
                        level.setBlock(pos, block.defaultBlockState(), 3)
                        if (px == 0 && pz == 0) applyWeathering(level, pos, temperature, downfall, random)
                    }
                }
            }

            // Add decorative stairs at corners
            if (random.nextFloat() < 0.6f) {
                val stairPos = pillarCenter.offset(1, pillarHeight - 1, 1)
                level.setBlock(stairPos, stairsBlock.defaultBlockState(), 3)
            }

            // MORE rubble at base
            for (i in 0..6) {
                val rubbleOffset = pillarCenter.offset(
                    random.nextInt(5) - 2,
                    -1,
                    random.nextInt(5) - 2
                )
                if (random.nextFloat() < 0.7f) {
                    val rubbleBlock = if (random.nextFloat() < 0.5f) crackedBlock else weatheredBlock
                    level.setBlock(rubbleOffset, rubbleBlock.defaultBlockState(), 3)
                }
            }
        }
    }

    /**
     * Generates scattered, toppled pillars around the obelisk.
     */
    private fun generateScatteredPillars(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS
        val pillarCount = 6 + random.nextInt(6) // 6-11 pillars (MORE)

        // Calculate mossy chance based on humidity - VERY HIGH for wet climates
        val mossyChance = when {
            downfall > 0.8f -> 0.95f // Near 100% in jungle/swamp
            downfall > 0.6f -> 0.85f // 85% in moderate rain
            downfall > 0.4f -> 0.65f // 65% in light rain
            downfall > 0.2f -> 0.40f // 40% in dry-ish areas
            downfall > 0.1f -> 0.15f // 15% in very dry areas
            else -> 0.05f // 5% in true deserts (near 0 rainfall)
        }

        for (i in 0 until pillarCount) {
            // Random position around obelisk
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 8 + random.nextInt(5) // Further out
            val x = centerPos.x + (distance * Math.cos(angle)).toInt()
            val z = centerPos.z + (distance * Math.sin(angle)).toInt()

            val groundPos = findLocalGround(level, BlockPos(x, centerPos.y, z)) ?: continue

            if (random.nextFloat() < 0.5f) {
                // Standing pillar (broken) - TALLER and THICKER
                val height = 3 + random.nextInt(6)
                // 2x2 pillar
                for (px in 0..1) {
                    for (pz in 0..1) {
                        for (h in 0 until height) {
                            val pos = groundPos.offset(px, h, pz)
                            val block = when {
                                h > height - 2 && random.nextFloat() < 0.4f -> continue
                                random.nextFloat() < 0.2f -> crackedBlock
                                random.nextFloat() < mossyChance -> weatheredBlock
                                else -> baseBlock
                            }
                            level.setBlock(pos, block.defaultBlockState(), 3)
                            if (px == 0 && pz == 0) applyWeathering(level, pos, temperature, downfall, random)
                        }
                    }
                }
            } else {
                // Toppled pillar (horizontal) - LONGER
                val length = 4 + random.nextInt(5)
                val horizontal = if (random.nextBoolean()) 1 else 0 // X or Z axis

                for (l in 0 until length) {
                    val pos = if (horizontal == 0) {
                        groundPos.offset(l, 0, 0)
                    } else {
                        groundPos.offset(0, 0, l)
                    }
                    val block = when {
                        random.nextFloat() < 0.2f -> crackedBlock
                        random.nextFloat() < mossyChance -> weatheredBlock
                        else -> baseBlock
                    }
                    level.setBlock(pos, block.defaultBlockState(), 3)
                    applyWeathering(level, pos, temperature, downfall, random)
                }
            }
        }
    }

    /**
     * Generates foundation/floor ruins beneath the obelisk.
     */
    private fun generateFoundationRuins(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS
        val radius = 9 // BIGGER

        // Calculate mossy chance based on humidity - VERY HIGH for wet climates
        val mossyChance = when {
            downfall > 0.8f -> 0.95f // Near 100% in jungle/swamp
            downfall > 0.6f -> 0.85f // 85% in moderate rain
            downfall > 0.4f -> 0.65f // 65% in light rain
            downfall > 0.2f -> 0.40f // 40% in dry-ish areas
            downfall > 0.1f -> 0.15f // 15% in very dry areas
            else -> 0.05f // 5% in true deserts (near 0 rainfall)
        }

        // Create partial foundation pattern
        for (x in -radius..radius) {
            for (z in -radius..radius) {
                val distance = Math.sqrt((x * x + z * z).toDouble())
                if (distance > radius) continue

                // Randomized pattern with gaps
                if (random.nextFloat() < 0.4f) continue

                val pos = centerPos.offset(x, -1, z)

                // Don't overwrite obelisk pillar
                if (x == 0 && z == 0) continue

                val block = when {
                    random.nextFloat() < 0.15f -> crackedBlock
                    random.nextFloat() < mossyChance -> weatheredBlock
                    else -> baseBlock
                }
                level.setBlock(pos, block.defaultBlockState(), 3)

                // Add BIGGER pillars/stubs at corners and edges
                if (Math.abs(x) >= radius - 1 || Math.abs(z) >= radius - 1) {
                    if (random.nextFloat() < 0.25f) {
                        val stubHeight = 2 + random.nextInt(3) // 2-4 blocks
                        for (h in 0 until stubHeight) {
                            val stubPos = pos.above(h + 1)
                            val stubBlock = if (random.nextFloat() < 0.3f) crackedBlock else baseBlock
                            level.setBlock(stubPos, stubBlock.defaultBlockState(), 3)
                            applyWeathering(level, stubPos, temperature, downfall, random)
                        }
                    }
                }
            }
        }
    }

    /**
     * Generates a raised dais/platform that extends down to the ground.
     */
    private fun generateDais(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS
        val stairsBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS

        val platformRadius = 5

        // Create raised platform at obelisk level
        for (x in -platformRadius..platformRadius) {
            for (z in -platformRadius..platformRadius) {
                val distance = Math.sqrt((x * x + z * z).toDouble())
                if (distance > platformRadius) continue

                val platformPos = centerPos.offset(x, 0, z)

                // Don't overwrite obelisk itself
                if (x == 0 && z == 0) continue

                // Place platform blocks
                val block = when {
                    random.nextFloat() < 0.1f -> crackedBlock
                    random.nextFloat() < 0.25f -> weatheredBlock
                    else -> baseBlock
                }
                level.setBlock(platformPos, block.defaultBlockState(), 3)

                // Extend support pillars down to ground from platform - ALWAYS
                val groundPos = findLocalGround(level, platformPos.below())
                if (groundPos != null) {
                    val depth = platformPos.y - groundPos.y
                    if (depth > 0) {
                        // Build support all the way down
                        for (d in 1..depth) {
                            val pillarPos = platformPos.below(d)
                            val currentState = level.getBlockState(pillarPos)

                            // Only replace non-solid blocks
                            if (!currentState.isSolidRender(level, pillarPos) || currentState.fluidState.isEmpty.not()) {
                                val pillarBlock = if (random.nextFloat() < 0.15f) crackedBlock else baseBlock
                                level.setBlock(pillarPos, pillarBlock.defaultBlockState(), 3)
                            }
                        }
                    }
                }

                // Add decorative stairs at platform edges
                if (distance >= platformRadius - 0.5 && distance < platformRadius) {
                    if (random.nextFloat() < 0.2f) {
                        level.setBlock(platformPos.above(), stairsBlock.defaultBlockState(), 3)
                    }
                }
            }
        }
    }

    /**
     * Adds epic decorative features to the ruins.
     */
    private fun generateEpicFeatures(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource,
        temperature: Float,
        downfall: Float
    ) {
        val baseBlock = net.minecraft.world.level.block.Blocks.STONE_BRICKS
        val weatheredBlock = net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS
        val crackedBlock = net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS
        val stairsBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS
        val slabBlock = net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB

        // Add 3-5 decorative columns around the area
        val columnCount = 3 + random.nextInt(3)
        for (i in 0 until columnCount) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 6 + random.nextInt(5)
            val x = centerPos.x + (distance * Math.cos(angle)).toInt()
            val z = centerPos.z + (distance * Math.sin(angle)).toInt()

            val groundPos = findLocalGround(level, BlockPos(x, centerPos.y, z)) ?: continue

            // Decorative column with capital
            val height = 3 + random.nextInt(3)
            for (h in 0 until height) {
                val block = if (random.nextFloat() < 0.2f) crackedBlock else baseBlock
                level.setBlock(groundPos.above(h), block.defaultBlockState(), 3)
            }

            // Add slab or stair capital on top
            if (random.nextBoolean()) {
                level.setBlock(groundPos.above(height), slabBlock.defaultBlockState(), 3)
            }
        }

        // Add scattered stone brick debris piles
        for (i in 0..10) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 5 + random.nextInt(8)
            val x = centerPos.x + (distance * Math.cos(angle)).toInt()
            val z = centerPos.z + (distance * Math.sin(angle)).toInt()

            val groundPos = findLocalGround(level, BlockPos(x, centerPos.y, z)) ?: continue

            // Pile of 2-4 blocks
            val pileSize = 2 + random.nextInt(3)
            for (p in 0 until pileSize) {
                val offsetX = random.nextInt(2)
                val offsetZ = random.nextInt(2)
                val block = when {
                    random.nextFloat() < 0.3f -> crackedBlock
                    random.nextFloat() < 0.5f -> weatheredBlock
                    else -> baseBlock
                }
                level.setBlock(groundPos.offset(offsetX, 0, offsetZ), block.defaultBlockState(), 3)
                applyWeathering(level, groundPos.offset(offsetX, 0, offsetZ), temperature, downfall, random)
            }
        }
    }

    /**
     * Scatters gravel around the ruins for an excavated look.
     */
    private fun scatterRuinGravel(
        level: WorldGenLevel,
        centerPos: BlockPos,
        random: RandomSource
    ) {
        val radius = 12
        val gravelCount = 30 + random.nextInt(20) // 30-50 gravel blocks

        for (i in 0 until gravelCount) {
            val angle = random.nextDouble() * Math.PI * 2
            val distance = 3 + random.nextInt(radius - 3)
            val x = centerPos.x + (distance * Math.cos(angle)).toInt()
            val z = centerPos.z + (distance * Math.sin(angle)).toInt()

            val groundPos = findLocalGround(level, BlockPos(x, centerPos.y, z))
            if (groundPos != null) {
                val targetPos = groundPos.below()
                val currentBlock = level.getBlockState(targetPos)

                // Replace natural terrain blocks
                if (currentBlock.isSolidRender(level, targetPos)) {
                    val block = if (random.nextFloat() < 0.85f) {
                        net.minecraft.world.level.block.Blocks.GRAVEL
                    } else {
                        net.minecraft.world.level.block.Blocks.COARSE_DIRT
                    }
                    level.setBlock(targetPos, block.defaultBlockState(), 3)
                }
            }
        }
    }

    /**
     * Applies biome-appropriate weathering (moss, vines, plants) to a block.
     * HEAVY overgrowth for ancient ruins look.
     */
    private fun applyWeathering(
        level: WorldGenLevel,
        pos: BlockPos,
        temperature: Float,
        downfall: Float,
        random: RandomSource
    ) {
        // MUCH higher weathering chances for overgrown look - but NOT in deserts
        val weatheringChance = when {
            downfall > 0.7f -> 0.95f // Nearly 100% in wet biomes
            downfall > 0.5f -> 0.80f // 80% in moderate
            downfall > 0.3f -> 0.60f // 60% in light rain
            downfall > 0.15f -> 0.35f // 35% in dry areas
            else -> 0.10f // Only 10% in true deserts
        }

        if (random.nextFloat() > weatheringChance) return

        val abovePos = pos.above()
        val aboveState = level.getBlockState(abovePos)

        // Don't place if space is occupied
        if (!aboveState.isAir) return

        // HOT + HUMID = HEAVY jungle overgrowth (vines, tall grass, ferns)
        if (temperature > 0.8f && downfall > 0.7f) {
            val vegetation = when {
                random.nextFloat() < 0.50f -> net.minecraft.world.level.block.Blocks.VINE
                random.nextFloat() < 0.70f -> net.minecraft.world.level.block.Blocks.TALL_GRASS
                random.nextFloat() < 0.85f -> net.minecraft.world.level.block.Blocks.FERN
                else -> net.minecraft.world.level.block.Blocks.LARGE_FERN
            }
            level.setBlock(abovePos, vegetation.defaultBlockState(), 3)

            // Add hanging vines on sides occasionally
            if (random.nextFloat() < 0.4f) {
                val sideDir = when(random.nextInt(4)) {
                    0 -> pos.north()
                    1 -> pos.south()
                    2 -> pos.east()
                    else -> pos.west()
                }
                if (level.getBlockState(sideDir).isAir) {
                    level.setBlock(sideDir, net.minecraft.world.level.block.Blocks.VINE.defaultBlockState(), 3)
                }
            }
        }
        // Moderate + humid = moss, grass, flowers
        else if (temperature > 0.5f && downfall > 0.5f) {
            val plant = when {
                random.nextFloat() < 0.40f -> net.minecraft.world.level.block.Blocks.MOSS_CARPET
                random.nextFloat() < 0.65f -> net.minecraft.world.level.block.Blocks.GRASS
                random.nextFloat() < 0.80f -> net.minecraft.world.level.block.Blocks.TALL_GRASS
                random.nextFloat() < 0.90f -> net.minecraft.world.level.block.Blocks.FERN
                else -> net.minecraft.world.level.block.Blocks.DANDELION
            }
            level.setBlock(abovePos, plant.defaultBlockState(), 3)
        }
        // Cold + wet = moss, ferns, spruce saplings
        else if (temperature < 0.5f && downfall > 0.4f) {
            val coldPlant = when {
                random.nextFloat() < 0.60f -> net.minecraft.world.level.block.Blocks.MOSS_CARPET
                random.nextFloat() < 0.85f -> net.minecraft.world.level.block.Blocks.FERN
                else -> net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH
            }
            level.setBlock(abovePos, coldPlant.defaultBlockState(), 3)
        }
        // Dry = dead bush, cactus, occasional desert plants - NO GRASS IN TRUE DESERT
        else if (downfall < 0.3f) {
            // Only dead bushes in extremely dry deserts
            if (downfall < 0.1f) {
                if (random.nextFloat() < 0.80f) {
                    level.setBlock(abovePos, net.minecraft.world.level.block.Blocks.DEAD_BUSH.defaultBlockState(), 3)
                }
                // Rare cactus
                else if (random.nextFloat() < 0.3f) {
                    level.setBlock(abovePos, net.minecraft.world.level.block.Blocks.CACTUS.defaultBlockState(), 3)
                }
            } else {
                // Slightly less dry - can have some dried grass
                val dryPlant = when {
                    random.nextFloat() < 0.60f -> net.minecraft.world.level.block.Blocks.DEAD_BUSH
                    random.nextFloat() < 0.85f -> net.minecraft.world.level.block.Blocks.GRASS // Dried grass only in 0.1-0.3 downfall
                    else -> net.minecraft.world.level.block.Blocks.CACTUS
                }
                level.setBlock(abovePos, dryPlant.defaultBlockState(), 3)
            }
        }
    }

    /**
     * Finds ground level near a position (smaller search than main findGroundPosition).
     */
    private fun findLocalGround(level: WorldGenLevel, startPos: BlockPos): BlockPos? {
        var currentPos = startPos

        // Look down up to 15 blocks
        for (i in 0..15) {
            val blockBelow = level.getBlockState(currentPos.below())
            if (blockBelow.isSolidRender(level, currentPos.below())) {
                return currentPos
            }
            currentPos = currentPos.below()
        }

        // Look up if we went too far down
        currentPos = startPos
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
