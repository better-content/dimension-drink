package dev.yourname.obelisks.compat

import dev.yourname.obelisks.MOD_ID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.registries.ForgeRegistries

object StillBeatingHeartCompat {
    const val DATA_TAG: String = "StillBeatingHeartData"

    private val rpgStatsHeartId = ResourceLocation("rpgstats", "still_beating_heart")

    fun isFontHeart(stack: ItemStack): Boolean {
        if (stack.isEmpty || getLevel(stack) <= 0) return false
        val itemId = ForgeRegistries.ITEMS.getKey(stack.item)
        return itemId == rpgStatsHeartId || stack.`is`(fontHeartTag())
    }

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

    private fun fontHeartTag(): TagKey<Item> =
        TagKey.create(ForgeRegistries.ITEMS.registryKey, ResourceLocation(MOD_ID, "font_hearts"))
}
