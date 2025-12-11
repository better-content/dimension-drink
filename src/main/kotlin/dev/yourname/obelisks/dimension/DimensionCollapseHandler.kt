package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.config.ObelisksConfig
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.run.RunData
import dev.yourname.obelisks.run.RunManager
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.random.Random

/**
 * Handles progressive dimension collapse effects based on FE thresholds.
 * Phase 3: Collapse Logic
 * 
 * Thresholds:
 * - 90-100%: Stable (no effects)
 * - 30-90%: Warning phase (boss bar visible, stable visually)
 * - 10-30%: Decay phase (particles, sounds, minor block changes)
 * - 0-10%: Critical collapse (aggressive block removal, void holes)
 * - 0%: Forced return (handled by InstanceTickHandler)
 */
object DimensionCollapseHandler {

    private var tickCounter = 0

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        tickCounter++

        val server = event.server
        val runManager = RunManager.get(server)

        // Process each active run
        for (runData in runManager.getAllRuns()) {
            // Get origin obelisk to read FE level
            val originLevel = server.getLevel(runData.originDimension) ?: continue
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity ?: continue

            val fePercent = obeliskBE.getEnergyPercent()

            // Get run dimension
            val runLevel = server.getLevel(runData.runDimensionKey) ?: continue

            // Apply collapse effects based on FE threshold
            when {
                fePercent >= 0.30 -> {
                    // 30-100%: No collapse effects (stable or warning phase)
                    // Boss bar handles the warning display
                }
                fePercent >= ObelisksConfig.COLLAPSE_CRITICAL_THRESHOLD -> {
                    // 10-30%: Decay phase with individual block deletion at increasing frequency
                    if (tickCounter % ObelisksConfig.COLLAPSE_EFFECT_INTERVAL_TICKS == 0) {
                        applyDecayEffects(runLevel, runData, fePercent)
                    }
                }
                fePercent > 0.0 -> {
                    // 0-10%: Critical collapse phase with column deletion
                    if (tickCounter % ObelisksConfig.COLLAPSE_CRITICAL_INTERVAL_TICKS == 0) {
                        applyCriticalCollapseEffects(runLevel, runData)
                    }
                }
                // else: 0% is handled by InstanceTickHandler force return
            }
        }
    }

    /**
     * Decay phase (10-30% FE): Cosmetic effects with minor block changes.
     * Individual blocks deleted at increasing frequency as stability drops.
     */
    private fun applyDecayEffects(level: ServerLevel, runData: RunData, fePercent: Double) {
        // Get all players in this dimension
        val players = level.players().filterIsInstance<net.minecraft.server.level.ServerPlayer>()
            .filter { it.uuid in runData.activePlayers }

        if (players.isEmpty()) return

        // Calculate block deletion chance based on stability (increases as FE drops from 30% to 10%)
        // At 30%: ~0% deletion, At 20%: ~25% deletion, At 10%: ~50% deletion
        val stabilityFactor = (0.30 - fePercent) / 0.20 // 0.0 at 30%, 1.0 at 10%
        val blockDeletionChance = stabilityFactor * 0.5f // Max 50% at 10%

        for (player in players) {
            val playerPos = player.blockPosition()

            // Spawn particles around player
            for (i in 0..10) {
                val offsetX = Random.nextDouble(-10.0, 10.0)
                val offsetY = Random.nextDouble(-5.0, 5.0)
                val offsetZ = Random.nextDouble(-10.0, 10.0)

                level.sendParticles(
                    ParticleTypes.SMOKE,
                    playerPos.x + offsetX,
                    playerPos.y + offsetY,
                    playerPos.z + offsetZ,
                    1,
                    0.0, 0.0, 0.0,
                    0.01
                )
            }

            // Play ambient sound
            if (Random.nextFloat() < 0.1f) {
                level.playSound(
                    null,
                    playerPos,
                    SoundEvents.PORTAL_AMBIENT,
                    SoundSource.AMBIENT,
                    0.5f,
                    0.8f + Random.nextFloat() * 0.4f
                )
            }

            // Minor block swaps (rare)
            if (Random.nextFloat() < 0.05f) {
                swapRandomBlockNearby(level, playerPos, runData)
            }

            // Individual block deletion - increases with lower stability
            if (Random.nextFloat() < blockDeletionChance) {
                deleteIndividualBlocksNearby(level, playerPos, runData)
            }
        }
    }

    /**
     * Critical collapse phase (0-10% FE): Aggressive destructive effects.
     * Includes entire column deletion.
     */
    private fun applyCriticalCollapseEffects(level: ServerLevel, runData: RunData) {
        val players = level.players().filterIsInstance<net.minecraft.server.level.ServerPlayer>()
            .filter { it.uuid in runData.activePlayers }

        if (players.isEmpty()) return

        for (player in players) {
            val playerPos = player.blockPosition()

            // Spawn void particles
            for (i in 0..20) {
                val offsetX = Random.nextDouble(-15.0, 15.0)
                val offsetY = Random.nextDouble(-8.0, 8.0)
                val offsetZ = Random.nextDouble(-15.0, 15.0)

                level.sendParticles(
                    ParticleTypes.PORTAL,
                    playerPos.x + offsetX,
                    playerPos.y + offsetY,
                    playerPos.z + offsetZ,
                    2,
                    0.1, 0.1, 0.1,
                    0.1
                )
            }

            // Play critical sound
            if (Random.nextFloat() < 0.2f) {
                level.playSound(
                    null,
                    playerPos,
                    SoundEvents.WITHER_AMBIENT,
                    SoundSource.AMBIENT,
                    0.7f,
                    0.5f + Random.nextFloat() * 0.3f
                )
            }

            // Aggressive individual block removal
            if (Random.nextFloat() < 0.15f) {
                removeRandomBlocksNearby(level, playerPos, runData)
            }

            // Delete entire columns (more frequent than void holes)
            if (Random.nextFloat() < 0.08f) {
                deleteRandomColumn(level, playerPos, runData)
            }

            // Create void holes (very rare but dramatic)
            if (Random.nextFloat() < 0.02f) {
                createVoidHole(level, playerPos, runData)
            }
        }
    }

    /**
     * Swaps random blocks to their "cracked" variants.
     */
    private fun swapRandomBlockNearby(level: ServerLevel, center: BlockPos, runData: RunData) {
        val radius = 5
        val attempts = 3

        for (i in 0 until attempts) {
            val offsetX = Random.nextInt(-radius, radius + 1)
            val offsetY = Random.nextInt(-3, 4)
            val offsetZ = Random.nextInt(-radius, radius + 1)

            val targetPos = center.offset(offsetX, offsetY, offsetZ)

            // Safety check: only modify blocks in the run dimension
            if (!isInRunDimension(level, runData)) return

            val currentBlock = level.getBlockState(targetPos).block

            // Swap to cracked variants
            val newBlock = when (currentBlock) {
                Blocks.STONE -> Blocks.COBBLESTONE
                Blocks.COBBLESTONE -> Blocks.MOSSY_COBBLESTONE
                Blocks.STONE_BRICKS -> Blocks.CRACKED_STONE_BRICKS
                Blocks.DIRT -> Blocks.COARSE_DIRT
                Blocks.GRASS_BLOCK -> Blocks.DIRT
                Blocks.NETHERRACK -> Blocks.MAGMA_BLOCK
                else -> null
            }

            if (newBlock != null && currentBlock != Blocks.AIR) {
                level.setBlock(targetPos, newBlock.defaultBlockState(), 3)
            }
        }
    }

    /**
     * Removes random blocks, creating gaps.
     */
    private fun removeRandomBlocksNearby(level: ServerLevel, center: BlockPos, runData: RunData) {
        val radius = 8
        val attempts = 5

        for (i in 0 until attempts) {
            val offsetX = Random.nextInt(-radius, radius + 1)
            val offsetY = Random.nextInt(-5, 6)
            val offsetZ = Random.nextInt(-radius, radius + 1)

            val targetPos = center.offset(offsetX, offsetY, offsetZ)

            // Safety check
            if (!isInRunDimension(level, runData)) return

            val currentBlock = level.getBlockState(targetPos).block

            // Don't remove important blocks
            if (currentBlock != Blocks.AIR && 
                currentBlock != Blocks.BEDROCK &&
                currentBlock != Blocks.BARRIER &&
                !currentBlock.defaultBlockState().isAir) {
                
                level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)
                
                // Spawn particles at removal site
                level.sendParticles(
                    ParticleTypes.LARGE_SMOKE,
                    targetPos.x + 0.5,
                    targetPos.y + 0.5,
                    targetPos.z + 0.5,
                    5,
                    0.3, 0.3, 0.3,
                    0.02
                )
            }
        }
    }

    /**
     * Creates a small void hole (3x3x3 sphere of air).
     */
    private fun createVoidHole(level: ServerLevel, center: BlockPos, runData: RunData) {
        // Safety check
        if (!isInRunDimension(level, runData)) return

        val radius = 2

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        val targetPos = center.offset(x, y, z)
                        val currentBlock = level.getBlockState(targetPos).block

                        if (currentBlock != Blocks.BEDROCK && currentBlock != Blocks.BARRIER) {
                            level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)
                        }
                    }
                }
            }
        }

        // Play dramatic sound
        level.playSound(
            null,
            center,
            SoundEvents.WITHER_BREAK_BLOCK,
            SoundSource.AMBIENT,
            1.0f,
            0.5f
        )

        // Spawn void particles
        for (i in 0..30) {
            val offsetX = Random.nextDouble(-radius.toDouble(), radius.toDouble())
            val offsetY = Random.nextDouble(-radius.toDouble(), radius.toDouble())
            val offsetZ = Random.nextDouble(-radius.toDouble(), radius.toDouble())

            level.sendParticles(
                ParticleTypes.PORTAL,
                center.x + offsetX,
                center.y + offsetY,
                center.z + offsetZ,
                3,
                0.2, 0.2, 0.2,
                0.2
            )
        }
    }

    /**
     * Deletes individual blocks near the player (10-30% stability phase).
     * Used for gradual decay effect.
     */
    private fun deleteIndividualBlocksNearby(level: ServerLevel, center: BlockPos, runData: RunData) {
        val radius = 6
        val attempts = 3

        for (i in 0 until attempts) {
            val offsetX = Random.nextInt(-radius, radius + 1)
            val offsetY = Random.nextInt(-4, 5)
            val offsetZ = Random.nextInt(-radius, radius + 1)

            val targetPos = center.offset(offsetX, offsetY, offsetZ)

            // Safety check
            if (!isInRunDimension(level, runData)) return

            val currentBlock = level.getBlockState(targetPos).block

            // Don't remove important blocks
            if (currentBlock != Blocks.AIR &&
                currentBlock != Blocks.BEDROCK &&
                currentBlock != Blocks.BARRIER &&
                !currentBlock.defaultBlockState().isAir) {

                level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)

                // Spawn particles at deletion site
                level.sendParticles(
                    ParticleTypes.SMOKE,
                    targetPos.x + 0.5,
                    targetPos.y + 0.5,
                    targetPos.z + 0.5,
                    3,
                    0.2, 0.2, 0.2,
                    0.01
                )
            }
        }
    }

    /**
     * Deletes an entire vertical column of blocks (0-10% stability phase).
     * Creates dramatic visual effect of dimension collapsing.
     */
    private fun deleteRandomColumn(level: ServerLevel, center: BlockPos, runData: RunData) {
        // Safety check
        if (!isInRunDimension(level, runData)) return

        val radius = 10
        val offsetX = Random.nextInt(-radius, radius + 1)
        val offsetZ = Random.nextInt(-radius, radius + 1)

        val columnBase = center.offset(offsetX, 0, offsetZ)

        // Delete from bedrock (y=0) to build height
        val minY = level.minBuildHeight
        val maxY = level.maxBuildHeight

        for (y in minY..maxY) {
            val targetPos = BlockPos(columnBase.x, y, columnBase.z)
            val currentBlock = level.getBlockState(targetPos).block

            // Don't remove bedrock or barrier blocks
            if (currentBlock != Blocks.BEDROCK && currentBlock != Blocks.BARRIER) {
                level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)
            }
        }

        // Create dramatic effect at the top of the column
        val effectPos = BlockPos(columnBase.x, center.y, columnBase.z)

        // Play sound
        level.playSound(
            null,
            effectPos,
            SoundEvents.WITHER_BREAK_BLOCK,
            SoundSource.AMBIENT,
            1.2f,
            0.6f
        )

        // Spawn particles along the column
        for (y in (center.y - 10)..(center.y + 10)) {
            level.sendParticles(
                ParticleTypes.LARGE_SMOKE,
                columnBase.x + 0.5,
                y.toDouble(),
                columnBase.z + 0.5,
                4,
                0.1, 0.3, 0.1,
                0.05
            )
        }
    }

    /**
     * Safety check: ensures we're only modifying blocks in the run dimension.
     */
    private fun isInRunDimension(level: ServerLevel, runData: RunData): Boolean {
        return level.dimension() == runData.runDimensionKey
    }
}
