package dev.yourname.obelisks.util

import dev.yourname.obelisks.config.ConfigManager
import dev.yourname.obelisks.config.LootGenerator
import dev.yourname.obelisks.jaunt.RunData
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack

/**
 * Centralized reward spawning system used by both InstanceTickHandler and DimensionTeardownHandler.
 */
object RewardSystem {

    /**
     * Spawns loot rewards at the obelisk based on monsters killed during the run.
     * Uses configurable loot tables for flexible reward systems.
     */
    fun spawnRewards(level: ServerLevel, pos: BlockPos, runData: RunData) {
        val monstersKilled = runData.monstersKilled
        if (monstersKilled == 0) return

        // Get dimension config for loot table override
        val dimConfig = ConfigManager.getDimensionConfig(runData.dimensionId)

        // Generate loot for each kill using loot table (dimension-specific if configured)
        val allLoot = mutableListOf<ItemStack>()
        repeat(monstersKilled) {
            val loot = LootGenerator.generateLootForKill(level, dimConfig)
            allLoot.addAll(loot)
        }

        if (allLoot.isEmpty()) {
            return
        }

        // Check for adjacent inventories
        val adjacentInventory = findAdjacentInventory(level, pos)

        if (adjacentInventory != null) {
            // Export to adjacent inventory
            allLoot.forEach { stack ->
                val remaining = insertIntoInventory(adjacentInventory, stack)
                if (!remaining.isEmpty) {
                    // If inventory is full, eject the remainder
                    ejectItem(level, pos, remaining)
                }
                // Play jingley sound for each item
                playJingleSound(level, pos)
            }
        } else {
            // No adjacent inventory, eject all items
            allLoot.forEach { stack ->
                ejectItem(level, pos, stack)
                // Play jingley sound for each item
                playJingleSound(level, pos)
            }
        }

        // Spawn particles
        if (EffectLimiter.trySpawnParticles(20)) {
            level.sendParticles(
                net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                pos.x + 0.5,
                pos.y + 1.0,
                pos.z + 0.5,
                20,
                0.3, 0.3, 0.3,
                0.1
            )
        }
    }

    /**
     * Find an adjacent inventory (chest, barrel, etc.)
     */
    private fun findAdjacentInventory(level: ServerLevel, pos: BlockPos): Container? {
        val directions = arrayOf(
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP,
            Direction.DOWN
        )

        for (direction in directions) {
            val adjacentPos = pos.relative(direction)
            val blockEntity = level.getBlockEntity(adjacentPos)
            if (blockEntity is Container) {
                return blockEntity
            }
        }

        return null
    }

    /**
     * Insert item into inventory, return remainder if any
     */
    private fun insertIntoInventory(inventory: Container, stack: ItemStack): ItemStack {
        var remaining = stack.copy()

        for (i in 0 until inventory.containerSize) {
            if (remaining.isEmpty) break

            val slotStack = inventory.getItem(i)
            if (slotStack.isEmpty) {
                // Empty slot, insert all
                inventory.setItem(i, remaining)
                remaining = ItemStack.EMPTY
            } else if (ItemStack.isSameItemSameTags(slotStack, remaining)) {
                // Same item, try to merge
                val maxStack = slotStack.maxStackSize
                val canAdd = maxStack - slotStack.count
                if (canAdd > 0) {
                    val toAdd = minOf(canAdd, remaining.count)
                    slotStack.grow(toAdd)
                    remaining.shrink(toAdd)
                }
            }
        }

        return remaining
    }

    /**
     * Eject item into the world
     */
    private fun ejectItem(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        val spawnPos = pos.above()
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

    /**
     * Play a jingley sound when items are ejected/exported
     */
    private fun playJingleSound(level: ServerLevel, pos: BlockPos) {
        level.playSound(
            null,
            pos,
            SoundEvents.EXPERIENCE_ORB_PICKUP,
            SoundSource.BLOCKS,
            0.5f,
            1.5f + (Math.random() * 0.5f).toFloat()
        )
    }
}
