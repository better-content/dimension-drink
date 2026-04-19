package dev.yourname.obelisks.data

data class ObeliskDefinition(
    val id: String,
    val displayName: String,
    val instanceTemplateId: String,
    val requiredNamespace: String? = null,
    val enabled: Boolean = true,
    val worldgenWeight: Double = 1.0,
    val craterFillBlocks: List<String> = listOf("minecraft:gravel", "minecraft:coarse_dirt"),
    val rewardTableId: String = "default"
)
