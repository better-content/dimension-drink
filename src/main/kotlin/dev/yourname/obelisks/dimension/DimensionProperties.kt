package dev.yourname.obelisks.dimension

import dev.yourname.obelisks.config.DimensionConfig
import dev.yourname.obelisks.config.DimensionConfigLoader
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
        return DimensionConfigLoader.getFEMultiplier(dimensionKey)
    }

    /**
     * Gets the stem block type for a dimension.
     * Returns null if not configured.
     */
    fun getStemBlock(dimensionKey: ResourceLocation): Block? {
        val blockId = DimensionConfigLoader.getStemBlockType(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(blockId)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the platform block type for a dimension.
     */
    fun getPlatformBlock(dimensionKey: ResourceLocation): Block? {
        val config = DimensionConfigLoader.getConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.platformBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the glow block type for a dimension.
     */
    fun getGlowBlock(dimensionKey: ResourceLocation): Block? {
        val config = DimensionConfigLoader.getConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.glowBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }

    /**
     * Gets the spawn Y coordinate for a dimension.
     */
    fun getSpawnY(dimensionKey: ResourceLocation): Int? {
        return DimensionConfigLoader.getConfig(dimensionKey)?.spawnY
    }

    /**
     * Gets the minimum FE required to enter a dimension.
     */
    fun getMinFERequired(dimensionKey: ResourceLocation): Int {
        return DimensionConfigLoader.getConfig(dimensionKey)?.minFERequired ?: 0
    }

    /**
     * Gets the collapse speed multiplier for a dimension.
     */
    fun getCollapseSpeedMultiplier(dimensionKey: ResourceLocation): Double {
        return DimensionConfigLoader.getConfig(dimensionKey)?.collapseSpeedMultiplier ?: 1.0
    }

    /**
     * Checks if a dimension is enabled for obelisk runs.
     */
    fun isDimensionEnabled(dimensionKey: ResourceLocation): Boolean {
        return DimensionConfigLoader.isDimensionEnabled(dimensionKey)
    }

    /**
     * Gets the full configuration for a dimension.
     */
    fun getConfig(dimensionKey: ResourceLocation): DimensionConfig? {
        return DimensionConfigLoader.getConfig(dimensionKey)
    }

    /**
     * Gets all enabled dimension configurations.
     */
    fun getEnabledDimensions(): Map<ResourceLocation, DimensionConfig> {
        return DimensionConfigLoader.getEnabledConfigs()
    }

    /**
     * Gets the display name for a dimension.
     */
    fun getDimensionName(dimensionKey: ResourceLocation): String {
        return DimensionConfigLoader.getConfig(dimensionKey)?.dimensionName
            ?: dimensionKey.toString()
    }

    /**
     * Gets the target block that platforms must spawn mostly touching.
     */
    fun getTargetBlock(dimensionKey: ResourceLocation): Block? {
        val config = DimensionConfigLoader.getConfig(dimensionKey) ?: return null
        val blockLocation = ResourceLocation(config.targetBlock)
        return ForgeRegistries.BLOCKS.getValue(blockLocation)
    }
}
