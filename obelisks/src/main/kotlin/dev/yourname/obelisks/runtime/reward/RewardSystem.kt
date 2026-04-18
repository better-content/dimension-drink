package dev.yourname.obelisks.runtime.reward

import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.RewardEntryDefinition
import dev.yourname.obelisks.data.RewardPoolDefinition
import dev.yourname.obelisks.data.RewardTableDefinition
import dev.yourname.obelisks.runtime.run.RunRecord
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.common.capabilities.ForgeCapabilities

object RewardSystem {

    fun spawnRewards(server: MinecraftServer, run: RunRecord): Boolean {
        if (run.rewardsGranted) return false
        if (run.totalDamageDealt <= 0f && run.monstersKilled <= 0) return false

        val originLevelKey = run.originLevelKey ?: return false
        val originPos = run.originObeliskPos ?: return false
        val level = server.getLevel(originLevelKey) ?: return false
        val obelisk = level.getBlockEntity(originPos) as? ObeliskBlockEntity ?: return false
        val rewardTableId = ObeliskDataManager.getObelisk(run.definitionId)?.rewardTableId ?: "default"
        val rewards = buildRewards(run, rewardTableId)
        if (rewards.isEmpty()) return false

        val hasAutomation = Direction.values().any { side ->
            val adjacent = level.getBlockEntity(originPos.relative(side))
            adjacent?.getCapability(ForgeCapabilities.ITEM_HANDLER, side.opposite)?.isPresent == true
        }

        rewards.forEach { stack ->
            if (hasAutomation) {
                insertIntoBufferOrEject(level, originPos, obelisk, stack)
            } else {
                eject(level, originPos, stack)
            }
        }

        level.playSound(null, originPos, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 0.5f, 1.3f)
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            originPos.x + 0.5,
            originPos.y + 1.0,
            originPos.z + 0.5,
            12,
            0.35,
            0.35,
            0.35,
            0.05
        )
        return true
    }

    private fun buildRewards(run: RunRecord, rewardTableId: String): List<ItemStack> {
        val table = ObeliskDataManager.getRewardTable(rewardTableId) ?: return emptyList()
        if (!table.enabled) return emptyList()
        val totalRolls = table.baseRolls +
            (run.monstersKilled * table.rollsPerKill) +
            (run.totalDamageDealt / table.damagePerBonusRoll).toInt()
        if (totalRolls <= 0) return emptyList()
        val rewards = mutableListOf<ItemStack>()
        repeat(totalRolls) {
            table.pools.forEach { pool ->
                if (pool.entries.isEmpty()) return@forEach
                if (Math.random() > pool.chance) return@forEach
                rollPool(pool)?.let(rewards::add)
            }
        }
        return rewards
    }

    private fun rollPool(pool: RewardPoolDefinition): ItemStack? {
        val entries = pool.entries.filter { it.weight > 0 }
        if (entries.isEmpty()) return null
        val totalWeight = entries.sumOf { it.weight }
        var cursor = kotlin.random.Random.nextInt(totalWeight)
        for (entry in entries) {
            cursor -= entry.weight
            if (cursor < 0) {
                return buildStack(entry)
            }
        }
        return buildStack(entries.last())
    }

    private fun buildStack(entry: RewardEntryDefinition): ItemStack? {
        val itemId = ResourceLocation.tryParse(entry.item) ?: return null
        val item = BuiltInRegistries.ITEM.get(itemId)
        if (item == Items.AIR) return null
        val count = if (entry.maxCount <= entry.minCount) entry.minCount else kotlin.random.Random.nextInt(entry.minCount, entry.maxCount + 1)
        return ItemStack(item, count.coerceAtLeast(1))
    }

    private fun insertIntoBufferOrEject(level: ServerLevel, pos: BlockPos, obelisk: ObeliskBlockEntity, stack: ItemStack) {
        var remaining = stack.copy()
        val itemHandler = obelisk.getInternalItemHandler()
        for (slot in 0 until itemHandler.slots) {
            if (remaining.isEmpty) break
            remaining = itemHandler.insertItem(slot, remaining, false)
        }
        if (!remaining.isEmpty) {
            eject(level, pos, remaining)
        }
    }

    private fun eject(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        val entity = ItemEntity(
            level,
            pos.x + 0.5,
            pos.y + 1.25,
            pos.z + 0.5,
            stack.copy()
        )
        entity.deltaMovement = entity.deltaMovement.add(0.0, 0.25, 0.0)
        level.addFreshEntity(entity)
    }
}
