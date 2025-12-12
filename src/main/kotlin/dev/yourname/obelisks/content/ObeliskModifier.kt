package dev.yourname.obelisks.content

import net.minecraft.nbt.CompoundTag
import kotlin.random.Random

/**
 * Represents a modifier that affects one of the obelisk's FE-related stats.
 * Each obelisk has 5 modifiers, each providing a 0-50% bonus to a specific stat.
 */
data class ObeliskModifier(
    val stat: FEStat,
    val bonusPercent: Int // 0-50
) {
    /**
     * Applies this modifier's bonus to the given base value.
     * @param baseValue The base stat value before modification
     * @return The modified value with the bonus applied
     */
    fun applyTo(baseValue: Int): Int {
        return (baseValue * (1.0 + bonusPercent / 100.0)).toInt()
    }

    /**
     * Applies this modifier's bonus to the given base value (Double version).
     */
    fun applyTo(baseValue: Double): Double {
        return baseValue * (1.0 + bonusPercent / 100.0)
    }

    fun saveToNBT(tag: CompoundTag) {
        tag.putString("Stat", stat.name)
        tag.putInt("BonusPercent", bonusPercent)
    }

    companion object {
        fun loadFromNBT(tag: CompoundTag): ObeliskModifier {
            val stat = FEStat.valueOf(tag.getString("Stat"))
            val bonusPercent = tag.getInt("BonusPercent")
            return ObeliskModifier(stat, bonusPercent)
        }

        /**
         * Generates 5 random modifiers for a new obelisk.
         * Each modifier affects one FE stat with a 0-50% bonus.
         * Repetition of stats is allowed.
         */
        fun generateModifiers(random: Random = Random.Default): List<ObeliskModifier> {
            return List(5) {
                val stat = FEStat.entries[random.nextInt(FEStat.entries.size)]
                val bonusPercent = random.nextInt(51) // 0-50 inclusive
                ObeliskModifier(stat, bonusPercent)
            }
        }
    }
}

/**
 * FE-related stats that can be modified by obelisk modifiers.
 */
enum class FEStat {
    /** Maximum FE storage capacity */
    MAX_STORAGE,
    
    /** FE regeneration rate when idle */
    REGEN_RATE,
    
    /** Base FE drain per tick */
    BASE_DRAIN,
    
    /** Per-player FE drain */
    PLAYER_DRAIN,
    
    /** Exponential drain factor */
    DRAIN_FACTOR
}
