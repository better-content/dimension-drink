package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.jaunt.RunData
import dev.yourname.obelisks.jaunt.RunManager
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

    // Track initial block count and total deleted per run
    private val initialBlockCount = mutableMapOf<Long, Int>()
    private val totalBlocksDeleted = mutableMapOf<Long, Int>()
    private val deletionRate = mutableMapOf<Long, Int>() // blocks per tick

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server

        // Run collapse effects every 5 ticks (4 times per second) for smooth visuals
        if (server.tickCount % 5 != 0) return

        tickCounter++

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
                fePercent < ObelisksConstants.DECAY_PHASE_THRESHOLD && fePercent >= ObelisksConstants.CRITICAL_COLLAPSE_THRESHOLD -> {
                    // 10-90%: Decay phase - runs EVERY tick for smooth deletion
                    applyDecayEffects(runLevel, runData, fePercent)
                }
                fePercent < ObelisksConstants.CRITICAL_COLLAPSE_THRESHOLD && fePercent > 0.0 -> {
                    // 0-10%: Critical collapse phase - runs EVERY tick
                    applyCriticalCollapseEffects(runLevel, runData)
                }
                // else: 0% is handled by InstanceTickHandler force return
            }
        }
    }

    /**
     * Decay phase (10-30% FE): Dynamic deletion with target tracking.
     */
    private fun applyDecayEffects(level: ServerLevel, runData: RunData, fePercent: Double) {
        val players = level.players().filterIsInstance<net.minecraft.server.level.ServerPlayer>()
            .filter { it.uuid in runData.activePlayers }

        if (players.isEmpty()) return

        val runId = runData.runId

        // Initialize: count all blocks once at start
        if (!initialBlockCount.containsKey(runId)) {
            val count = countAllBlocksInLoadedChunks(level)
            initialBlockCount[runId] = count
            totalBlocksDeleted[runId] = 0
            deletionRate[runId] = ObelisksConstants.INITIAL_DELETION_RATE
        }

        val initial = initialBlockCount[runId] ?: return
        val deleted = totalBlocksDeleted[runId] ?: 0
        val rate = deletionRate[runId] ?: ObelisksConstants.INITIAL_DELETION_RATE

        // Target: 90% of blocks * stability percentage * 2
        // At 30% FE: target = 90% * 0.30 * 2 = 54% deleted
        // At 10% FE: target = 90% * 0.10 * 2 = 18% deleted (wait, this goes down?)
        // Let me invert: 90% * (1 - stability) for proper progression
        val targetDeletionPercent = ObelisksConstants.TARGET_DELETION_AT_ZERO_FE * (1.0 - fePercent)
        val targetDeleted = (initial * targetDeletionPercent).toInt()

        // Compare current vs target
        val difference = targetDeleted - deleted

        // Adjust deletion rate dynamically
        val newRate = when {
            difference > ObelisksConstants.DELETION_DIFF_HUGE -> (rate * ObelisksConstants.RATE_INCREASE_HUGE).toInt().coerceIn(ObelisksConstants.INITIAL_DELETION_RATE, ObelisksConstants.MAX_DELETION_RATE) // way behind, increase
            difference > ObelisksConstants.DELETION_DIFF_LARGE -> (rate * ObelisksConstants.RATE_INCREASE_LARGE).toInt().coerceIn(ObelisksConstants.INITIAL_DELETION_RATE, ObelisksConstants.MAX_DELETION_RATE)  // behind, increase
            difference > ObelisksConstants.DELETION_DIFF_MEDIUM -> rate // roughly on target
            difference > ObelisksConstants.DELETION_DIFF_SMALL -> (rate * ObelisksConstants.RATE_DECREASE_SMALL).toInt().coerceIn(ObelisksConstants.MIN_DELETION_RATE, ObelisksConstants.MAX_DELETION_RATE)  // slightly ahead, decrease
            else -> (rate * ObelisksConstants.RATE_DECREASE_LARGE).toInt().coerceIn(ObelisksConstants.MIN_DELETION_RATE, ObelisksConstants.MAX_DELETION_RATE)                // way ahead, decrease
        }
        deletionRate[runId] = newRate

        // Delete blocks and track what was actually deleted
        val actualDeleted = deleteRandomLoadedBlocks(level, runData, newRate, isDecayPhase = true)
        totalBlocksDeleted[runId] = deleted + actualDeleted

        // Delete some columns - guarantee at least 1 near each player every second
        val baseColumns = if (tickCounter % ObelisksConstants.COLLAPSE_LOG_INTERVAL == 0) players.size * ObelisksConstants.DECAY_GUARANTEED_COLUMNS_PER_PLAYER else 0
        val columnsDeleted = deleteRandomColumns(level, runData, baseColumns + Random.nextInt(ObelisksConstants.DECAY_EXTRA_COLUMNS_MIN, ObelisksConstants.DECAY_EXTRA_COLUMNS_MAX))
        totalBlocksDeleted[runId] = (totalBlocksDeleted[runId] ?: 0) + columnsDeleted

        if (tickCounter % ObelisksConstants.COLLAPSE_LOG_INTERVAL == 0) { // Log every second
            val currentPercent = ((deleted.toDouble() / initial) * 100).toInt()
            val targetPercent = (targetDeletionPercent * 100).toInt()
        }
    }

    /**
     * Critical collapse phase (0-10% FE): Aggressive dynamic deletion.
     */
    private fun applyCriticalCollapseEffects(level: ServerLevel, runData: RunData) {
        val players = level.players().filterIsInstance<net.minecraft.server.level.ServerPlayer>()
            .filter { it.uuid in runData.activePlayers }

        if (players.isEmpty()) return

        // Get current FE from obelisk
        val server = players.firstOrNull()?.server ?: return
        val originLevel = server.getLevel(runData.originDimension) ?: return
        val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? ObeliskBlockEntity ?: return
        val fePercent = obeliskBE.getEnergyPercent()

        val runId = runData.runId

        // Use same tracking as decay phase
        if (!initialBlockCount.containsKey(runId)) {
            val count = countAllBlocksInLoadedChunks(level)
            initialBlockCount[runId] = count
            totalBlocksDeleted[runId] = 0
            deletionRate[runId] = ObelisksConstants.INITIAL_CRITICAL_DELETION_RATE
        }

        val initial = initialBlockCount[runId] ?: return
        val deleted = totalBlocksDeleted[runId] ?: 0
        val rate = deletionRate[runId] ?: ObelisksConstants.INITIAL_CRITICAL_DELETION_RATE

        // Target: 90% * (1 - stability)
        val targetDeletionPercent = ObelisksConstants.TARGET_DELETION_AT_ZERO_FE * (1.0 - fePercent)
        val targetDeleted = (initial * targetDeletionPercent).toInt()
        val difference = targetDeleted - deleted

        // More aggressive rate adjustment in critical phase
        val newRate = when {
            difference > ObelisksConstants.DELETION_DIFF_HUGE -> (rate * ObelisksConstants.RATE_INCREASE_CRITICAL_HUGE).toInt().coerceIn(ObelisksConstants.INITIAL_CRITICAL_DELETION_RATE, ObelisksConstants.MAX_CRITICAL_DELETION_RATE)
            difference > ObelisksConstants.DELETION_DIFF_LARGE -> (rate * ObelisksConstants.RATE_INCREASE_CRITICAL_LARGE).toInt().coerceIn(ObelisksConstants.INITIAL_CRITICAL_DELETION_RATE, ObelisksConstants.MAX_CRITICAL_DELETION_RATE)
            difference > ObelisksConstants.DELETION_DIFF_MEDIUM -> (rate * ObelisksConstants.RATE_INCREASE_CRITICAL_MEDIUM).toInt().coerceIn(ObelisksConstants.INITIAL_CRITICAL_DELETION_RATE, ObelisksConstants.MAX_CRITICAL_DELETION_RATE)
            difference > ObelisksConstants.DELETION_DIFF_SMALL -> rate
            else -> (rate * ObelisksConstants.RATE_DECREASE_CRITICAL).toInt().coerceIn(ObelisksConstants.MIN_CRITICAL_DELETION_RATE, ObelisksConstants.MAX_CRITICAL_DELETION_RATE)
        }
        deletionRate[runId] = newRate

        val actualDeleted = deleteRandomLoadedBlocks(level, runData, newRate, isDecayPhase = false)
        totalBlocksDeleted[runId] = deleted + actualDeleted

        // More columns in critical - guarantee at least 2 near each player every second
        val baseColumns = if (tickCounter % ObelisksConstants.COLLAPSE_LOG_INTERVAL == 0) players.size * ObelisksConstants.CRITICAL_GUARANTEED_COLUMNS_PER_PLAYER else 0
        val columnsDeleted = deleteRandomColumns(level, runData, baseColumns + Random.nextInt(ObelisksConstants.CRITICAL_EXTRA_COLUMNS_MIN, ObelisksConstants.CRITICAL_EXTRA_COLUMNS_MAX))
        totalBlocksDeleted[runId] = (totalBlocksDeleted[runId] ?: 0) + columnsDeleted

        if (tickCounter % ObelisksConstants.COLLAPSE_LOG_INTERVAL == 0) {
            val currentPercent = ((deleted.toDouble() / initial) * 100).toInt()
            val targetPercent = (targetDeletionPercent * 100).toInt()
        }
    }

    /**
     * Safety check: ensures we're only modifying blocks in the run dimension.
     */
    private fun isInRunDimension(level: ServerLevel, runData: RunData): Boolean {
        return level.dimension() == runData.runDimensionKey
    }

    /**
     * Cleanup tracking data when a run ends.
     */
    fun cleanupRun(runId: Long) {
        initialBlockCount.remove(runId)
        totalBlocksDeleted.remove(runId)
        deletionRate.remove(runId)
    }

    /**
     * Clears all collapse states. Called on server startup to clean up stale data.
     */
    fun clearAllStates() {
        initialBlockCount.clear()
        totalBlocksDeleted.clear()
        deletionRate.clear()
    }

    /**
     * Counts all solid blocks in loaded chunks (ONE time at start).
     */
    private fun countAllBlocksInLoadedChunks(level: ServerLevel): Int {
        var count = 0
        val players = level.players()

        // Get all loaded chunks around players
        for (player in players) {
            val playerPos = player.blockPosition()
            val chunkX = playerPos.x shr 4
            val chunkZ = playerPos.z shr 4

            // Count in chunks around player (view distance = 10 chunks)
            for (dx in -ObelisksConstants.BLOCK_COUNT_VIEW_DISTANCE..ObelisksConstants.BLOCK_COUNT_VIEW_DISTANCE) {
                for (dz in -ObelisksConstants.BLOCK_COUNT_VIEW_DISTANCE..ObelisksConstants.BLOCK_COUNT_VIEW_DISTANCE) {
                    val chunk = level.getChunk(chunkX + dx, chunkZ + dz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false)
                    if (chunk is net.minecraft.world.level.chunk.LevelChunk) {
                        // Count non-air sections
                        for (section in chunk.sections) {
                            if (!section.hasOnlyAir()) {
                                // Rough estimate: 16x16x16 = 4096 blocks per section, assume 50% solid
                                count += ObelisksConstants.ESTIMATED_BLOCKS_PER_SECTION
                            }
                        }
                    }
                }
            }
        }

        return count.coerceAtLeast(ObelisksConstants.MIN_ESTIMATED_BLOCKS) // minimum estimate
    }


    /**
     * Deletes random blocks. Super simple approach.
     */
    private fun deleteRandomLoadedBlocks(level: ServerLevel, runData: RunData, count: Int, @Suppress("UNUSED_PARAMETER") isDecayPhase: Boolean): Int {
        if (!isInRunDimension(level, runData)) return 0

        val players = level.players()
        if (players.isEmpty()) return 0

        var deleted = 0
        var attempts = 0
        val maxAttempts = count * 2

        while (deleted < count && attempts < maxAttempts) {
            attempts++

            // Pick random player and area around them
            val player = players.random()
            val playerPos = player.blockPosition()

            // Random position within 128 blocks of player
            val offsetX = Random.nextInt(-ObelisksConstants.RANDOM_DELETION_RADIUS, ObelisksConstants.RANDOM_DELETION_RADIUS + 1)
            val offsetZ = Random.nextInt(-ObelisksConstants.RANDOM_DELETION_RADIUS, ObelisksConstants.RANDOM_DELETION_RADIUS + 1)
            val offsetY = Random.nextInt(ObelisksConstants.RANDOM_DELETION_Y_MIN, ObelisksConstants.RANDOM_DELETION_Y_MAX)

            val targetPos = playerPos.offset(offsetX, offsetY, offsetZ)

            // Make sure position is valid
            if (targetPos.y < level.minBuildHeight || targetPos.y > level.maxBuildHeight) continue

            val currentBlock = level.getBlockState(targetPos).block

            if (currentBlock != Blocks.AIR &&
                currentBlock != Blocks.BEDROCK &&
                currentBlock != Blocks.BARRIER &&
                currentBlock != Blocks.WATER &&
                currentBlock != Blocks.LAVA &&
                currentBlock != Blocks.OBSIDIAN &&
                currentBlock != Blocks.CRYING_OBSIDIAN &&
                currentBlock != dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get() &&
                !currentBlock.defaultBlockState().isAir) {

                level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)
                deleted++

                // Occasional particle/sound
                if (deleted % ObelisksConstants.DELETION_PARTICLE_INTERVAL == 0) {
                    if (dev.yourname.obelisks.util.EffectLimiter.trySpawnParticles(1)) {
                        level.sendParticles(
                            ParticleTypes.SMOKE,
                            targetPos.x + 0.5,
                            targetPos.y + 0.5,
                            targetPos.z + 0.5,
                            1,
                            0.1, 0.1, 0.1,
                            0.01
                        )
                    }
                }

                // Block break sound chance
                if (ObelisksConstants.COLLAPSE_BLOCK_BREAK_SOUND_ENABLED &&
                    Random.nextDouble() < ObelisksConstants.COLLAPSE_BLOCK_BREAK_SOUND_CHANCE) {
                    if (dev.yourname.obelisks.util.EffectLimiter.tryPlaySound()) {
                        level.playSound(
                            null,
                            targetPos,
                            currentBlock.defaultBlockState().getSoundType(level, targetPos, null).breakSound,
                            net.minecraft.sounds.SoundSource.BLOCKS,
                            0.5f,
                            0.8f + Random.nextFloat() * 0.4f
                        )
                    }
                }
            }
        }

        return deleted
    }

    /**
     * Deletes multiple random columns and returns total solid blocks deleted.
     */
    private fun deleteRandomColumns(level: ServerLevel, runData: RunData, count: Int): Int {
        if (!isInRunDimension(level, runData)) return 0

        val players = level.players()
        if (players.isEmpty()) return 0

        var totalDeleted = 0

        repeat(count) {
            val player = players.random()
            val playerPos = player.blockPosition()

            // Random column within 32 blocks (closer to player for visibility)
            val offsetX = Random.nextInt(-ObelisksConstants.COLUMN_PLACEMENT_RADIUS, ObelisksConstants.COLUMN_PLACEMENT_RADIUS + 1)
            val offsetZ = Random.nextInt(-ObelisksConstants.COLUMN_PLACEMENT_RADIUS, ObelisksConstants.COLUMN_PLACEMENT_RADIUS + 1)

            val x = playerPos.x + offsetX
            val z = playerPos.z + offsetZ

            // Delete 2x2 column and count solid blocks
            var blocksDeleted = 0
            for (dx in 0..1) {
                for (dz in 0..1) {
                    for (y in level.minBuildHeight..level.maxBuildHeight) {
                        val targetPos = BlockPos(x + dx, y, z + dz)
                        val currentBlock = level.getBlockState(targetPos).block

                        if (currentBlock != Blocks.BARRIER &&
                            currentBlock != Blocks.AIR &&
                            currentBlock != Blocks.WATER &&
                            currentBlock != Blocks.LAVA &&
                            currentBlock != Blocks.OBSIDIAN &&
                            currentBlock != Blocks.CRYING_OBSIDIAN &&
                            currentBlock != dev.yourname.obelisks.registry.ModBlocks.RETURN_PAD.get()) {

                            // Seal adjacent fluids before removing block
                            sealAdjacentFluids(level, targetPos)

                            level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), 3)
                            blocksDeleted++
                        }
                    }
                }
            }

            if (blocksDeleted > 0) {
                totalDeleted += blocksDeleted

                // No sound for column deletion - too annoying

                // Smoke particles at each corner of 2x2 column
                for (y in level.minBuildHeight..level.maxBuildHeight step ObelisksConstants.COLUMN_PARTICLE_STEP) {
                    for (dx in 0..1) {
                        for (dz in 0..1) {
                            if (dev.yourname.obelisks.util.EffectLimiter.trySpawnParticles(5)) {
                                level.sendParticles(
                                    ParticleTypes.LARGE_SMOKE,
                                    x + dx + 0.5,
                                    y.toDouble(),
                                    z + dz + 0.5,
                                    5,
                                    0.2, 0.2, 0.2,
                                    0.1
                                )
                            }
                        }
                    }
                }
            }
        }

        return totalDeleted
    }

    /**
     * Seals adjacent fluids before removing a block during collapse.
     * Uses cobblestone for simple, fast sealing.
     */
    private fun sealAdjacentFluids(level: ServerLevel, pos: BlockPos) {
        val directions = arrayOf(
            net.minecraft.core.Direction.NORTH,
            net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST,
            net.minecraft.core.Direction.WEST,
            net.minecraft.core.Direction.UP,
            net.minecraft.core.Direction.DOWN
        )

        // Check and seal adjacent fluids with cobblestone
        for (dir in directions) {
            val adjacentPos = pos.relative(dir)
            val adjacentState = level.getBlockState(adjacentPos)

            // Only seal water and lava source blocks
            if (!adjacentState.fluidState.isEmpty && adjacentState.fluidState.isSource) {
                val block = adjacentState.block
                if (block == Blocks.WATER || block == Blocks.LAVA) {
                    // Use cobblestone for sealing
                    level.setBlock(adjacentPos, Blocks.COBBLESTONE.defaultBlockState(), 3)
                }
            }
        }
    }
}
