package dev.yourname.dimensiondrink.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock

internal fun shouldUseAltarUpperThresholdStair(isActiveEntry: Boolean): Boolean =
    isActiveEntry

internal fun shouldUseAltarLowerThresholdStair(altarY: Int, outerGroundY: Int?, isActiveEntry: Boolean): Boolean {
    if (!isActiveEntry) return false
    return outerGroundY != null && altarY > outerGroundY
}

internal fun altarLowerThresholdY(altarY: Int, outerGroundY: Int?, isActiveEntry: Boolean): Int? {
    if (!shouldUseAltarLowerThresholdStair(altarY, outerGroundY, isActiveEntry)) return null
    return altarY
}

internal fun altarApproachStairY(altarY: Int, groundY: Int, maxDrop: Int): Int? {
    val drop = altarY - groundY
    if (drop !in 2..maxDrop) return null
    return groundY + 1
}

private val fallbackModdedCourtPotIds = listOf(
    "hexerei:potted_mandrake_plant",
    "malum:potted_soulwood_sapling",
    "supplementaries:potted_flax"
)

private val moddedCourtPotIds: List<String> by lazy {
    runCatching {
        BuiltInRegistries.BLOCK.keySet()
            .asSequence()
            .filter { id -> id.namespace != "minecraft" && id.path.startsWith("potted_") }
            .filter { id ->
                val baseId = ResourceLocation.tryParse("${id.namespace}:${id.path.removePrefix("potted_")}")
                    ?: return@filter false
                val baseBlock = BuiltInRegistries.BLOCK.get(baseId)
                baseBlock != Blocks.AIR && isCourtPottablePlant(baseBlock, baseId.path)
            }
            .map { it.toString() }
            .sorted()
            .toList()
    }.getOrDefault(emptyList()).ifEmpty { fallbackModdedCourtPotIds }
}

private val moddedCourtPotBlocks: List<Block> by lazy {
    moddedCourtPotIds.mapNotNull { id ->
        ResourceLocation.tryParse(id)
            ?.let(BuiltInRegistries.BLOCK::get)
            ?.takeUnless { it == Blocks.AIR }
    }
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
    val options = moddedCourtPotIds
    if (options.isEmpty()) return "minecraft:potted_dead_bush"
    val index = Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7, options.size)
    return options[index]
}

internal fun pickCourtPotBlock(dry: Boolean, pos: BlockPos): Block {
    if (dry) return Blocks.POTTED_DEAD_BUSH
    val options = moddedCourtPotBlocks
    if (options.isEmpty()) return Blocks.POTTED_DEAD_BUSH
    val index = Math.floorMod(pos.x * 37 + pos.z * 19 + pos.y * 7, options.size)
    return options[index]
}
