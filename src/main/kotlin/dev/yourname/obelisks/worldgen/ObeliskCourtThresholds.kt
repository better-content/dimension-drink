package dev.yourname.obelisks.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock

internal fun shouldUseAltarThresholdStair(altarY: Int, outerGroundY: Int?, isActiveEntry: Boolean): Boolean {
    if (!isActiveEntry) return false
    return outerGroundY != null && altarY > outerGroundY
}

private val moddedCourtPotBlocks: List<Block> by lazy {
    BuiltInRegistries.BLOCK.keySet()
        .asSequence()
        .filter { id -> id.namespace != "minecraft" && id.path.startsWith("potted_") }
        .filter { id ->
            val baseId = net.minecraft.resources.ResourceLocation.tryParse("${id.namespace}:${id.path.removePrefix("potted_")}")
                ?: return@filter false
            val baseBlock = BuiltInRegistries.BLOCK.get(baseId)
            baseBlock != Blocks.AIR && isCourtPottablePlant(baseBlock, baseId.path)
        }
        .sortedBy { it.toString() }
        .map { id -> BuiltInRegistries.BLOCK.get(id) }
        .toList()
}

private fun isCourtPottablePlant(block: Block, path: String): Boolean {
    if (path == "dead_bush") return false
    if (block is BushBlock) return true
    return path == "chorus_flower" ||
        path.endsWith("_flower") ||
        path.endsWith("_flowers") ||
        path.endsWith("_mushroom") ||
        path.endsWith("_roots") ||
        path.endsWith("_sapling") ||
        path.endsWith("_bush") ||
        path.endsWith("_shrub") ||
        path.endsWith("_fern") ||
        path.endsWith("_sprout") ||
        path.endsWith("_shoots")
}

internal fun pickCourtPotBlockId(dry: Boolean, pos: BlockPos): String {
    if (dry) return "minecraft:potted_dead_bush"
    val options = moddedCourtPotBlocks
    if (options.isEmpty()) return "minecraft:potted_dead_bush"
    val index = Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7, options.size)
    return BuiltInRegistries.BLOCK.getKey(options[index]).toString()
}

internal fun pickCourtPotBlock(dry: Boolean, pos: BlockPos): Block {
    if (dry) return Blocks.POTTED_DEAD_BUSH
    val options = moddedCourtPotBlocks
    if (options.isEmpty()) return Blocks.POTTED_DEAD_BUSH
    val index = Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7, options.size)
    return options[index]
}
