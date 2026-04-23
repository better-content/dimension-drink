package dev.yourname.obelisks.data

data class ObeliskDefinition(
    val id: String,
    val displayName: String,
    val instanceTemplateId: String = "",
    val targetDimension: String? = null,
    val coordinateScale: Double? = null,
    val spawnSearchRadius: Int? = null,
    val runRadius: Int? = null,
    val scarRadius: Int? = null,
    val scarIntervalTicks: Long? = null,
    val scarColumnsPerInterval: Int? = null,
    val protectedSpawnRadius: Int? = null,
    val requiredNamespace: String? = null,
    val enabled: Boolean = true,
    val worldgenWeight: Double = 1.0,
    val worldgenFamilyId: String? = null,
    val meteorCoreBlock: String? = null,
    val meteorShellBlock: String? = null,
    val useAe2Skystone: Boolean = false,
    val craterFillBlocks: List<String> = listOf("minecraft:gravel", "minecraft:coarse_dirt"),
    val rewardTableId: String = "default"
)
