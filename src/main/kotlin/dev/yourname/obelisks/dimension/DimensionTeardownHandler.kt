package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.jaunt.RunData
import dev.yourname.obelisks.jaunt.RunManager
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Handles safe teardown of run dimensions when they become empty.
 */
object DimensionTeardownHandler {

    // Map of dimension keys pending cleanup -> tick count
    private val pendingCleanup = mutableMapOf<ResourceKey<Level>, Int>() // 5 seconds

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) return

        val server = event.server
        val runManager = RunManager.get(server)

        // Check for empty runs
        val emptyRuns = runManager.getEmptyRuns()
        for (runData in emptyRuns) {
            val dimKey = runData.runDimensionKey

            // Skip if already pending cleanup
            if (pendingCleanup.containsKey(dimKey)) {
                pendingCleanup[dimKey] = pendingCleanup[dimKey]!! + 1
            } else {
                // Start cleanup timer
                pendingCleanup[dimKey] = 0
            }
        }

        // Process pending cleanups
        val toCleanup = mutableListOf<ResourceKey<Level>>()
        pendingCleanup.forEach { (dimKey, ticks) ->
            if (ticks >= ObelisksConstants.RUN_CLEANUP_DELAY_TICKS) {
                toCleanup.add(dimKey)
            }
        }

        // Execute cleanups
        for (dimKey in toCleanup) {
            val runData = runManager.getRunByDimension(dimKey)
            if (runData != null) {
                cleanupRunDimension(runData, server)
                DimensionCollapseHandler.cleanupRun(runData.runId)
                runManager.endRun(runData.obeliskId, runData.runId)
            }
            pendingCleanup.remove(dimKey)
        }
    }

    @SubscribeEvent
    fun onPlayerChangeDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        // When a player leaves a run dimension, check if it's now empty
        val player = event.entity
        if (player.level().isClientSide) return

        val server = player.server ?: return
        val runManager = RunManager.get(server)

        // Check if the dimension they left is a run dimension
        val leftDim = event.from
        val runData = runManager.getRunByDimension(leftDim) ?: return

        // If dimension is now empty, immediately end the run (don't wait for cleanup delay)
        // This ensures re-entering creates a fresh run instead of rejoining the old one
        if (runData.activePlayers.isEmpty()) {
            cleanupRunDimension(runData, server)
            DimensionCollapseHandler.cleanupRun(runData.runId)
            runManager.endRun(runData.obeliskId, runData.runId)
            pendingCleanup.remove(leftDim)
        }
    }

    /**
     * Cleans up a run: releases the slot so it can be reused by another obelisk.
     * Also resets the origin obelisk state (refills FE, clears active run).
     */
    private fun cleanupRunDimension(runData: RunData, server: MinecraftServer) {
        val dimKey = runData.runDimensionKey
        val modId = ObelisksConstants.MOD_ID


        // Reset origin obelisk state and spawn emerald rewards
        val originLevel = server.getLevel(runData.originDimension)
        if (originLevel != null) {
            val obeliskBE = originLevel.getBlockEntity(runData.originObeliskPos) as? dev.yourname.obelisks.content.ObeliskBlockEntity
            if (obeliskBE != null) {
                // Spawn emerald rewards based on monsters killed
                spawnEmeraldRewards(originLevel, runData.originObeliskPos, runData.monstersKilled)

                // Clear active run ID and start cooldown
                obeliskBE.activeRunId = null
                obeliskBE.startCooldown(ObelisksConstants.RUN_CLEANUP_DELAY_TICKS)
                obeliskBE.setChanged()
            } else {
            }
        }

        // Release the coordinate assignment
        RunCoordinateManager.releaseRun(runData.obeliskId, runData.runId)

        // Note: With direct dimension teleport, we don't need to clean up anything
        // The platforms stay in the actual dimension at their random coordinates (far from spawn)
    }

    /**
     * Spawns loot rewards at the obelisk based on monsters killed during the run.
     * Uses configurable loot tables for flexible reward systems.
     */
    private fun spawnEmeraldRewards(level: net.minecraft.server.level.ServerLevel, pos: BlockPos, monstersKilled: Int) {
        if (monstersKilled == 0) return

        // Generate loot for each kill using loot table
        val allLoot = mutableListOf<net.minecraft.world.item.ItemStack>()
        repeat(monstersKilled) {
            val loot = dev.yourname.obelisks.config.LootGenerator.generateLootForKill()
            allLoot.addAll(loot)
        }

        if (allLoot.isEmpty()) {
            return
        }

        // Consolidate identical items
        val consolidatedLoot = mutableMapOf<net.minecraft.world.item.Item, Int>()
        allLoot.forEach { stack ->
            consolidatedLoot[stack.item] = (consolidatedLoot[stack.item] ?: 0) + stack.count
        }

        // Spawn loot as item entities
        val spawnPos = pos.above() // Spawn above the obelisk
        consolidatedLoot.forEach { (item, count) ->
            val stack = net.minecraft.world.item.ItemStack(item, count)
            val itemEntity = net.minecraft.world.entity.item.ItemEntity(
                level,
                spawnPos.x + 0.5,
                spawnPos.y + 0.5,
                spawnPos.z + 0.5,
                stack
            )

            // Add upward velocity for dramatic effect
            itemEntity.deltaMovement = itemEntity.deltaMovement.add(0.0, 0.3, 0.0)
            level.addFreshEntity(itemEntity)
        }

        // Spawn particles
        if (dev.yourname.obelisks.util.EffectLimiter.trySpawnParticles(20)) {
            level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                spawnPos.x + 0.5,
                spawnPos.y + 1.0,
                spawnPos.z + 0.5,
                20,
                0.3, 0.3, 0.3,
                0.1
            )
        }

        // Play success sound
        if (dev.yourname.obelisks.util.EffectLimiter.tryPlaySound()) {
            level.playSound(
                null as net.minecraft.world.entity.player.Player?,
                spawnPos,
                net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP,
                net.minecraft.sounds.SoundSource.BLOCKS,
                1.0f,
                1.0f
            )
        }

        val lootSummary = consolidatedLoot.entries.joinToString(", ") { (item, count) ->
            "$count x ${item.description.string}"
        }
    }

    /**
     * Cancel cleanup if a player rejoins an empty dimension.
     */
    fun cancelCleanup(dimensionKey: ResourceKey<Level>) {
        pendingCleanup.remove(dimensionKey)
    }
}
