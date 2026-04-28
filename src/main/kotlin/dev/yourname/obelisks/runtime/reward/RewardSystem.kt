package dev.yourname.obelisks.runtime.reward

import com.mojang.logging.LogUtils
import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.RewardEntryDefinition
import dev.yourname.obelisks.data.RewardPoolDefinition
import dev.yourname.obelisks.data.RewardTableDefinition
import dev.yourname.obelisks.runtime.run.RunRecord
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.floor

object RewardSystem {
    private val logger = LogUtils.getLogger()

    private const val DOT_COIN_NAMESPACE = "dotcoinmod"
    private val DOT_COIN_ITEM_IDS = listOf(
        "dotcoinmod:copper_coin",
        "dotcoinmod:iron_coin",
        "dotcoinmod:gold_coin",
        "dotcoinmod:platinum_coin",
        "dotcoinmod:tin_coin",
        "dotcoinmod:nickel_coin",
        "dotcoinmod:silver_coin",
        "dotcoinmod:steel_coin",
        "dotcoinmod:bronze_coin",
        "dotcoinmod:brass_coin",
        "dotcoinmod:osmium_coin",
        "dotcoinmod:diamond_coin",
        "dotcoinmod:emerald_coin",
        "dotcoinmod:ruby_coin",
        "dotcoinmod:sapphire_coin",
        "dotcoinmod:topaz_coin",
        "dotcoinmod:token"
    )
    private const val LEGACY_CURRENCY_POOL_ID = "emeralds"
    private const val KILL_CURRENCY_ITEMS_PER_EJECTION = 4
    private const val KILL_CURRENCY_EJECTION_DURATION_TICKS = 20

    private val pendingCurrencyEjections = mutableListOf<ScheduledCurrencyEjection>()

    fun spawnRewards(server: MinecraftServer, run: RunRecord): Boolean {
        if (run.rewardsGranted) return false

        val originLevelKey = run.originLevelKey ?: return false
        val originPos = run.originObeliskPos ?: return false
        val level = server.getLevel(originLevelKey) ?: return false
        val obelisk = level.getBlockEntity(originPos) as? ObeliskBlockEntity ?: return false
        val rewardTableId = ObeliskDataManager.getObelisk(run.definitionId)?.rewardTableId ?: "default"
        val rewards = buildRewards(run, rewardTableId)
        val currencyRewards = buildKillCurrencyRewards(run)
        if (rewards.isEmpty() && currencyRewards.isEmpty()) return false

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
        scheduleCurrencyEjections(level, originPos, currencyRewards)

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
                if (pool.id == LEGACY_CURRENCY_POOL_ID) return@forEach
                if (pool.entries.isEmpty()) return@forEach
                if (Math.random() > pool.chance) return@forEach
                rollPool(pool)?.let(rewards::add)
            }
        }
        return rewards
    }

    private fun buildKillCurrencyRewards(run: RunRecord): List<ItemStack> {
        if (run.monstersKilled <= 0) return emptyList()
        return fixedCurrencyStacks(resolveKillCurrencyItem(), run.monstersKilled)
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

    private fun resolveKillCurrencyItem(): Item {
        for (itemId in DOT_COIN_ITEM_IDS) {
            val coinId = ResourceLocation.tryParse(itemId) ?: continue
            val coin = ForgeRegistries.ITEMS.getValue(coinId)
            if (coin != null && coin != Items.AIR) return coin
        }

        val fallbackDotCoinItemId = ForgeRegistries.ITEMS.keys
            .asSequence()
            .filter { it.namespace == DOT_COIN_NAMESPACE }
            .filter { it.path.endsWith("_coin") || it.path == "token" }
            .sortedBy { it.path }
            .firstOrNull()
        if (fallbackDotCoinItemId != null) {
            val fallbackItem = ForgeRegistries.ITEMS.getValue(fallbackDotCoinItemId)
            if (fallbackItem != null && fallbackItem != Items.AIR) return fallbackItem
        }

        logger.warn("No DotCoin currency item found in item registry; falling back to emerald rewards")
        return Items.EMERALD
    }

    private fun fixedCurrencyStacks(item: Item, count: Int): List<ItemStack> {
        val stackSize = KILL_CURRENCY_ITEMS_PER_EJECTION.coerceAtMost(item.defaultInstance.maxStackSize).coerceAtLeast(1)
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
