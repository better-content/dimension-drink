package dev.yourname.obelisks.dimension

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.config.DimensionConfigLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Generates dimension slot JSON files dynamically based on loaded dimension configs.
 * This ensures that the correct number of slots are created for each dimension type.
 */
object DimensionSlotJsonGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Generates dimension and dimension_type JSON files for all slots.
     * Should be called after DimensionConfigLoader.loadAll() and DimensionSlotManager.initializeSlots()
     */
    fun generateSlotJsons() {
        val dimensionDir = Paths.get("src/main/resources/data/$MOD_ID/dimension")
        val dimensionTypeDir = Paths.get("src/main/resources/data/$MOD_ID/dimension_type")

        // Create directories if they don't exist
        Files.createDirectories(dimensionDir)
        Files.createDirectories(dimensionTypeDir)

        println("[$MOD_ID] Generating dimension slot JSON files...")

        var slotIndex = 0

        // Iterate through all dimension base types and generate slots
        for (baseType in DimensionBaseType.entries) {
            val config = DimensionConfigLoader.getConfigForBaseType(baseType)
            if (config != null && config.enabled) {
                val slotCount = config.slotCount

                for (i in 0 until slotCount) {
                    generateDimensionJson(dimensionDir, slotIndex, baseType)
                    slotIndex++
                }

                println("[$MOD_ID] Generated $slotCount dimension slots for ${baseType.name}")
            }
        }

        println("[$MOD_ID] Total dimension slots generated: $slotIndex")
    }

    private fun generateDimensionJson(outputDir: java.nio.file.Path, slotIndex: Int, baseType: DimensionBaseType) {
        val dimensionJson = JsonObject()

        // Set dimension type reference based on base type
        val templateType = when (baseType) {
            DimensionBaseType.NETHER -> "obelisks:template_nether"
            DimensionBaseType.END -> "obelisks:template_end"
            DimensionBaseType.OVERWORLD -> "obelisks:template_overworld"
        }
        dimensionJson.addProperty("type", templateType)

        // Set generator configuration
        val generator = JsonObject()
        generator.addProperty("type", "minecraft:noise")

        // Biome source configuration
        val biomeSource = JsonObject()
        when (baseType) {
            DimensionBaseType.NETHER -> {
                biomeSource.addProperty("type", "minecraft:multi_noise")
                biomeSource.addProperty("preset", "minecraft:nether")
            }
            DimensionBaseType.END -> {
                biomeSource.addProperty("type", "minecraft:the_end")
            }
            DimensionBaseType.OVERWORLD -> {
                biomeSource.addProperty("type", "minecraft:multi_noise")
                biomeSource.addProperty("preset", "minecraft:overworld")
            }
        }
        generator.add("biome_source", biomeSource)

        // Generator settings
        val settings = when (baseType) {
            DimensionBaseType.NETHER -> "minecraft:nether"
            DimensionBaseType.END -> "minecraft:end"
            DimensionBaseType.OVERWORLD -> "minecraft:overworld"
        }
        generator.addProperty("settings", settings)

        dimensionJson.add("generator", generator)

        // Write to file
        val outputFile = outputDir.resolve("run_slot_$slotIndex.json").toFile()
        outputFile.writeText(gson.toJson(dimensionJson))
    }

    /**
     * Cleans up old slot JSON files that are no longer needed.
     * Useful when slot counts change in configs.
     */
    fun cleanupOldSlotJsons(maxSlotIndex: Int) {
        val dimensionDir = Paths.get("src/main/resources/data/$MOD_ID/dimension")
        if (!Files.exists(dimensionDir)) return

        val files = Files.list(dimensionDir)
            .filter { it.fileName.toString().matches(Regex("run_slot_\\d+\\.json")) }
            .toList()

        for (file in files) {
            val fileName = file.fileName.toString()
            val slotNumber = fileName.replace("run_slot_", "").replace(".json", "").toIntOrNull()

            if (slotNumber != null && slotNumber >= maxSlotIndex) {
                Files.deleteIfExists(file)
                println("[$MOD_ID] Removed old slot JSON: $fileName")
            }
        }
    }
}
