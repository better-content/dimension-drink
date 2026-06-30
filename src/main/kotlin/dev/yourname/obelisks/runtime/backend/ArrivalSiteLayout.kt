package dev.yourname.obelisks.runtime.backend

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block

internal object ArrivalSiteLayout {
    const val FLOOR_RADIUS = 2
    const val CLEARANCE_HEIGHT = 3
    private val verdigrisCopperIds = setOf(
        "minecraft:oxidized_copper",
        "minecraft:oxidized_cut_copper",
        "minecraft:oxidized_cut_copper_stairs",
        "minecraft:oxidized_cut_copper_slab"
    )

    private val floorOffsets = buildList {
        for (dx in -FLOOR_RADIUS..FLOOR_RADIUS) {
            for (dz in -FLOOR_RADIUS..FLOOR_RADIUS) {
                add(BlockPos(dx, 0, dz))
            }
        }
    }

    private val scatterOffsets = floorOffsets.filter { offset ->
        (offset.x != 0 || offset.z != 0) && maxOf(kotlin.math.abs(offset.x), kotlin.math.abs(offset.z)) == FLOOR_RADIUS
    }

    fun floorOffsets(): List<BlockPos> = floorOffsets

    fun scatterOffsets(): List<BlockPos> = scatterOffsets

    fun verdigrisCopperIds(): Set<String> = verdigrisCopperIds

    fun isVerdigrisCopper(block: Block): Boolean =
        BuiltInRegistries.BLOCK.getKey(block)?.toString() in verdigrisCopperIds
}
