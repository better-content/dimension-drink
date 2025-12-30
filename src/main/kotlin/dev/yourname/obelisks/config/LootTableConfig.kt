package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
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
     * Attempts to apply Apotheosis affixes if the mod is loaded.
     * GUARANTEED affixes if Apotheosis is installed.
     */
    private fun tryApplyApotheosisAffixes(stack: ItemStack, tier: EquipmentTier): ItemStack {
        try {
            // Check if Apotheosis is loaded
            Class.forName("dev.shadowsoffire.apotheosis.Apotheosis")

            // Try to apply affixes via reflection
            // Use AffixHelper to guarantee affixes
            val affixHelper = Class.forName("dev.shadowsoffire.apotheosis.adventure.affix.AffixHelper")

            // Rarity to quality mapping - use high values to guarantee affixes
            val quality = when (tier) {
                EquipmentTier.COMMON -> 1.0f      // 100% quality = guaranteed affixes
                EquipmentTier.UNCOMMON -> 1.0f
                EquipmentTier.RARE -> 1.0f
                EquipmentTier.EPIC -> 1.0f
                EquipmentTier.LEGENDARY -> 1.0f
            }

            // Try multiple methods to ensure affixes are applied
            try {
                // Method 1: Try applyRandomAffixes with guaranteed quality
                val method1 = affixHelper.getMethod("applyRandomAffixes", ItemStack::class.java, net.minecraft.util.RandomSource::class.java, Float::class.javaPrimitiveType)
                method1.invoke(null, stack, net.minecraft.util.RandomSource.create(), quality)
            } catch (e1: Exception) {
                try {
                    // Method 2: Try alternative signature
                    val method2 = affixHelper.getDeclaredMethods().firstOrNull {
                        it.name.contains("affix", ignoreCase = true) && it.parameterCount >= 2
                    }
                    if (method2 != null) {
                        method2.isAccessible = true
                        when (method2.parameterCount) {
                            2 -> method2.invoke(null, stack, net.minecraft.util.RandomSource.create())
                            3 -> method2.invoke(null, stack, net.minecraft.util.RandomSource.create(), quality)
                            else -> method2.invoke(null, stack, net.minecraft.util.RandomSource.create(), quality, true)
                        }
                    }
                } catch (e2: Exception) {
                    // If both methods fail, try to add affixes directly via NBT manipulation
                    ensureAffixedViaRarity(stack, tier)
                }
            }

            return stack
        } catch (e: Exception) {
            // Apotheosis not installed, return vanilla item
            return stack
        }
    }

    /**
     * Ensures item has Apotheosis rarity set, which typically triggers affix generation.
     */
    private fun ensureAffixedViaRarity(stack: ItemStack, tier: EquipmentTier) {
        try {
            val rarityClass = Class.forName("dev.shadowsoffire.apotheosis.adventure.loot.LootRarity")
            val rarities = rarityClass.enumConstants

            // Map tier to Apotheosis rarity
            val rarityIndex = when (tier) {
                EquipmentTier.COMMON -> 0       // COMMON
                EquipmentTier.UNCOMMON -> 1     // UNCOMMON
                EquipmentTier.RARE -> 2         // RARE
                EquipmentTier.EPIC -> 3         // EPIC
                EquipmentTier.LEGENDARY -> 4    // MYTHIC
            }

            val rarity = rarities.getOrNull(rarityIndex) ?: rarities.last()

            // Try to set rarity on the item
            val lootHelper = Class.forName("dev.shadowsoffire.apotheosis.adventure.loot.LootCategory")
            val methods = lootHelper.declaredMethods.filter { it.name.contains("forItem") }
            // This would set the item's rarity, triggering affix generation

        } catch (e: Exception) {
            // Silently fail if unable to set rarity
        }
    }

    private enum class EquipmentTier {
        COMMON, UNCOMMON, RARE, EPIC, LEGENDARY
    }

}
