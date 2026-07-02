package dev.yourname.obelisks.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

internal fun shouldUseAltarThresholdStair(altarY: Int, outerGroundY: Int?, isActiveEntry: Boolean): Boolean {
    if (!isActiveEntry) return false
    return outerGroundY != null && altarY > outerGroundY
}

internal fun pickCourtPotBlockId(wet: Boolean, pos: BlockPos): String =
    when (Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7 + if (wet) 1 else 0, 3)) {
        0 -> "minecraft:potted_fern"
        1 -> "minecraft:potted_azalea_bush"
        else -> "minecraft:potted_flowering_azalea_bush"
    }

internal fun pickCourtPotBlock(wet: Boolean, pos: BlockPos): Block =
    when (pickCourtPotBlockId(wet, pos)) {
        "minecraft:potted_fern" -> Blocks.POTTED_FERN
        "minecraft:potted_azalea_bush" -> Blocks.POTTED_AZALEA
        "minecraft:potted_flowering_azalea_bush" -> Blocks.POTTED_FLOWERING_AZALEA
        else -> Blocks.POTTED_FERN
    }
