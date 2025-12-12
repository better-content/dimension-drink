package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.config.ConfigManager
import dev.yourname.obelisks.config.DimensionConfig
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraftforge.registries.ForgeRegistries

/**
 * Helper class to access dimension-specific obelisk properties.
 * Bridges the JSON configuration system with runtime dimension handling.
 */
object DimensionProperties {

    /**
     * Gets the FE drain multiplier for a dimension.
     * Returns 1.0 if the dimension has no specific configuration.
     */
    fun getFEMultiplier(dimensionKey: ResourceLocation): Double {
        return ConfigManager.getFEMultiplier(dimensionKey)
    }

    /**
     * Gets the stem block type for a dimension.
     * Returns null if not configured.
     */
    fun getStemBlock(dimensionKey: ResourceLocation): Block? {
        val blockId = ConfigManager.getStemBlockType(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(blockId)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the platform block type for a dimension.
     */
    fun getPlatformBlock(dimensionKey: ResourceLocation): Block? {
        val config = ConfigManager.getDimensionConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.platformBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the glow block type for a dimension.
     */
    fun getGlowBlock(dimensionKey: ResourceLocation): Block? {
        val config = ConfigManager.getDimensionConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.glowBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the spawn Y coordinate for a dimension.
     */
    fun getSpawnY(dimensionKey: ResourceLocation): Int? {
        return ConfigManager.getDimensionConfig(dimensionKey)?.spawnY
    }

    /**
     * Gets the minimum FE required to enter a dimension.
     */
    fun getMinFERequired(dimensionKey: ResourceLocation): Int {
        return ConfigManager.getDimensionConfig(dimensionKey)?.minFERequired ?: 0
    }

    /**
     * Gets the collapse speed multiplier for a dimension.
     */
    fun getCollapseSpeedMultiplier(dimensionKey: ResourceLocation): Double {
        return ConfigManager.getDimensionConfig(dimensionKey)?.collapseSpeedMultiplier ?: 1.0
    }

    /**
     * Checks if a dimension is enabled for obelisk runs.
     */
    fun isDimensionEnabled(dimensionKey: ResourceLocation): Boolean {
        return ConfigManager.isDimensionEnabled(dimensionKey)
    }

    /**
     * Gets the full configuration for a dimension.
     */
    fun getConfig(dimensionKey: ResourceLocation): DimensionConfig? {
        return ConfigManager.getDimensionConfig(dimensionKey)
    }

    /**
     * Gets all enabled dimension configurations.
     */
    fun getEnabledDimensions(): Map<ResourceLocation, DimensionConfig> {
        return ConfigManager.getEnabledDimensionConfigs().mapKeys { ResourceLocation(it.key) }
    }

    /**
     * Gets the display name for a dimension.
     */
    fun getDimensionName(dimensionKey: ResourceLocation): String {
        return ConfigManager.getDimensionConfig(dimensionKey)?.dimensionName
            ?: dimensionKey.toString()
    }

    /**
     * Gets the target block that platforms must spawn mostly touching.
     */
    fun getTargetBlock(dimensionKey: ResourceLocation): Block? {
        val config = ConfigManager.getDimensionConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.targetBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }
}
