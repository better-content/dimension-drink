package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import dev.yourname.obelisks.MOD_ID
import net.minecraft.resources.ResourceLocation
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads dimension-specific obelisk configuration from JSON files.
 * Each JSON file in config/dimensions/ defines properties for one dimension.
 */
object DimensionConfigLoader {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dimensionConfigs = mutableMapOf<ResourceLocation, DimensionConfig>()

    private const val CONFIG_DIR = "config/dimensions"

    /**
     * Loads all dimension configuration files from the config/dimensions/ folder.
     */
    fun loadAll() {
        val configPath = Paths.get(CONFIG_DIR)

        // Create directory if it doesn't exist
        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath)
            println("[$MOD_ID] Created dimensions config directory: $CONFIG_DIR")

            // Generate default configs
            generateDefaultConfigs(configPath)
        }

        // Load all JSON files
        val jsonFiles = Files.walk(configPath)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .toList()

        if (jsonFiles.isEmpty()) {
            println("[$MOD_ID] Warning: No dimension config files found in $CONFIG_DIR")
            return
        }

        dimensionConfigs.clear()

        for (file in jsonFiles) {
            try {
                loadConfigFile(file)
            } catch (e: Exception) {
                println("[$MOD_ID] Error loading dimension config from ${file.fileName}: ${e.message}")
                e.printStackTrace()
            }
        }

        println("[$MOD_ID] Loaded ${dimensionConfigs.size} dimension configurations")
    }

    /**
     * Loads a single dimension config file.
     */
    private fun loadConfigFile(path: Path) {
        val content = Files.readString(path)
        val config = gson.fromJson(content, DimensionConfig::class.java)

        if (!config.isValid()) {
            println("[$MOD_ID] Skipping invalid dimension config: ${path.fileName}")
            return
        }

        val key = config.getDimensionKey()
        dimensionConfigs[key] = config

        val status = if (config.enabled) "ENABLED" else "DISABLED"
        println("[$MOD_ID] Loaded dimension config: ${config.dimensionName} ($status)")
    }

    /**
     * Generates default configuration files for common dimensions.
     */
    private fun generateDefaultConfigs(configPath: Path) {
        println("[$MOD_ID] Generating default dimension configs...")

        val netherConfig = DimensionConfig(
            dimensionId = "minecraft:the_nether",
            dimensionName = "The Nether",
            stemBlockType = "minecraft:netherrack",
            feMultiplier = 1.0,
            spawnY = 100,
            platformBlock = "minecraft:netherrack",
            glowBlock = "minecraft:glowstone",
            enabled = true,
            minFERequired = 0,
            collapseSpeedMultiplier = 1.0,
            drainExponentialFactor = 0.00008,
            drainExponentialIntervalTicks = 20,
            flavorBlocks = listOf(
                "minecraft:netherrack",
                "minecraft:nether_bricks",
                "minecraft:red_nether_bricks",
                "minecraft:magma_block",
                "minecraft:soul_sand",
                "minecraft:soul_soil",
                "minecraft:blackstone"
            ),
            rarityMultiplier = 1.0,
            slotCount = 5,
            targetBlock = "minecraft:netherrack",
            customProperties = emptyMap()
        )

        val endConfig = DimensionConfig(
            dimensionId = "minecraft:the_end",
            dimensionName = "The End",
            stemBlockType = "minecraft:end_stone",
            feMultiplier = 1.5,
            spawnY = 64,
            platformBlock = "minecraft:end_stone",
            glowBlock = "minecraft:end_rod",
            enabled = true,
            minFERequired = 5000,
            collapseSpeedMultiplier = 1.2,
            drainExponentialFactor = 0.0001,
            drainExponentialIntervalTicks = 20,
            flavorBlocks = listOf(
                "minecraft:end_stone",
                "minecraft:end_stone_bricks",
                "minecraft:purpur_block",
                "minecraft:purpur_pillar",
                "minecraft:obsidian"
            ),
            rarityMultiplier = 0.5,
            slotCount = 5,
            targetBlock = "minecraft:end_stone",
            customProperties = emptyMap()
        )

        val overworldConfig = DimensionConfig(
            dimensionId = "minecraft:overworld",
            dimensionName = "Overworld",
            stemBlockType = "minecraft:stone",
            feMultiplier = 0.8,
            spawnY = 64,
            platformBlock = "minecraft:stone",
            glowBlock = "minecraft:torch",
            enabled = true, // ENABLED by default now
            minFERequired = 0,
            collapseSpeedMultiplier = 0.9,
            drainExponentialFactor = 0.00005,
            drainExponentialIntervalTicks = 20,
            flavorBlocks = listOf(
                "minecraft:stone",
                "minecraft:cobblestone",
                "minecraft:mossy_cobblestone",
                "minecraft:gravel",
                "minecraft:andesite",
                "minecraft:diorite"
            ),
            rarityMultiplier = 1.5,
            slotCount = 5,
            targetBlock = "minecraft:stone",
            customProperties = emptyMap()
        )

        saveConfig(configPath.resolve("nether.json"), netherConfig)
        saveConfig(configPath.resolve("end.json"), endConfig)
        saveConfig(configPath.resolve("overworld.json"), overworldConfig)

        println("[$MOD_ID] Generated default dimension configs")
    }

    /**
     * Saves a config to a JSON file.
     */
    private fun saveConfig(path: Path, config: DimensionConfig) {
        val json = gson.toJson(config)
        Files.writeString(path, json)
    }

    /**
     * Gets the configuration for a specific dimension.
     */
    fun getConfig(dimensionKey: ResourceLocation): DimensionConfig? {
        return dimensionConfigs[dimensionKey]
    }

    /**
     * Gets all loaded dimension configurations.
     */
    fun getAllConfigs(): Map<ResourceLocation, DimensionConfig> {
        return dimensionConfigs.toMap()
    }

    /**
     * Gets all enabled dimension configurations.
     */
    fun getEnabledConfigs(): Map<ResourceLocation, DimensionConfig> {
        return dimensionConfigs.filterValues { it.enabled }
    }

    /**
     * Checks if a dimension is configured and enabled.
     */
    fun isDimensionEnabled(dimensionKey: ResourceLocation): Boolean {
        return dimensionConfigs[dimensionKey]?.enabled == true
    }

    /**
     * Gets the FE multiplier for a dimension (defaults to 1.0).
     */
    fun getFEMultiplier(dimensionKey: ResourceLocation): Double {
        return dimensionConfigs[dimensionKey]?.feMultiplier ?: 1.0
    }

    /**
     * Gets the stem block type for a dimension.
     */
    fun getStemBlockType(dimensionKey: ResourceLocation): String? {
        return dimensionConfigs[dimensionKey]?.stemBlockType
    }

    /**
     * Gets the configuration for a specific DimensionBaseType.
     * Maps base types to their corresponding dimension IDs.
     */
    fun getConfigForBaseType(baseType: dev.yourname.obelisks.dimension.DimensionBaseType): DimensionConfig? {
        val dimensionId = when (baseType) {
            dev.yourname.obelisks.dimension.DimensionBaseType.NETHER -> "minecraft:the_nether"
            dev.yourname.obelisks.dimension.DimensionBaseType.END -> "minecraft:the_end"
            dev.yourname.obelisks.dimension.DimensionBaseType.OVERWORLD -> "minecraft:overworld"
        }
        return getConfig(ResourceLocation(dimensionId))
    }

    /**
     * Reloads all dimension configurations.
     */
    fun reload() {
        println("[$MOD_ID] Reloading dimension configurations...")
        loadAll()
    }
}
