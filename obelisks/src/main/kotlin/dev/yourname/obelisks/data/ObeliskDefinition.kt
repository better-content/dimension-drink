package dev.yourname.obelisks.data

data class ObeliskDefinition(
    val id: String,
    val displayName: String,
    val instanceTemplateId: String,
    val enabled: Boolean = true,
    val worldgenWeight: Double = 1.0,
    val worldgenRarityChance: Int = 200,
    val worldgenFamilyId: String = "meteor",
    val meteorCoreBlock: String = "minecraft:obsidian",
    val meteorShellBlock: String = "minecraft:crying_obsidian",
    val pedestalBlock: String = "minecraft:obsidian",
    val returnPadFrameBlock: String = "minecraft:obsidian",
    val platformFloorBlock: String = "minecraft:bedrock",
    val craterFillBlocks: List<String> = listOf("minecraft:gravel", "minecraft:coarse_dirt"),
    val rewardTableId: String = "default",
    val useAe2Skystone: Boolean = true
)
