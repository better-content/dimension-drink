package dev.yourname.obelisks.player

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import java.util.*

/**
 * Stores information about a player's current run participation.
 */
data class PlayerRunInfo(
    var originObeliskId: UUID? = null,
    var originPos: BlockPos? = null,
    var originDimension: ResourceKey<Level>? = null,
    var runId: Long? = null,
    var runDimensionKey: ResourceKey<Level>? = null
) {
    fun isInRun(): Boolean = runId != null && runDimensionKey != null

    fun clear() {
        originObeliskId = null
        originPos = null
        originDimension = null
        runId = null
        runDimensionKey = null
    }

    fun toNbt(): CompoundTag {
        val tag = CompoundTag()
        originObeliskId?.let { tag.putUUID("OriginObeliskId", it) }
        originPos?.let { tag.putLong("OriginPos", it.asLong()) }
        originDimension?.let { tag.putString("OriginDim", it.location().toString()) }
        runId?.let { tag.putLong("RunId", it) }
        runDimensionKey?.let { tag.putString("RunDimKey", it.location().toString()) }
        return tag
    }

    companion object {
        fun fromNbt(tag: CompoundTag): PlayerRunInfo {
            val info = PlayerRunInfo()

            if (tag.contains("OriginObeliskId")) {
                info.originObeliskId = tag.getUUID("OriginObeliskId")
            }
            if (tag.contains("OriginPos")) {
                info.originPos = BlockPos.of(tag.getLong("OriginPos"))
            }
            if (tag.contains("OriginDim")) {
                info.originDimension = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation(tag.getString("OriginDim"))
                )
            }
            if (tag.contains("RunId")) {
                info.runId = tag.getLong("RunId")
            }
            if (tag.contains("RunDimKey")) {
                info.runDimensionKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation(tag.getString("RunDimKey"))
                )
            }

            return info
        }
    }
}
