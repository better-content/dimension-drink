package dev.yourname.obelisks.data

data class WorldgenFamilyDefinition(
    val id: String,
    val enabled: Boolean = true,
    val siteShape: String = "meteor",
    val craterRadiusMin: Int = 5,
    val craterRadiusMax: Int = 7,
    val craterDepthMin: Int = 2,
    val craterDepthMax: Int = 4,
    val coreRadiusMin: Int = 3,
    val coreRadiusMax: Int = 3,
    val shellIntegrity: Double = 0.88,
    val debrisRadius: Int = 0,
    val debrisChance: Double = 0.0,
    val pillarCountMin: Int = 0,
    val pillarCountMax: Int = 0,
    val pillarHeightMin: Int = 2,
    val pillarHeightMax: Int = 5
)
