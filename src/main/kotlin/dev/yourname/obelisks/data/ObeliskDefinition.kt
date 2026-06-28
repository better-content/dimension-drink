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
    val rewardTableId: String = "default",
    val graveyardPalette: GraveyardPaletteDefinition? = null,
    val pathBlocks: List<String>? = null,
    val graveBlocks: List<String>? = null,
    val structureBlocks: List<String>? = null,
    val decorations: List<String>? = null,
    val trophyBlocks: List<String>? = null,
    val pedestalBlock: String? = null,
    val meteorCoreBlock: String? = null,
    val meteorShellBlock: String? = null,
    val craterFillBlocks: List<String>? = null
)

data class GraveyardPaletteDefinition(
    val pathBlocks: List<String>? = null,
    val graveBlocks: List<String>? = null,
    val structureBlocks: List<String>? = null,
    val decorations: List<String>? = null,
    val trophyBlocks: List<String>? = null,
    val pedestalBlock: String? = null
)
