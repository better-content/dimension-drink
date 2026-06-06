package dev.yourname.obelisks.data

data class WorldgenFamilyDefinition(
    val id: String,
    val enabled: Boolean = true,
    val siteShape: String = "altar"
)
