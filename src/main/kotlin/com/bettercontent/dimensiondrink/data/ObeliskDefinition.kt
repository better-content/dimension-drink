package com.bettercontent.dimensiondrink.data

data class ObeliskDefinition(
    val id: String,
    val displayName: String,
    val instanceTemplateId: String = "",
    val targetDimension: String? = null,
    val coordinateScale: Double? = null,
    val spawnSearchRadius: Int? = null,
    val runRadius: Int? = null,
    val maxCharge: Double? = null,
    val startChargeCost: Double? = null,
    val joinChargeCost: Double? = null,
    val passiveChargePerTick: Double? = null,
    val runBaseChargePerSecond: Double? = null,
    val runPlayerChargePerSecond: Double? = null,
    val requiredNamespace: String? = null,
    val enabled: Boolean = true,
    val worldgenWeight: Double = 1.0,
    val worldgenFamilyId: String? = null,
    val rewardTableId: String = "default",
    val cultivationPalette: CultivationPaletteDefinition? = null,
    val graveyardPalette: CultivationPaletteDefinition? = null,
    val pathBlocks: List<String>? = null,
    val cultivationBlocks: List<String>? = null,
    val graveBlocks: List<String>? = null,
    val structureBlocks: List<String>? = null,
    val decorations: List<String>? = null,
    val trophyBlocks: List<String>? = null,
    val pedestalBlock: String? = null,
    val dimensionDrinkCoreBlock: String? = null,
    val dimensionDrinkShellBlock: String? = null,
    val craterFillBlocks: List<String>? = null
)

data class CultivationPaletteDefinition(
    val pathBlocks: List<String>? = null,
    val cultivationBlocks: List<String>? = null,
    val graveBlocks: List<String>? = null,
    val structureBlocks: List<String>? = null,
    val decorations: List<String>? = null,
    val trophyBlocks: List<String>? = null,
    val pedestalBlock: String? = null
)
