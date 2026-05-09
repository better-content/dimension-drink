package dev.yourname.obelisks.data

data class RewardTableDefinition(
    val id: String,
    val enabled: Boolean = true,
    val baseRolls: Int = 0,
    val rollsPerKill: Int = 1,
    val damagePerBonusRoll: Float = 20.0f,
    val killCurrency: KillCurrencyDefinition? = null,
    val pools: List<RewardPoolDefinition> = emptyList()
)

data class KillCurrencyDefinition(
    val item: String,
    val perKill: Int = 1,
    val burstSize: Int = 4
)

data class RewardPoolDefinition(
    val id: String,
    val chance: Double = 1.0,
    val entries: List<RewardEntryDefinition> = emptyList()
)

data class RewardEntryDefinition(
    val item: String,
    val minCount: Int = 1,
    val maxCount: Int = 1,
    val weight: Int = 1
)
