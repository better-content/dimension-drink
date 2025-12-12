package dev.yourname.obelisks.config

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader

/**
 * Loads configuration from JSON files.
 */
object ConfigLoader {

    private val gson = Gson()
    private var config: JsonObject? = null

    /**
     * Loads the configuration from the JSON file.
     * Should be called during mod initialization.
     */
    fun load() {
        try {
            val inputStream = javaClass.getResourceAsStream("/config/obelisks.json")
                ?: throw IllegalStateException("Could not find config/obelisks.json in resources")

            val reader = InputStreamReader(inputStream)
            config = JsonParser.parseReader(reader).asJsonObject
            reader.close()

            println("[Obelisks] Configuration loaded successfully from JSON")

            // Load dimension-specific configs
            DimensionConfigLoader.loadAll()

            // Initialize dimension slots based on loaded configs
            dev.yourname.obelisks.dimension.DimensionSlotManager.initializeSlots()
        } catch (e: Exception) {
            println("[Obelisks] ERROR: Failed to load configuration from JSON: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Gets the loaded configuration.
     * Throws if config hasn't been loaded yet.
     */
    fun getConfig(): JsonObject {
        return config ?: throw IllegalStateException("Configuration not loaded. Call ConfigLoader.load() first.")
    }

    // Helper methods for type-safe access
    fun getString(path: String): String {
        return getValueAtPath(path).asString
    }

    fun getInt(path: String): Int {
        return getValueAtPath(path).asInt
    }

    fun getDouble(path: String): Double {
        return getValueAtPath(path).asDouble
    }

    fun getBoolean(path: String): Boolean {
        return getValueAtPath(path).asBoolean
    }

    /**
     * Navigates nested JSON structure using dot notation.
     * Example: "feSystem.maxFEStorage" -> config["feSystem"]["maxFEStorage"]
     */
    private fun getValueAtPath(path: String): com.google.gson.JsonElement {
        val parts = path.split(".")
        var current: com.google.gson.JsonElement = getConfig()

        for (part in parts) {
            if (!current.isJsonObject) {
                throw IllegalArgumentException("Path '$path' is invalid: '$part' is not an object")
            }
            current = current.asJsonObject.get(part)
                ?: throw IllegalArgumentException("Path '$path' not found in configuration")
        }

        return current
    }
}
