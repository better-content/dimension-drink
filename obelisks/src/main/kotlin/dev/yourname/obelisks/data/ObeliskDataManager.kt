package dev.yourname.obelisks.data

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.mojang.logging.LogUtils
import dev.yourname.obelisks.MOD_ID
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.random.Random

object ObeliskDataManager {
    private val logger = LogUtils.getLogger()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val configRoot: Path = FMLPaths.CONFIGDIR.get().resolve(MOD_ID)
    private val definitionsDir: Path = configRoot.resolve("obelisks")
    private val rewardsDir: Path = configRoot.resolve("rewards")
    private val worldgenFamiliesDir: Path = configRoot.resolve("worldgen_families")

    @Volatile private var loaded = false
    private var obeliskDefinitions: Map<String, ObeliskDefinition> = emptyMap()
    private var rewardTables: Map<String, RewardTableDefinition> = emptyMap()
    private var worldgenFamilies: Map<String, WorldgenFamilyDefinition> = emptyMap()

    fun ensureLoaded() {
        if (loaded) return
        reload()
    }

    @Synchronized
    fun reload() {
        copyDefaultsIfMissing()
        obeliskDefinitions = loadDirectory(definitionsDir, ObeliskDefinition::class.java).associateBy { it.id }
        rewardTables = loadDirectory(rewardsDir, RewardTableDefinition::class.java).associateBy { it.id }
        worldgenFamilies = loadDirectory(worldgenFamiliesDir, WorldgenFamilyDefinition::class.java).associateBy { it.id }
        loaded = true
        logger.info(
            "Loaded {} obelisk definitions, {} reward tables, and {} worldgen families",
            obeliskDefinitions.size,
            rewardTables.size,
            worldgenFamilies.size
        )
    }

    fun allObelisks(): Collection<ObeliskDefinition> {
        ensureLoaded()
        return obeliskDefinitions.values
    }

    fun getObelisk(id: String): ObeliskDefinition? {
        ensureLoaded()
        return obeliskDefinitions[id]
    }

    fun enabledObelisks(): List<ObeliskDefinition> {
        ensureLoaded()
        return obeliskDefinitions.values.filter { it.enabled }
    }

    fun pickRandomObelisk(random: Random = Random.Default): ObeliskDefinition? {
        val enabled = enabledObelisks().filter { it.worldgenWeight > 0.0 }
        if (enabled.isEmpty()) return null
        val total = enabled.sumOf { it.worldgenWeight }
        var cursor = random.nextDouble(total)
        for (definition in enabled) {
            cursor -= definition.worldgenWeight
            if (cursor <= 0.0) return definition
        }
        return enabled.last()
    }

    fun getRewardTable(id: String): RewardTableDefinition? {
        ensureLoaded()
        return rewardTables[id]
    }

    fun configRootPath(): Path = configRoot

    fun definitionsPath(): Path = definitionsDir

    fun rewardsPath(): Path = rewardsDir

    fun worldgenFamiliesPath(): Path = worldgenFamiliesDir

    fun getWorldgenFamily(id: String): WorldgenFamilyDefinition? {
        ensureLoaded()
        return worldgenFamilies[id]
    }

    private fun copyDefaultsIfMissing() {
        definitionsDir.createDirectories()
        rewardsDir.createDirectories()
        worldgenFamiliesDir.createDirectories()
        copyDefaultFolder("defaults/obelisks", definitionsDir)
        copyDefaultFolder("defaults/rewards", rewardsDir)
        copyDefaultFolder("defaults/worldgen_families", worldgenFamiliesDir)
    }

    private fun copyDefaultFolder(resourceFolder: String, targetDir: Path) {
        val listingStream = javaClass.classLoader.getResourceAsStream("$resourceFolder/.index") ?: return
        listingStream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() }
                .forEach { fileName ->
                    val target = targetDir.resolve(fileName)
                    if (target.exists()) return@forEach
                    val resource = javaClass.classLoader.getResourceAsStream("$resourceFolder/$fileName") ?: return@forEach
                    resource.use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
        }
    }

    private fun <T> loadDirectory(dir: Path, type: Class<T>): List<T> {
        if (!dir.exists()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { path -> path.name.endsWith(".json") }
                .sorted()
                .map { path ->
                    path.inputStream().use { input ->
                        gson.fromJson(input.reader(), type)
                    }
                }
                .toList()
        }
    }
}
