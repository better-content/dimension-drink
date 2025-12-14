package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Loot table configuration for run completion rewards.
 */
data class LootTableConfig(
    val enabled: Boolean = true,
    val pools: List<LootPool> = emptyList()
)

data class LootPool(
    val name: String,
    val rolls: RollRange,
    val chance: Double = 1.0, // 0.0-1.0, chance for this pool to activate at all
    val entries: List<LootEntry>
)

data class RollRange(
    val min: Int,
    val max: Int
)

data class LootEntry(
    val type: String, // "item"
    val item: String, // "minecraft:emerald"
    val weight: Int = 1
)

/**
 * Generates loot based on loot table configuration.
 */
object LootGenerator {
    /**
     * Generate loot for a single monster kill.
     * Returns list of ItemStacks to spawn.
     */
    fun generateLootForKill(): List<ItemStack> {
        val table = dev.yourname.obelisks.config.ConfigManager.getLootTableConfig()
        if (!table.enabled) return emptyList()

        val loot = mutableListOf<ItemStack>()

        for (pool in table.pools) {
            // Check pool activation chance
            if (Random.nextDouble() > pool.chance) continue

            // Calculate number of rolls
            val rolls = if (pool.rolls.min == pool.rolls.max) {
                pool.rolls.min
            } else {
                Random.nextInt(pool.rolls.min, pool.rolls.max + 1)
            }

            // Roll for items
            repeat(rolls) {
                val selectedEntry = selectWeightedEntry(pool.entries) ?: return@repeat
                val item = getItemFromString(selectedEntry.item) ?: return@repeat
                loot.add(ItemStack(item, 1))
            }
        }

        return loot
    }

    private fun selectWeightedEntry(entries: List<LootEntry>): LootEntry? {
        if (entries.isEmpty()) return null

        val totalWeight = entries.sumOf { it.weight }
        var roll = Random.nextInt(totalWeight)

        for (entry in entries) {
            roll -= entry.weight
            if (roll < 0) {
                return entry
            }
        }

        return entries.last()
    }

    private fun getItemFromString(itemId: String): Item? {
        return try {
            val location = ResourceLocation(itemId)
            BuiltInRegistries.ITEM.get(location)
        } catch (e: Exception) {
            null
        }
    }
}
