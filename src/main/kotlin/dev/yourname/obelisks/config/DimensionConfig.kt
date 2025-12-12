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

    /** Block type to use for the obelisk stem/pillar */
    val stemBlockType: String,

    /** FE drain multiplier for this dimension (1.0 = normal, 2.0 = double drain) */
    val feMultiplier: Double,

    /** Default spawn Y coordinate for this dimension */
    val spawnY: Int,

    /** Block type to use for spawn platform */
    val platformBlock: String,

    /** Block type to use for platform lighting */
    val glowBlock: String,

    /** Whether this dimension is enabled for obelisk runs */
    val enabled: Boolean,

    /** Minimum FE required to enter this dimension */
    val minFERequired: Int,

    /** Custom collapse speed multiplier (1.0 = normal) */
    val collapseSpeedMultiplier: Double,

    /** Exponential drain factor (controls how quickly drain increases over time) */
    val drainExponentialFactor: Double,

    /** Interval in ticks between drain multiplier recalculations */
    val drainExponentialIntervalTicks: Int,

    /** Flavor blocks to scatter around obelisk spawn areas for decoration */
    val flavorBlocks: List<String>,

    /** Rarity multiplier for obelisk worldgen spawning (1.0 = normal, 0.5 = half as common, 2.0 = twice as common) */
    val rarityMultiplier: Double,

    /** Number of dimension slots to allocate for this dimension type */
    val slotCount: Int,

    /** Target block that the platform must spawn mostly touching (e.g., "minecraft:netherrack") */
    val targetBlock: String,

    /** Custom properties for future extensibility */
    val customProperties: Map<String, Any>
) {
    /** Get the dimension as a ResourceLocation */
    fun getDimensionKey(): ResourceLocation = ResourceLocation(dimensionId)

    /** Check if this dimension is valid and usable */
    fun isValid(): Boolean = enabled && dimensionId.isNotBlank() && stemBlockType.isNotBlank()
}
