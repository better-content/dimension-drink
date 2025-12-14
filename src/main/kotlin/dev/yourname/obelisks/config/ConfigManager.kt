package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.ModList
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Centralized configuration manager.
 * Loads all configs from JSON once on initialization and provides access through data classes.
 */
object ConfigManager {

    private lateinit var mainConfig: MainConfig
    private lateinit var dimensionConfigs: Map<String, DimensionConfig>
    private lateinit var lootTableConfig: LootTableConfig

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configRoot = Paths.get("config/obelisks")

    /**
     * Load all configuration files into memory.
     * Call this once during mod initialization.
     */
    fun load() {
        try {
            Files.createDirectories(configRoot)

            // Load main config
            mainConfig = loadMainConfig()

            // Load dimension configs
            dimensionConfigs = loadDimensionConfigs()

            // Load loot table config
            lootTableConfig = loadLootTableConfig()

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Get the main configuration.
     */
    fun getMainConfig(): MainConfig {
        checkInitialized()
        return mainConfig
    }

    /**
     * Get a specific dimension configuration by dimension ID.
     */
    fun getDimensionConfig(dimensionId: String): DimensionConfig? {
        checkInitialized()
        return dimensionConfigs[dimensionId]
    }

    /**
     * Get a specific dimension configuration by ResourceLocation.
     */
    fun getDimensionConfig(dimensionKey: ResourceLocation): DimensionConfig? {
        checkInitialized()
        return dimensionConfigs[dimensionKey.toString()]
    }

    /**
     * Get all dimension configurations.
     */
    fun getAllDimensionConfigs(): Map<String, DimensionConfig> {
        checkInitialized()
        return dimensionConfigs
    }

    /**
     * Get only enabled dimension configurations.
     */
    fun getEnabledDimensionConfigs(): Map<String, DimensionConfig> {
        checkInitialized()
        return dimensionConfigs.filterValues { it.enabled }
    }

    /**
     * Get the loot table configuration.
     */
    fun getLootTableConfig(): LootTableConfig {
        checkInitialized()
        return lootTableConfig
    }

    /**
     * Check if a dimension is enabled.
     */
    fun isDimensionEnabled(dimensionId: String): Boolean {
        checkInitialized()
        return dimensionConfigs[dimensionId]?.enabled == true
    }

    /**
     * Get FE multiplier for a dimension (defaults to 1.0).
     */
    fun getFEMultiplier(dimensionId: String): Double {
        checkInitialized()
        return dimensionConfigs[dimensionId]?.feMultiplier ?: 1.0
    }

    /**
     * Get stem block type for a dimension.
     */
    fun getStemBlockType(dimensionId: String): String? {
        checkInitialized()
        return dimensionConfigs[dimensionId]?.stemBlockType
    }

    /**
     * Get stem block type for a dimension by ResourceLocation.
     */
    fun getStemBlockType(dimensionKey: ResourceLocation): String? {
        checkInitialized()
        return dimensionConfigs[dimensionKey.toString()]?.stemBlockType
    }

    /**
     * Check if a dimension is enabled by ResourceLocation.
     */
    fun isDimensionEnabled(dimensionKey: ResourceLocation): Boolean {
        checkInitialized()
        return dimensionConfigs[dimensionKey.toString()]?.enabled == true
    }

    /**
     * Get FE multiplier for a dimension by ResourceLocation.
     */
    fun getFEMultiplier(dimensionKey: ResourceLocation): Double {
        checkInitialized()
        return dimensionConfigs[dimensionKey.toString()]?.feMultiplier ?: 1.0
    }

    private fun loadMainConfig(): MainConfig {
        val configPath = configRoot.resolve("obelisks.json")

        if (!Files.exists(configPath)) {
            copyDefaultResource("/config/obelisks.json.default", configPath)
        }

        val json = Files.readString(configPath)
        return gson.fromJson(json, MainConfig::class.java)
    }

    private fun loadDimensionConfigs(): Map<String, DimensionConfig> {
        val dimensionsDir = configRoot.resolve("dimensions")

        if (!Files.exists(dimensionsDir)) {
            Files.createDirectories(dimensionsDir)
        }

        // Auto-copy dimension configs if their template exists and the file doesn't exist yet
        val dimensionTemplates = listOf(
            "overworld", "nether", "end",
            "aether", "twilight", "everbright", "everdawn", "fallout", "otherside"
        )

        for (templateName in dimensionTemplates) {
            val targetFile = dimensionsDir.resolve("$templateName.json")
            if (!Files.exists(targetFile)) {
                try {
                    copyDefaultResource("/config/dimensions/$templateName.json.default", targetFile)
                } catch (e: Exception) {
                    // Template doesn't exist in resources, skip
                }
            }
        }

        val configs = mutableMapOf<String, DimensionConfig>()

        Files.walk(dimensionsDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .forEach { file ->
                try {
                    val json = Files.readString(file)
                    val config = gson.fromJson(json, DimensionConfig::class.java)

                    // Load all configs initially without validation
                    // Validation will be done lazily when blocks are actually accessed
                    if (config.isValid()) {
                        // Only check if mod is loaded, not if blocks exist yet
                        if (checkModsLoaded(config)) {
                            configs[config.dimensionId] = config
                        }
                    }
                } catch (e: Exception) {
                    // Silently skip configs that fail to load
                }
            }

        return configs
    }

    /**
     * Checks if all required mods for a dimension config are loaded.
     * Does NOT validate that blocks exist in registry (that happens too early).
     */
    private fun checkModsLoaded(config: DimensionConfig): Boolean {
        val blocksToCheck = listOf(
            config.stemBlockType,
            config.platformBlock,
            config.glowBlock,
            config.targetBlock
        ) + config.flavorBlocks

        for (blockId in blocksToCheck) {
            try {
                val resourceLocation = ResourceLocation(blockId)
                val namespace = resourceLocation.namespace

                // Only check if mod is loaded, not if block exists
                if (namespace != "minecraft" && !ModList.get().isLoaded(namespace)) {
                    return false
                }
            } catch (e: Exception) {
                return false
            }
        }

        return true
    }

    /**
     * Validates that all blocks/items referenced in a dimension config exist and their mods are loaded.
     */
    private fun validateDimensionConfig(config: DimensionConfig): Boolean {
        val blocksToCheck = listOf(
            config.stemBlockType,
            config.platformBlock,
            config.glowBlock,
            config.targetBlock
        ) + config.flavorBlocks

        for (blockId in blocksToCheck) {
            try {
                val resourceLocation = ResourceLocation(blockId)

                // Check if the mod is loaded
                val namespace = resourceLocation.namespace
                if (namespace != "minecraft" && !ModList.get().isLoaded(namespace)) {
                    return false
                }

                // Check if the block exists in registry
                if (!BuiltInRegistries.BLOCK.containsKey(resourceLocation)) {
                    return false
                }
            } catch (e: Exception) {
                return false
            }
        }

        return true
    }

    private fun loadLootTableConfig(): LootTableConfig {
        val lootTablePath = configRoot.resolve("loot_tables/run_completion.json")

        if (!Files.exists(lootTablePath)) {
            Files.createDirectories(lootTablePath.parent)

            val defaultTable = LootTableConfig(
                enabled = true,
                pools = listOf(
                    LootPool(
                        name = "emeralds",
                        rolls = RollRange(0, 3),
                        entries = listOf(
                            LootEntry("item", "minecraft:emerald", 100)
                        )
                    )
                )
            )

            Files.writeString(lootTablePath, gson.toJson(defaultTable))
            return defaultTable
        }

        val json = Files.readString(lootTablePath)
        val table = gson.fromJson(json, LootTableConfig::class.java)

        // Validate loot table entries
        validateLootTable(table)

        return table
    }

    /**
     * Validates that all items referenced in loot table exist and their mods are loaded.
     * Removes invalid entries instead of failing entirely.
     */
    private fun validateLootTable(table: LootTableConfig) {
        for (pool in table.pools) {
            val invalidEntries = mutableListOf<LootEntry>()

            for (entry in pool.entries) {
                if (entry.type != "item") continue

                try {
                    val resourceLocation = ResourceLocation(entry.item)

                    // Check if the mod is loaded
                    val namespace = resourceLocation.namespace
                    if (namespace != "minecraft" && !ModList.get().isLoaded(namespace)) {
                        invalidEntries.add(entry)
                        continue
                    }

                    // Check if the item exists in registry
                    if (!BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
                        invalidEntries.add(entry)
                    }
                } catch (e: Exception) {
                    invalidEntries.add(entry)
                }
            }

            // Remove invalid entries from the pool
            if (invalidEntries.isNotEmpty()) {
                (pool.entries as MutableList).removeAll(invalidEntries)
            }
        }
    }

    private fun copyDefaultResource(resourcePath: String, destination: Path) {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find default config template: $resourcePath")

        inputStream.use { input ->
            Files.copy(input, destination)
        }
    }

    private fun checkInitialized() {
        if (!::mainConfig.isInitialized) {
            throw IllegalStateException("ConfigManager not initialized. Call ConfigManager.load() first.")
        }
    }
}
