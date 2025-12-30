package dev.yourname.obelisks.config

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Centralized configuration for obelisk types.
 * Provides access to obelisk visual/mechanical properties based on dimension configs.
 * Fully data-driven from JSON config files.
 */
data class ObeliskTypeConfig(
    val dimensionConfig: DimensionConfig,
    val pillarBlock: Block,
    val platformBlock: Block,
    val pillarHeight: Int = 2,
)

object ObeliskTypeRegistry {

    /**
     * Gets a random obelisk configuration using weighted selection based on rarity multipliers.
     * Higher rarity multiplier = more likely to be chosen.
     * Only considers ENABLED dimensions.
     *
     * @param random Random source for selection
     * @return ObeliskTypeConfig or null if no enabled dimensions exist
     */
    fun getRandomWeightedConfig(random: RandomSource): ObeliskTypeConfig? {
        val enabledConfigs = try {
            ConfigManager.getEnabledDimensionConfigs()
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(ObeliskTypeRegistry::class.java)
                .error("Failed to get enabled dimension configs during worldgen", e)
            return null
        }

        if (enabledConfigs.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ObeliskTypeRegistry::class.java)
                .warn("No enabled dimension configs found. All: ${ConfigManager.getAllDimensionConfigs().keys}")
            return null
        }

        // Build weighted list
        val weights = mutableListOf<Pair<DimensionConfig, Double>>()
        var totalWeight = 0.0

        for ((_, dimConfig) in enabledConfigs) {
            val weight = dimConfig.rarityMultiplier
            if (weight > 0) {
                weights.add(Pair(dimConfig, weight))
                totalWeight += weight
            }
        }

        if (weights.isEmpty() || totalWeight <= 0) {
            return null
        }

        // Weighted random selection
        var randomValue = random.nextDouble() * totalWeight

        for ((dimConfig, weight) in weights) {
            randomValue -= weight
            if (randomValue <= 0) {
                return buildConfig(dimConfig)
            }
        }

        // Fallback
        return buildConfig(weights.lastOrNull()?.first ?: return null)
    }

    /**
     * Gets the configuration for a specific dimension config.
     */
    fun getConfigForDimension(dimensionConfig: DimensionConfig): ObeliskTypeConfig {
        return buildConfig(dimensionConfig)
    }

    /**
     * Gets the configuration for a specific dimension ID.
     * Returns null if the dimension is not enabled or doesn't exist.
     */
    fun getConfigForDimensionId(dimensionId: String): ObeliskTypeConfig? {
        val dimConfig = try {
            ConfigManager.getDimensionConfig(dimensionId)
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger(ObeliskTypeRegistry::class.java)
                .error("Failed to get dimension config for $dimensionId", e)
            return null
        }

        // Check if dimension is enabled
        if (dimConfig == null || !dimConfig.enabled) {
            return null
        }

        return buildConfig(dimConfig)
    }

    /**
     * Builds an ObeliskTypeConfig from a DimensionConfig.
     */
    private fun buildConfig(dimConfig: DimensionConfig): ObeliskTypeConfig {
        val pillarBlock = if (dimConfig.stemBlockType != null) {
            BuiltInRegistries.BLOCK.get(ResourceLocation(dimConfig.stemBlockType)) ?: Blocks.OBSIDIAN
        } else {
            Blocks.OBSIDIAN
        }

        val platformBlock = if (dimConfig.platformBlock != null) {
            BuiltInRegistries.BLOCK.get(ResourceLocation(dimConfig.platformBlock)) ?: Blocks.STONE
        } else {
            Blocks.STONE
        }

        return ObeliskTypeConfig(
            dimensionConfig = dimConfig,
            pillarBlock = pillarBlock,
            platformBlock = platformBlock,
            pillarHeight = 2
        )
    }
}
