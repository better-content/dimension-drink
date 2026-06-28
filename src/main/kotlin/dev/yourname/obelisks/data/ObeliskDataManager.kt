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
    private val definitionsDir: Path = configRoot.resolve("fonts")
    private val rewardsDir: Path = configRoot.resolve("rewards")
    private val worldgenFamiliesDir: Path = configRoot.resolve("site_families")

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
            "Loaded {} font definitions, {} reward tables, and {} site families",
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
            logger.warn("Ignoring font definition with missing id")
            return null
        }
        val requiredNamespace = stringOrNull(definition.requiredNamespace)
        if (requiredNamespace != null && !namespaceAvailable(requiredNamespace)) {
            logger.info("Skipping font definition {} because required namespace {} is unavailable", id, requiredNamespace)
            return null
        }
        val legacyTargetId = stringOrNull(definition.instanceTemplateId) ?: id
        val targetDimension = stringOrNull(definition.targetDimension)
            ?: CanonicalTargetResolver.targetId(definition.copy(instanceTemplateId = legacyTargetId))
        return definition.copy(
            id = id,
            displayName = stringOrNull(definition.displayName) ?: id.replaceFirstChar { it.uppercase() },
            instanceTemplateId = targetDimension,
            targetDimension = targetDimension,
            coordinateScale = (definition.coordinateScale ?: CanonicalTargetResolver.defaultCoordinateScale(legacyTargetId)).coerceAtLeast(0.0),
            spawnSearchRadius = definition.spawnSearchRadius?.coerceIn(0, 128) ?: 16,
            runRadius = definition.runRadius?.coerceIn(16, 512) ?: 96,
            maxBlood = definition.maxBlood?.coerceIn(1.0, 1_000_000.0) ?: 15_000.0,
            bloodStartCost = definition.bloodStartCost?.coerceAtLeast(0.0) ?: 5_000.0,
            bloodJoinCost = definition.bloodJoinCost?.coerceAtLeast(0.0) ?: 1_000.0,
            baseBloodPerTick = definition.baseBloodPerTick?.coerceAtLeast(0.0) ?: 0.25,
            heartBloodMultiplier = definition.heartBloodMultiplier?.coerceAtLeast(0.0) ?: 0.08,
            runBloodDrainPerTick = definition.runBloodDrainPerTick?.coerceAtLeast(0.0) ?: 20.0,
            requiredNamespace = requiredNamespace,
            worldgenFamilyId = stringOrNull(definition.worldgenFamilyId) ?: "altar",
            rewardTableId = stringOrNull(definition.rewardTableId) ?: "default",
            graveyardPalette = definition.graveyardPalette?.let { palette ->
                palette.copy(
                    pathBlocks = sanitizeIdList(palette.pathBlocks),
                    graveBlocks = sanitizeIdList(palette.graveBlocks),
                    structureBlocks = sanitizeIdList(palette.structureBlocks),
                    decorations = sanitizeIdList(palette.decorations),
                    trophyBlocks = sanitizeIdList(palette.trophyBlocks),
                    pedestalBlock = stringOrNull(palette.pedestalBlock)
                )
            },
            pathBlocks = sanitizeIdList(definition.pathBlocks),
            graveBlocks = sanitizeIdList(definition.graveBlocks),
            structureBlocks = sanitizeIdList(definition.structureBlocks),
            decorations = sanitizeIdList(definition.decorations),
            trophyBlocks = sanitizeIdList(definition.trophyBlocks),
            pedestalBlock = stringOrNull(definition.pedestalBlock),
            meteorCoreBlock = stringOrNull(definition.meteorCoreBlock),
            meteorShellBlock = stringOrNull(definition.meteorShellBlock),
            craterFillBlocks = sanitizeIdList(definition.craterFillBlocks)
        )
    }

    private fun sanitizeIdList(values: List<String>?): List<String>? =
        values.orEmpty().mapNotNull(::stringOrNull).takeIf { it.isNotEmpty() }

    private fun normalizeRewardTable(table: RewardTableDefinition): RewardTableDefinition? {
        val id = stringOrNull(table.id)
        if (id == null) {
            logger.warn("Ignoring reward table with missing id")
            return null
        }
        return table.copy(
            id = id,
            killCurrency = table.killCurrency?.let { currency ->
                val item = stringOrNull(currency.item)
                if (item == null) {
                    null
                } else {
                    currency.copy(
                        item = item,
                        perKill = currency.perKill.coerceAtLeast(1),
                        burstSize = currency.burstSize.coerceAtLeast(1)
                    )
                }
            },
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
            siteShape = stringOrNull(family.siteShape) ?: "altar"
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
        copyDefaultFolder("defaults/fonts", definitionsDir)
        copyDefaultFolder("defaults/rewards", rewardsDir)
        copyDefaultFolder("defaults/site_families", worldgenFamiliesDir)
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

    private val ALWAYS_AVAILABLE_NAMESPACES = setOf("minecraft", "forge", MOD_ID)
}
