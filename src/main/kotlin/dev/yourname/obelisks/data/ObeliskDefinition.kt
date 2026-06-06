package dev.yourname.obelisks.data

data class ObeliskDefinition(
    val id: String,
    val displayName: String,
    val instanceTemplateId: String = "",
    val targetDimension: String? = null,
    val coordinateScale: Double? = null,
    val spawnSearchRadius: Int? = null,
    val runRadius: Int? = null,
    val maxBlood: Double? = null,
    val bloodStartCost: Double? = null,
    val bloodJoinCost: Double? = null,
    val baseBloodPerTick: Double? = null,
    val heartBloodMultiplier: Double? = null,
    val runBloodDrainPerTick: Double? = null,
    val requiredNamespace: String? = null,
    val enabled: Boolean = true,
    val worldgenWeight: Double = 1.0,
    val worldgenFamilyId: String? = null,
    val rewardTableId: String = "default"
)
