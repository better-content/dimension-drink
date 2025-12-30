package dev.yourname.obelisks.config

import net.minecraft.resources.ResourceLocation

/**
 * Configuration data for a single dimension's obelisk properties.
 * Loaded from JSON files in config/dimensions/
 */
data class DimensionConfig(
    /** The dimension's resource location (e.g., "minecraft:the_nether") */
    val dimensionId: String,

    /** Human-readable dimension name for display */
    val dimensionName: String,

    /** FE drain multiplier for this dimension (1.0 = normal, 2.0 = double drain) */
    val feMultiplier: Double,

    /** Block type to use for spawn platform */
    val platformBlock: String,

    /** Block type to use for platform lighting */
    val glowBlock: String,

    /** Whether this dimension is enabled for obelisk runs */
    val enabled: Boolean,

    /** Minimum FE required to enter this dimension */
    val minFERequired: Int,

    /** Flavor blocks to scatter around obelisk spawn areas for decoration */
    val flavorBlocks: List<String>,

    /** Rarity multiplier for obelisk worldgen spawning (1.0 = normal, 0.5 = half as common, 2.0 = twice as common) */
    val rarityMultiplier: Double,

    /** Block type to use for obelisk stem */
    val stemBlockType: String = "minecraft:obsidian",

    /** Block type to use as target/destination block */
    val targetBlock: String = "minecraft:crying_obsidian",

    /** Y coordinate for spawn platform */
    val spawnY: Int? = null,

    /** Multiplier for dimension collapse speed */
    val collapseSpeedMultiplier: Double = 1.0,

    /** Interval in ticks for exponential FE drain calculations */
    val drainExponentialIntervalTicks: Int = 200,

    /** Factor for exponential FE drain growth */
    val drainExponentialFactor: Double = 0.0001,

    /** Custom properties for future extensibility */
    val customProperties: Map<String, Any> = mapOf(),

    /** Difficulty modifiers for this dimension */
    val difficultySettings: DifficultySettings = DifficultySettings(),

    /** Loot table to use for kill rewards (defaults to "obelisks:obelisk_kill_reward") */
    val lootTable: String? = null,

    /** Equipment loot table to use for gear drops (defaults to "obelisks:obelisk_equipment_reward") */
    val equipmentLootTable: String? = null
) {
    /** Get the dimension as a ResourceLocation */
    fun getDimensionKey(): ResourceLocation = ResourceLocation(dimensionId)

    /** Check if this dimension is valid and usable */
    fun isValid(): Boolean = enabled && dimensionId.isNotBlank()
}

/**
 * Difficulty settings for a dimension run.
 */
data class DifficultySettings(
    /** Multiplier for mob spawn rates (1.0 = normal, 2.0 = double spawns) */
    val spawnRateMultiplier: Double = 1.0,

    /** Multiplier for mob health (1.0 = normal, 1.5 = 50% more health) */
    val healthMultiplier: Double = 1.0,

    /** Multiplier for mob damage (1.0 = normal, 1.5 = 50% more damage) */
    val damageMultiplier: Double = 1.0,

    /** Potion effects to apply to all spawned mobs (format: "effect_id:amplifier:duration_seconds") */
    val mobEffects: List<String> = emptyList(),

    /** Chance (0.0-1.0) that mobs spawn with armor */
    val armorChance: Double = 0.0,

    /** Chance (0.0-1.0) that mobs spawn with weapons */
    val weaponChance: Double = 0.0,

    /** Multiplier for mob movement speed (1.0 = normal, 1.2 = 20% faster) */
    val speedMultiplier: Double = 1.0,

    /** Whether mobs can pick up loot */
    val canPickupLoot: Boolean = false,

    /** Additional armor points to add to mobs */
    val bonusArmorPoints: Double = 0.0,

    /** Chance (0.0-1.0) for mobs to spawn as babies/children (where applicable) */
    val babyChance: Double = 0.0
)
