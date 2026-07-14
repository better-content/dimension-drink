package dev.yourname.dimensiondrink.compat

import dev.yourname.dimensiondrink.MOD_ID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object StillBeatingHeartCompat {
    const val DATA_TAG: String = "StillBeatingHeartData"
    private const val BASE_LP_PER_TICK: Int = 5
    private const val LEVELS_PER_DOUBLING: Double = 10.0
    private const val MAX_LP_PER_TICK: Int = 4096

    private val rpgStatsHeartId = ResourceLocation("rpgstats", "still_beating_heart")

    fun isFontHeart(stack: ItemStack): Boolean {
        if (stack.isEmpty || getLevel(stack) <= 0) return false
        val itemId = ForgeRegistries.ITEMS.getKey(stack.item)
        return itemId == rpgStatsHeartId || stack.`is`(fontHeartTag())
    }

    fun hasHeartData(stack: ItemStack): Boolean {
        return hasHeartDataTag(stack.tag)
    }

    fun hasHeartDataTag(tag: CompoundTag?): Boolean =
        tag?.contains(DATA_TAG, Tag.TAG_COMPOUND.toInt()) == true

    fun getLevel(stack: ItemStack): Int {
        val tag = stack.tag ?: return 0
        if (!tag.contains(DATA_TAG, Tag.TAG_COMPOUND.toInt())) return 0
        return getLevel(tag.getCompound(DATA_TAG))
    }

    fun getLevel(data: CompoundTag): Int {
        return when {
            data.contains("level", Tag.TAG_INT.toInt()) -> data.getInt("level")
            data.contains("player", Tag.TAG_COMPOUND.toInt()) -> data.getCompound("player").getInt("experience_level")
            else -> 0
        }.coerceAtLeast(0)
    }

    fun bloodMultiplier(stack: ItemStack, perLevelMultiplier: Double): Double =
        bloodMultiplier(getLevel(stack), perLevelMultiplier)

    fun bloodMultiplier(level: Int, perLevelMultiplier: Double): Double =
        1.0 + (level.coerceAtLeast(0) * perLevelMultiplier.coerceAtLeast(0.0))

    fun lpPerTick(stack: ItemStack): Int = lpPerTick(getLevel(stack))

    fun lpPerTick(level: Int): Int {
        if (level <= 0) return 0
        val scaled = BASE_LP_PER_TICK * 2.0.pow(max(0, level) / LEVELS_PER_DOUBLING)
        return min(MAX_LP_PER_TICK, max(1, scaled.toInt()))
    }

    private fun fontHeartTag(): TagKey<Item> =
        TagKey.create(ForgeRegistries.ITEMS.registryKey, ResourceLocation(MOD_ID, "font_hearts"))
}
