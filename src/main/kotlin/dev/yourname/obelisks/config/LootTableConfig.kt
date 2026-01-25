package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraftforge.fml.ModList
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
 * Generates loot based on vanilla loot tables.
 */
object LootGenerator {
    /**
     * Generate loot for a single monster kill using vanilla loot tables.
     * Returns list of ItemStacks to spawn.
     *
     * @param level The server level
     * @param dimensionConfig Optional dimension config to use dimension-specific loot tables
     */
    fun generateLootForKill(level: ServerLevel, dimensionConfig: DimensionConfig? = null): List<ItemStack> {
        val loot = mutableListOf<ItemStack>()

        // Determine which loot tables to use (dimension-specific or default)
        val basicLootTableId = dimensionConfig?.lootTable ?: "obelisks:obelisk_kill_reward"
        val equipmentLootTableId = dimensionConfig?.equipmentLootTable ?: "obelisks:obelisk_equipment_reward"

        // Roll basic rewards loot table (emeralds, materials, etc.)
        val basicRewardsTable = level.server.lootData.getLootTable(
            ResourceLocation(basicLootTableId)
        )
        val basicParams = LootParams.Builder(level).create(LootContextParamSets.EMPTY)
        loot.addAll(basicRewardsTable.getRandomItems(basicParams))

        // Roll equipment loot table
        val equipmentTable = level.server.lootData.getLootTable(
            ResourceLocation(equipmentLootTableId)
        )
        val equipmentParams = LootParams.Builder(level).create(LootContextParamSets.EMPTY)
        val equipmentDrops = equipmentTable.getRandomItems(equipmentParams)

        // Apply Apotheosis affixes to all equipment drops
        for (equipmentStack in equipmentDrops) {
            val tier = getTierFromNBT(equipmentStack)
            val affixedStack = tryApplyApotheosisAffixes(equipmentStack, tier)
            loot.add(affixedStack)
        }

        return loot
    }

    /**
     * Extract tier from the obelisks_tier NBT tag set by the loot table.
     */
    private fun getTierFromNBT(stack: ItemStack): EquipmentTier {
        val tag = stack.tag ?: return EquipmentTier.COMMON
        val tierString = tag.getString("obelisks_tier")
        return when (tierString.lowercase()) {
            "common" -> EquipmentTier.COMMON
            "uncommon" -> EquipmentTier.UNCOMMON
            "rare" -> EquipmentTier.RARE
            "epic" -> EquipmentTier.EPIC
            "legendary" -> EquipmentTier.LEGENDARY
            else -> EquipmentTier.COMMON
        }
    }

    /**
     * Applies Apotheosis affixes if the mod is loaded.
     * Uses reflection since Apotheosis is an optional dependency.
     */
    private fun tryApplyApotheosisAffixes(stack: ItemStack, tier: EquipmentTier): ItemStack {
        // Check if Apotheosis is loaded
        if (!ModList.get().isLoaded("apotheosis")) {
            return stack
        }

        try {
            // Map tier to Apotheosis rarity ID
            val rarityId = when (tier) {
                EquipmentTier.COMMON -> "common"
                EquipmentTier.UNCOMMON -> "uncommon"
                EquipmentTier.RARE -> "rare"
                EquipmentTier.EPIC -> "epic"
                EquipmentTier.LEGENDARY -> "mythic"
            }

            // Get LootRarity enum via reflection
            val rarityClass = Class.forName("dev.shadowsoffire.apotheosis.adventure.loot.LootRarity")
            val byIdMethod = rarityClass.getMethod("byId", String::class.java)
            val rarity = byIdMethod.invoke(null, rarityId)

            // Call randomAffixes method
            val randomAffixesMethod = rarityClass.getMethod("randomAffixes",
                ItemStack::class.java,
                RandomSource::class.java)
            randomAffixesMethod.invoke(rarity, stack, RandomSource.create())

            return stack
        } catch (e: Exception) {
            // Apotheosis not installed or API changed, return vanilla item
            return stack
        }
    }

    private enum class EquipmentTier {
        COMMON, UNCOMMON, RARE, EPIC, LEGENDARY
    }

}
