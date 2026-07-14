package dev.yourname.dimensiondrink.content

import net.minecraft.nbt.CompoundTag
import kotlin.random.Random

data class ObeliskModifier(
    val stat: FEStat,
    val bonusPercent: Int
) {
    fun applyTo(baseValue: Int): Int = (baseValue * (1.0 + bonusPercent / 100.0)).toInt()
    fun applyTo(baseValue: Double): Double = baseValue * (1.0 + bonusPercent / 100.0)

    fun save(tag: CompoundTag) {
        tag.putString("stat", stat.name)
        tag.putInt("bonus_percent", bonusPercent)
    }

    companion object {
        fun load(tag: CompoundTag): ObeliskModifier {
            return ObeliskModifier(
                stat = FEStat.valueOf(tag.getString("stat")),
                bonusPercent = tag.getInt("bonus_percent")
            )
        }

        fun generateModifiers(random: Random = Random.Default): List<ObeliskModifier> {
            return List(5) {
                ObeliskModifier(
                    stat = FEStat.entries[random.nextInt(FEStat.entries.size)],
                    bonusPercent = random.nextInt(0, 51)
                )
            }
        }
    }
}

enum class FEStat {
    MAX_STORAGE,
    REGEN_RATE,
    BASE_DRAIN,
    PLAYER_DRAIN,
    DRAIN_FACTOR
}
