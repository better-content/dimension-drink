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
    fun shrineTemplateUsesSingleFontMaintainedCopperAndFullSizedAltarFootprint() {
        val root = loadShrineTemplate()
        val palette = root.getList("palette", Tag.TAG_COMPOUND.toInt())
        val blocks = root.getList("blocks", Tag.TAG_COMPOUND.toInt())
        val size = root.getList("size", Tag.TAG_INT.toInt())

        val paletteNames = (0 until palette.size).map { palette.getCompound(it).getString("Name") }
        val blockNames = (0 until blocks.size).map { blockIndex ->
            val stateIndex = blocks.getCompound(blockIndex).getInt("state")
            paletteNames[stateIndex]
        }

        assertTrue(size.getInt(0) >= 9 && size.getInt(2) >= 9, "Village shrine should stay full-sized, not a compact roadside piece")
        assertTrue(size.getInt(1) >= 5, "Village shrine should preserve a stepped altar profile")
        assertEquals(1, blockNames.count { it == "dimensionalfonts:dimensional_font" }, "Village shrine should contain exactly one dimensional font")

        val allowedCopper = setOf(
            "minecraft:waxed_copper_block",
            "minecraft:waxed_cut_copper",
            "minecraft:waxed_cut_copper_stairs",
            "minecraft:waxed_cut_copper_slab"
        )
        val copperBlocks = blockNames.filter { "copper" in it }
        assertTrue(copperBlocks.isNotEmpty(), "Village shrine should visibly use maintained waxed copper")
        assertTrue(copperBlocks.all { it in allowedCopper }, "Village shrine should use only maintained waxed fresh copper variants")
        assertTrue(blockNames.count { it == "minecraft:waxed_copper_block" } >= 100, "Village shrine should present the full altar as actively maintained waxed copper")
        assertTrue(blockNames.none { it == "minecraft:stone_bricks" }, "Village shrine should not leave the altar shell in abandoned stone")
    }

    @Test
    fun shrineTemplateAvoidsVillagePoisAndGraveyardBlocks() {
        val root = loadShrineTemplate()
        val palette = root.getList("palette", Tag.TAG_COMPOUND.toInt())
        val blocks = root.getList("blocks", Tag.TAG_COMPOUND.toInt())
        val paletteNames = (0 until palette.size).map { palette.getCompound(it).getString("Name") }
        val blockNames = (0 until blocks.size).map { index -> paletteNames[blocks.getCompound(index).getInt("state")] }

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
}
