package dev.yourname.obelisks.data

import com.google.gson.GsonBuilder
import com.mojang.logging.LogUtils
import dev.yourname.obelisks.MOD_ID
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
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
        obeliskDefinitions = loadDirectory(definitionsDir, ObeliskDefinition::class.java)
            .mapNotNull(::normalizeObeliskDefinition)
            .associateBy { it.id }
        rewardTables = loadDirectory(rewardsDir, RewardTableDefinition::class.java)
            .mapNotNull(::normalizeRewardTable)
            .associateBy { it.id }
        worldgenFamilies = loadDirectory(worldgenFamiliesDir, WorldgenFamilyDefinition::class.java)
            .mapNotNull(::normalizeWorldgenFamily)
            .associateBy { it.id }
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

    fun getRewardTable(id: String?): RewardTableDefinition? {
        ensureLoaded()
        return rewardTables[id ?: return null]
    }

    fun configRootPath(): Path = configRoot

    fun definitionsPath(): Path = definitionsDir

    fun rewardsPath(): Path = rewardsDir

    fun worldgenFamiliesPath(): Path = worldgenFamiliesDir

    fun getWorldgenFamily(id: String?): WorldgenFamilyDefinition? {
        ensureLoaded()
        return worldgenFamilies[id ?: return null]
    }

    private fun normalizeObeliskDefinition(definition: ObeliskDefinition): ObeliskDefinition? {
        val id = stringOrNull(definition.id)
        if (id == null) {
            logger.warn("Ignoring obelisk definition with missing id")
            return null
        }
        val requiredNamespace = stringOrNull(definition.requiredNamespace)
        if (requiredNamespace != null && !namespaceAvailable(requiredNamespace)) {
            logger.info("Skipping obelisk definition {} because required namespace {} is unavailable", id, requiredNamespace)
            return null
        }
        return definition.copy(
            id = id,
            displayName = stringOrNull(definition.displayName) ?: id.replaceFirstChar { it.uppercase() },
            instanceTemplateId = stringOrNull(definition.instanceTemplateId) ?: id,
            requiredNamespace = requiredNamespace,
            craterFillBlocks = definition.craterFillBlocks.orEmpty().ifEmpty {
                listOf("minecraft:gravel", "minecraft:coarse_dirt")
            },
            rewardTableId = stringOrNull(definition.rewardTableId) ?: "default"
        )
    }

    private fun normalizeRewardTable(table: RewardTableDefinition): RewardTableDefinition? {
        val id = stringOrNull(table.id)
        if (id == null) {
            logger.warn("Ignoring reward table with missing id")
            return null
        }
        return table.copy(
            id = id,
            pools = table.pools.orEmpty().mapNotNull poolLoop@ { pool ->
                val poolId = stringOrNull(pool.id) ?: return@poolLoop null
                pool.copy(
                    id = poolId,
                    chance = pool.chance.coerceIn(0.0, 1.0),
                    entries = pool.entries.orEmpty().mapNotNull entryLoop@ { entry ->
                        val item = stringOrNull(entry.item) ?: return@entryLoop null
                        entry.copy(
                            item = item,
                            minCount = entry.minCount.coerceAtLeast(1),
                            maxCount = entry.maxCount.coerceAtLeast(entry.minCount.coerceAtLeast(1)),
                            weight = entry.weight.coerceAtLeast(0)
                        )
                    }
                )
            }
        )
    }

    private fun normalizeWorldgenFamily(family: WorldgenFamilyDefinition): WorldgenFamilyDefinition? {
        val id = stringOrNull(family.id)
        if (id == null) {
            logger.warn("Ignoring worldgen family with missing id")
            return null
        }
        return family.copy(
            id = id,
            siteShape = stringOrNull(family.siteShape) ?: "meteor",
            craterRadiusMin = family.craterRadiusMin.coerceAtLeast(0),
            craterRadiusMax = family.craterRadiusMax.coerceAtLeast(family.craterRadiusMin.coerceAtLeast(0)),
            craterDepthMin = family.craterDepthMin.coerceAtLeast(0),
            craterDepthMax = family.craterDepthMax.coerceAtLeast(family.craterDepthMin.coerceAtLeast(0)),
            coreRadiusMin = family.coreRadiusMin.coerceAtLeast(1),
            coreRadiusMax = family.coreRadiusMax.coerceAtLeast(family.coreRadiusMin.coerceAtLeast(1)),
            shellIntegrity = family.shellIntegrity.coerceIn(0.0, 1.0),
            debrisRadius = family.debrisRadius.coerceAtLeast(0),
            debrisChance = family.debrisChance.coerceIn(0.0, 1.0),
            pillarCountMin = family.pillarCountMin.coerceAtLeast(0),
            pillarCountMax = family.pillarCountMax.coerceAtLeast(family.pillarCountMin.coerceAtLeast(0)),
            pillarHeightMin = family.pillarHeightMin.coerceAtLeast(0),
            pillarHeightMax = family.pillarHeightMax.coerceAtLeast(family.pillarHeightMin.coerceAtLeast(0))
        )
    }

    private fun stringOrNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun namespaceAvailable(namespace: String): Boolean {
        return namespace in ALWAYS_AVAILABLE_NAMESPACES || ModList.get().isLoaded(namespace)
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

    private val ALWAYS_AVAILABLE_NAMESPACES = setOf("minecraft", "forge", MOD_ID, "instanceddimensions")
}
