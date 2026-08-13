package com.bettercontent.dimensiondrink.runtime.reward

import com.mojang.logging.LogUtils
import com.bettercontent.dimensiondrink.content.ObeliskBlockEntity
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.data.KillCurrencyDefinition
import com.bettercontent.dimensiondrink.data.RewardEntryDefinition
import com.bettercontent.dimensiondrink.data.RewardPoolDefinition
import com.bettercontent.dimensiondrink.data.RewardTableDefinition
import com.bettercontent.dimensiondrink.runtime.run.RunRecord
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.floor

object RewardSystem {
    private val logger = LogUtils.getLogger()

    private const val LEGACY_CURRENCY_POOL_ID = "emeralds"
    private const val KILL_CURRENCY_EJECTION_DURATION_TICKS = 20

    private val pendingCurrencyEjections = mutableListOf<ScheduledCurrencyEjection>()

    fun spawnRewards(server: MinecraftServer, run: RunRecord): Boolean {
        if (run.rewardsGranted) return false

        val originLevelKey = run.originLevelKey ?: return false
        val originPos = run.originObeliskPos ?: return false
        val level = server.getLevel(originLevelKey) ?: return false
        val obelisk = level.getBlockEntity(originPos) as? ObeliskBlockEntity ?: return false
        val rewardTableId = ObeliskDataManager.getObelisk(run.definitionId)?.rewardTableId ?: "default"
        val rewardTable = ObeliskDataManager.getRewardTable(rewardTableId)
        val survivorSnapshot = run.survivors.toList()
        val disqualifiedSnapshot = run.disqualifiedPlayers.toSet()
        val recipients = survivorSnapshot
            .filterNot { it in disqualifiedSnapshot }
            .mapNotNull { server.playerList.getPlayer(it) }
        if (recipients.isEmpty()) {
            return false
        }

        var deliveredAny = false
        recipients.forEach { player ->
            val rewards = buildRewards(run, rewardTable).toMutableList()
            val currencyRewards = buildKillCurrencyRewards(run, rewardTable)
            (rewards + currencyRewards).forEach { stack ->
                deliverToPlayerOrFont(level, originPos, obelisk, player, stack)
                deliveredAny = true
            }
        }
        if (!deliveredAny) return false

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

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || pendingCurrencyEjections.isEmpty()) {
            return
        }

        val iterator = pendingCurrencyEjections.iterator()
        while (iterator.hasNext()) {
            val scheduled = iterator.next()
            if (scheduled.remainingDelay > 0) {
                scheduled.remainingDelay--
                continue
            }

            val level = event.server.getLevel(scheduled.levelKey)
            if (level != null) {
                ejectCurrency(level, scheduled.originPos, scheduled.stack)
            }
            iterator.remove()
        }
    }

    private fun buildRewards(run: RunRecord, table: RewardTableDefinition?): List<ItemStack> {
        table ?: return emptyList()
        if (!table.enabled) return emptyList()
        val totalRolls = table.baseRolls +
            (run.monstersKilled * table.rollsPerKill) +
            (run.totalDamageDealt / table.damagePerBonusRoll).toInt()
        if (totalRolls <= 0) return emptyList()
        val rewards = mutableListOf<ItemStack>()
        repeat(totalRolls) {
            table.pools.forEach { pool ->
                if (pool.id == LEGACY_CURRENCY_POOL_ID) return@forEach
                if (pool.entries.isEmpty()) return@forEach
                if (Math.random() > pool.chance) return@forEach
                rollPool(pool)?.let(rewards::add)
            }
        }
        return rewards
    }

    private fun buildKillCurrencyRewards(run: RunRecord, table: RewardTableDefinition?): List<ItemStack> {
        if (run.monstersKilled <= 0) return emptyList()
        val killCurrency = table?.killCurrency ?: return emptyList()
        val item = resolveCurrencyItem(killCurrency) ?: return emptyList()
        return fixedCurrencyStacks(item, run.monstersKilled * killCurrency.perKill, killCurrency.burstSize)
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
        val item = ForgeRegistries.ITEMS.getValue(itemId)?.takeUnless { it == Items.AIR } ?: return null
        val count = if (entry.maxCount <= entry.minCount) entry.minCount else kotlin.random.Random.nextInt(entry.minCount, entry.maxCount + 1)
        return ItemStack(item, count.coerceAtLeast(1))
    }

    private fun resolveCurrencyItem(currency: KillCurrencyDefinition): Item? {
        val itemId = ResourceLocation.tryParse(currency.item)
        if (itemId == null) {
            logger.warn("Invalid killCurrency item id '{}'; skipping kill-currency rewards", currency.item)
            return null
        }
        val item = ForgeRegistries.ITEMS.getValue(itemId)
        if (item == null || item == Items.AIR) {
            logger.warn("Missing killCurrency item '{}'; skipping kill-currency rewards", currency.item)
            return null
        }
        return item
    }

    private fun fixedCurrencyStacks(item: Item, count: Int, burstSize: Int): List<ItemStack> {
        val stackSize = burstSize.coerceAtMost(item.defaultInstance.maxStackSize).coerceAtLeast(1)
        val stacks = mutableListOf<ItemStack>()
        var remaining = count
        while (remaining > 0) {
            val emitted = remaining.coerceAtMost(stackSize)
            stacks += ItemStack(item, emitted)
            remaining -= emitted
        }
        return stacks
    }

    private fun scheduleCurrencyEjections(level: ServerLevel, pos: BlockPos, stacks: List<ItemStack>) {
        if (stacks.isEmpty()) return
        val burstCount = stacks.size
        logger.info(
            "Scheduling kill currency ejection at {} in {} item={} total={} bursts={}",
            pos,
            level.dimension().location(),
            ForgeRegistries.ITEMS.getKey(stacks.first().item),
            stacks.sumOf { it.count },
            burstCount
        )
        stacks.forEachIndexed { index, stack ->
            val delay = if (burstCount == 1) {
                0
            } else {
                floor(index.toDouble() * (KILL_CURRENCY_EJECTION_DURATION_TICKS - 1).toDouble() / (burstCount - 1).toDouble()).toInt()
            }
            pendingCurrencyEjections += ScheduledCurrencyEjection(level.dimension(), pos.immutable(), stack.copy(), delay)
        }
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

    private fun deliverToPlayerOrFont(
        level: ServerLevel,
        pos: BlockPos,
        obelisk: ObeliskBlockEntity,
        player: net.minecraft.server.level.ServerPlayer,
        stack: ItemStack
    ) {
        val remaining = stack.copy()
        if (player.inventory.add(remaining) || remaining.isEmpty) {
            return
        }
        insertIntoBufferOrEject(level, pos, obelisk, remaining)
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

    private fun ejectCurrency(level: ServerLevel, pos: BlockPos, stack: ItemStack) {
        val entity = ItemEntity(
            level,
            pos.x + 0.5,
            pos.y + 1.05,
            pos.z + 0.5,
            stack.copy()
        )
        val random = level.random
        entity.deltaMovement = entity.deltaMovement.add(
            (random.nextDouble() - 0.5) * 0.08,
            0.34,
            (random.nextDouble() - 0.5) * 0.08
        )
        level.addFreshEntity(entity)
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.22f, 1.6f + random.nextFloat() * 0.25f)
    }

    private data class ScheduledCurrencyEjection(
        val levelKey: ResourceKey<Level>,
        val originPos: BlockPos,
        val stack: ItemStack,
        var remainingDelay: Int
    )
}
