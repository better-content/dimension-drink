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

            println("[Obelisks] All configurations loaded successfully")

        } catch (e: Exception) {
            println("[Obelisks] ERROR: Failed to load configuration: ${e.message}")
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

    /**
     * Get configuration for a specific DimensionBaseType.
     * Maps base types to their corresponding dimension IDs.
     */
    fun getConfigForBaseType(baseType: dev.yourname.obelisks.dimension.DimensionBaseType): DimensionConfig? {
        val dimensionId = when (baseType) {
            dev.yourname.obelisks.dimension.DimensionBaseType.NETHER -> "minecraft:the_nether"
            dev.yourname.obelisks.dimension.DimensionBaseType.END -> "minecraft:the_end"
            dev.yourname.obelisks.dimension.DimensionBaseType.OVERWORLD -> "minecraft:overworld"
        }
        return getDimensionConfig(dimensionId)
    }

    private fun loadMainConfig(): MainConfig {
        val configPath = configRoot.resolve("obelisks.json")

        if (!Files.exists(configPath)) {
            println("[Obelisks] Main config not found, creating default at: $configPath")
            copyDefaultResource("/config/obelisks.json.default", configPath)
        }

        val json = Files.readString(configPath)
        return gson.fromJson(json, MainConfig::class.java)
    }

    private fun loadDimensionConfigs(): Map<String, DimensionConfig> {
        val dimensionsDir = configRoot.resolve("dimensions")

        if (!Files.exists(dimensionsDir)) {
            Files.createDirectories(dimensionsDir)
            println("[Obelisks] Dimensions config directory created")

            // Copy default dimension configs
            copyDefaultResource("/config/dimensions/overworld.json.default", dimensionsDir.resolve("overworld.json"))
            copyDefaultResource("/config/dimensions/nether.json.default", dimensionsDir.resolve("nether.json"))
            copyDefaultResource("/config/dimensions/end.json.default", dimensionsDir.resolve("end.json"))
        }

        val configs = mutableMapOf<String, DimensionConfig>()

        Files.walk(dimensionsDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .forEach { file ->
                try {
                    val json = Files.readString(file)
                    val config = gson.fromJson(json, DimensionConfig::class.java)

                    if (config.isValid() && validateDimensionConfig(config)) {
                        configs[config.dimensionId] = config
                        val status = if (config.enabled) "ENABLED" else "DISABLED"
                        println("[Obelisks] Loaded dimension config: ${config.dimensionName} ($status)")
                    } else {
                        println("[Obelisks] Skipping invalid/unloadable dimension config: ${file.fileName}")
                    }
                } catch (e: Exception) {
                    println("[Obelisks] Error loading dimension config from ${file.fileName}: ${e.message}")
                }
            }

        println("[Obelisks] Loaded ${configs.size} dimension configurations")
        return configs
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
                    println("[Obelisks] Config ${config.dimensionName}: Mod '$namespace' not loaded (required for block '$blockId')")
                    return false
                }

                // Check if the block exists in registry
                if (!BuiltInRegistries.BLOCK.containsKey(resourceLocation)) {
                    println("[Obelisks] Config ${config.dimensionName}: Block '$blockId' not found in registry")
                    return false
                }
            } catch (e: Exception) {
                println("[Obelisks] Config ${config.dimensionName}: Invalid block ID '$blockId': ${e.message}")
                return false
            }
        }

        return true
    }

    private fun loadLootTableConfig(): LootTableConfig {
        val lootTablePath = configRoot.resolve("loot_tables/run_completion.json")

        if (!Files.exists(lootTablePath)) {
            println("[Obelisks] Loot table not found, creating default at: $lootTablePath")
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
                        println("[Obelisks] Loot table pool '${pool.name}': Mod '$namespace' not loaded (item '${entry.item}' will be skipped)")
                        invalidEntries.add(entry)
                        continue
                    }

                    // Check if the item exists in registry
                    if (!BuiltInRegistries.ITEM.containsKey(resourceLocation)) {
                        println("[Obelisks] Loot table pool '${pool.name}': Item '${entry.item}' not found in registry (will be skipped)")
                        invalidEntries.add(entry)
                    }
                } catch (e: Exception) {
                    println("[Obelisks] Loot table pool '${pool.name}': Invalid item ID '${entry.item}' (will be skipped): ${e.message}")
                    invalidEntries.add(entry)
                }
            }

            // Remove invalid entries from the pool
            if (invalidEntries.isNotEmpty()) {
                (pool.entries as MutableList).removeAll(invalidEntries)
                println("[Obelisks] Removed ${invalidEntries.size} invalid entries from loot pool '${pool.name}'")
            }
        }
    }

    private fun copyDefaultResource(resourcePath: String, destination: Path) {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Could not find default config template: $resourcePath")

        inputStream.use { input ->
            Files.copy(input, destination)
        }
        println("[Obelisks] Created default config from template: $destination")
    }

    private fun checkInitialized() {
        if (!::mainConfig.isInitialized) {
            throw IllegalStateException("ConfigManager not initialized. Call ConfigManager.load() first.")
        }
    }
}
