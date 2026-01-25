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
     * Spawns loot rewards at the obelisk based on damage dealt during the run.
     * Uses configurable loot tables for flexible reward systems.
     * Items go to internal buffer if automation connected, otherwise eject as entities.
     *
     * Reward scaling: 1 loot roll per 20 damage dealt (equivalent to 1 zombie kill).
     */
    fun spawnRewards(level: ServerLevel, pos: BlockPos, runData: RunData) {
        val damageDealt = runData.totalDamageDealt
        if (damageDealt <= 0f) return

        // Calculate loot rolls: 1 roll per 20 damage dealt (1 zombie = 20 HP)
        val lootRolls = (damageDealt / 20f).toInt().coerceAtLeast(0)
        if (lootRolls == 0) return

        // Get dimension config for loot table override
        val dimConfig = ConfigManager.getDimensionConfig(runData.dimensionId)

        // Generate loot for each 20 damage dealt using loot table (dimension-specific if configured)
        val allLoot = mutableListOf<ItemStack>()
        repeat(lootRolls) {
            val loot = LootGenerator.generateLootForKill(level, dimConfig)
            allLoot.addAll(loot)
        }

        if (allLoot.isEmpty()) {
            return
        }

        // Get obelisk block entity
        val obeliskBE = level.getBlockEntity(pos) as? dev.yourname.obelisks.content.ObeliskBlockEntity

        // Check if any automation (item handler) is connected to any side
        val hasAutomation = if (obeliskBE != null) {
            Direction.values().any { side ->
                val adjacentPos = pos.relative(side)
                val adjacentBE = level.getBlockEntity(adjacentPos)
                adjacentBE?.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side.opposite)?.isPresent == true
            }
        } else false

        if (hasAutomation && obeliskBE != null) {
            // Route to internal buffer for automation to extract
            val internalInventory = obeliskBE.getInternalItemHandler()
            allLoot.forEach { stack ->
                var remaining = stack.copy()

                // Try to insert into internal inventory
                for (i in 0 until internalInventory.slots) {
                    if (remaining.isEmpty) break
                    remaining = internalInventory.insertItem(i, remaining, false)
                }

                // If internal inventory is full, eject remainder
                if (!remaining.isEmpty) {
                    ejectItem(level, pos, remaining)
                }

                playJingleSound(level, pos)
            }
        } else {
            // No automation connected - eject all items directly as entities
            allLoot.forEach { stack ->
                ejectItem(level, pos, stack)
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
