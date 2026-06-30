package dev.yourname.obelisks.worldgen

import dev.yourname.obelisks.worldgen.village.VillageShrinePools
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagParser
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VillageFontShrineTest {
    @Test
    fun shrineTemplateMatchesOriginalAltarLayoutUsingWaxedPristineCopper() {
        val root = loadShrineTemplate()
        val template = loadTemplateState(root)
        val size = root.getList("size", Tag.TAG_INT.toInt())

        assertEquals(7, size.getInt(0), "Village shrine should keep the original altar width")
        assertEquals(5, size.getInt(1), "Village shrine should keep the original altar height")
        assertEquals(7, size.getInt(2), "Village shrine should keep the original altar depth")
        assertEquals(BlockStateData("dimensionalfonts:dimensional_font"), template[BlockPos3(3, 3, 3)], "Village shrine should place the font at the altar center")
        assertEquals(1, template.values.count { it.name == "dimensionalfonts:dimensional_font" }, "Village shrine should contain exactly one dimensional font")

        for (dx in -3..3) {
            for (dz in -3..3) {
                val state = template[BlockPos3(3 + dx, 0, 3 + dz)]
                assertTrue(state != null, "Village shrine should preserve the original broad lower altar step at dx=$dx dz=$dz")
            }
        }
        for (dx in -2..2) {
            for (dz in -2..2) {
                val state = template[BlockPos3(3 + dx, 1, 3 + dz)]
                assertTrue(state != null, "Village shrine should preserve the original elevated middle altar tier at dx=$dx dz=$dz")
            }
        }

        assertEquals(BlockStateData("minecraft:packed_mud"), template[BlockPos3(3, 1, 1)])
        assertEquals(BlockStateData("minecraft:packed_mud"), template[BlockPos3(3, 1, 5)])
        assertEquals(BlockStateData("minecraft:packed_mud"), template[BlockPos3(1, 1, 3)])
        assertEquals(BlockStateData("minecraft:packed_mud"), template[BlockPos3(5, 1, 3)])

        assertEquals(stairs("south"), template[BlockPos3(3, 1, 0)])
        assertEquals(stairs("north"), template[BlockPos3(3, 1, 6)])
        assertEquals(stairs("east"), template[BlockPos3(0, 1, 3)])
        assertEquals(stairs("west"), template[BlockPos3(6, 1, 3)])

        assertEquals(BlockStateData("minecraft:waxed_copper_block"), template[BlockPos3(3, 2, 3)], "Village shrine should keep the original center pedestal")

        listOf(BlockPos3(1, 2, 1), BlockPos3(1, 2, 5), BlockPos3(5, 2, 1), BlockPos3(5, 2, 5)).forEach { pos ->
            assertEquals(warpedStem("y"), template[pos], "Village shrine should keep the original warped-stem support posts at $pos")
            assertEquals(warpedStem("y"), template[pos.copy(y = 3)], "Village shrine should keep the original warped-stem support posts at ${pos.copy(y = 3)}")
        }
        listOf(BlockPos3(0, 3, 1), BlockPos3(0, 3, 5), BlockPos3(6, 3, 1), BlockPos3(6, 3, 5)).forEach { pos ->
            assertEquals(warpedStem("x"), template[pos], "Village shrine should keep the original outward lantern brackets at $pos")
            assertEquals(BlockStateData("minecraft:soul_lantern", mapOf("hanging" to "true", "waterlogged" to "false")), template[pos.copy(y = 2)], "Village shrine should keep the hanging lanterns under the outer brackets")
        }

        listOf(BlockPos3(1, 4, 1), BlockPos3(1, 4, 5), BlockPos3(5, 4, 1), BlockPos3(5, 4, 5)).forEach { pos ->
            assertEquals(slab(), template[pos], "Village shrine should keep the original corner roof slabs at $pos")
        }
        for ((pos, facing) in roofStairPositions()) {
            assertEquals(stairs(facing), template[pos], "Village shrine should keep the original stair roof ring at $pos")
        }

        val allowedCopper = setOf(
            "minecraft:waxed_copper_block",
            "minecraft:waxed_cut_copper",
            "minecraft:waxed_cut_copper_stairs",
            "minecraft:waxed_cut_copper_slab"
        )
        val copperBlocks = template.values.map { it.name }.filter { "copper" in it }
        assertTrue(copperBlocks.isNotEmpty(), "Village shrine should visibly use maintained waxed copper")
        assertTrue(copperBlocks.all { it in allowedCopper }, "Village shrine should use only maintained waxed fresh copper variants")
        assertTrue(template.values.none { it.name == "minecraft:stone_bricks" }, "Village shrine should not leave the altar shell in abandoned stone")
    }

    @Test
    fun shrineTemplateAvoidsVillagePoisAndGraveyardBlocks() {
        val root = loadShrineTemplate()
        val template = loadTemplateState(root)
        val blockNames = template.values.map { it.name }

        val forbiddenNames = setOf(
            "minecraft:bell",
            "dimensionalfonts:grave_soil",
            "minecraft:lectern",
            "minecraft:loom",
            "minecraft:smithing_table",
            "minecraft:stonecutter",
            "minecraft:grindstone",
            "minecraft:blast_furnace",
            "minecraft:smoker",
            "minecraft:cartography_table",
            "minecraft:barrel",
            "minecraft:fletching_table",
            "minecraft:cauldron",
            "minecraft:brewing_stand",
            "minecraft:composter"
        )
        assertTrue(blockNames.none { it in forbiddenNames }, "Village shrine should not add bells, workstations, or graveyard blocks")
        assertTrue(blockNames.none { it.endsWith("_bed") }, "Village shrine should not contain beds")
    }

    @Test
    fun shrinePoolTargetsStayDecorOnlyAndModeledRarityStaysInBand() {
        assertEquals(setOf("plains", "desert", "savanna", "snowy", "taiga"), VillageShrinePools.TARGETS.map { it.style }.toSet())
        assertTrue(VillageShrinePools.TARGETS.all { it.poolId.namespace == "minecraft" && it.poolId.path.endsWith("/decor") }, "Shrine should append only to vanilla decor pools")

        val decorAttemptWindows = listOf(8, 12, 16, 20)
        VillageShrinePools.TARGETS.forEach { target ->
            val attemptRate = target.estimatedAttemptRate()
            assertTrue(attemptRate in 0.0019..0.0031, "Configured per-attempt shrine rate drifted for ${target.style}: $attemptRate")

            decorAttemptWindows.forEach { attempts ->
                val expectedVillageRate = 1.0 - (1.0 - attemptRate).pow(attempts.toDouble())
                assertTrue(expectedVillageRate in 0.015..0.06, "Modeled village shrine rate for ${target.style} fell outside the target band with $attempts decor attempts: $expectedVillageRate")
            }
        }

        val sampledRate = sampleVillageRate(VillageShrinePools.TARGETS.first(), villages = 20_000, decorAttempts = 12, seed = 90210L)
        assertTrue(sampledRate in 0.02..0.05, "Deterministic shrine sampling should stay close to 1 shrine per 20-50 villages, observed=$sampledRate")
    }

    private fun loadShrineTemplate(): CompoundTag {
        val resource = javaClass.classLoader.getResource("data/dimensionalfonts/structures/village/font_shrine.snbt")
            ?: error("Missing village shrine template")
        return TagParser.parseTag(resource.readText())
    }

    private fun loadTemplateState(root: CompoundTag): Map<BlockPos3, BlockStateData> {
        val palette = root.getList("palette", Tag.TAG_COMPOUND.toInt())
        val blocks = root.getList("blocks", Tag.TAG_COMPOUND.toInt())
        val paletteStates = (0 until palette.size).map { index ->
            val state = palette.getCompound(index)
            val properties = if (state.contains("Properties", Tag.TAG_COMPOUND.toInt())) {
                val props = state.getCompound("Properties")
                props.allKeys.associateWith(props::getString)
            } else {
                emptyMap()
            }
            BlockStateData(state.getString("Name"), properties)
        }

        return buildMap {
            for (index in 0 until blocks.size) {
                val block = blocks.getCompound(index)
                val pos = block.getList("pos", Tag.TAG_INT.toInt())
                put(BlockPos3(pos.getInt(0), pos.getInt(1), pos.getInt(2)), paletteStates[block.getInt("state")])
            }
        }
    }

    private fun stairs(facing: String) = BlockStateData(
        "minecraft:waxed_cut_copper_stairs",
        mapOf("facing" to facing, "half" to "bottom", "shape" to "straight", "waterlogged" to "false")
    )

    private fun slab() = BlockStateData(
        "minecraft:waxed_cut_copper_slab",
        mapOf("type" to "bottom", "waterlogged" to "false")
    )

    private fun warpedStem(axis: String) = BlockStateData("minecraft:stripped_warped_stem", mapOf("axis" to axis))

    private fun roofStairPositions(): List<Pair<BlockPos3, String>> =
        buildList {
            for (x in 2..4) {
                add(BlockPos3(x, 4, 1) to "north")
                add(BlockPos3(x, 4, 5) to "south")
            }
            for (z in 2..4) {
                add(BlockPos3(1, 4, z) to "west")
                add(BlockPos3(5, 4, z) to "east")
            }
        }

    private fun sampleVillageRate(
        target: VillageShrinePools.ShrinePoolTarget,
        villages: Int,
        decorAttempts: Int,
        seed: Long
    ): Double {
        val random = Random(seed)
        var shrineVillages = 0
        val selectionBound = target.basePoolWeight + 1
        repeat(villages) {
            var foundShrine = false
            for (attempt in 0 until decorAttempts) {
                if (random.nextInt(selectionBound) == 0 && random.nextDouble() <= target.placementChance.toDouble()) {
                    foundShrine = true
                    break
                }
            }
            if (foundShrine) {
                shrineVillages += 1
            }
        }
        return shrineVillages.toDouble() / villages.toDouble()
    }

    private data class BlockPos3(val x: Int, val y: Int, val z: Int)

    private data class BlockStateData(
        val name: String,
        val properties: Map<String, String> = emptyMap()
    )
}
