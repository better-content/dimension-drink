package dev.yourname.obelisks.config

import dev.yourname.obelisks.dimension.DimensionBaseType
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Centralized configuration for obelisk types.
 * Maps dimension types to their corresponding blocks, generation rules, etc.
 * TODO: Eventually load from JSON config files for data-driven customization.
 */
data class ObeliskTypeConfig(
    val dimensionType: DimensionBaseType,
    val pillarBlock: Block,
    val platformBlock: Block,
    val pillarHeight: Int = 2,
    // Future: add more properties like color, particle effects, sounds, etc.
)

object ObeliskTypeRegistry {

    // Configuration - TODO: move to JSON config
    private val configs = mapOf(
        DimensionBaseType.NETHER to ObeliskTypeConfig(
            dimensionType = DimensionBaseType.NETHER,
            pillarBlock = Blocks.NETHERRACK,
            platformBlock = Blocks.NETHERRACK,
            pillarHeight = 2
        ),
        DimensionBaseType.END to ObeliskTypeConfig(
            dimensionType = DimensionBaseType.END,
            pillarBlock = Blocks.END_STONE,
            platformBlock = Blocks.END_STONE,
            pillarHeight = 2
        )
    )

    /**
     * Gets the configuration for a given dimension type.
     */
    fun getConfig(type: DimensionBaseType): ObeliskTypeConfig {
        return configs[type] ?: throw IllegalArgumentException("No config for dimension type: $type")
    }

    /**
     * Gets all registered dimension types.
     */
    fun getAllTypes(): List<DimensionBaseType> {
        return configs.keys.toList()
    }
}
