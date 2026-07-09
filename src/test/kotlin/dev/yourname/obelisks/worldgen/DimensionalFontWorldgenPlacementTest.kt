package dev.yourname.obelisks.worldgen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimensionalFontWorldgenPlacementTest {
    @Test
    fun structureSetOwnsNaturalDimensionalFontPlacement() {
        val structureSet = assertNotNull(
            javaClass.classLoader.getResource("data/dimensionalfonts/worldgen/structure_set/dimensional_fonts.json")
        ).readText()
        val structure = assertNotNull(
            javaClass.classLoader.getResource("data/dimensionalfonts/worldgen/structure/dimensional_font.json")
        ).readText()

        assertTrue(
            Regex(""""type"\s*:\s*"minecraft:random_spread"""").containsMatchIn(structureSet),
            "Dimensional font worldgen should be controlled by vanilla random-spread structure placement"
        )
        assertTrue(
            Regex(""""spacing"\s*:\s*30\b""").containsMatchIn(structureSet),
            "Dimensional font structure rarity should stay explicit and reviewable"
        )
        assertTrue(
            Regex(""""separation"\s*:\s*9\b""").containsMatchIn(structureSet),
            "Dimensional font structures should keep enough separation to avoid overlapping cultivation centers"
        )
        assertTrue(
            Regex(""""type"\s*:\s*"dimensionalfonts:dimensional_font"""").containsMatchIn(structure),
            "Natural dimensional font placement should use the custom structure type"
        )
        assertFalse(javaClass.classLoader.getResource("data/dimensionalfonts/worldgen/placed_feature/dimensional_font_placed.json") != null)
        assertFalse(javaClass.classLoader.getResource("data/dimensionalfonts/worldgen/configured_feature/dimensional_font.json") != null)
        assertFalse(javaClass.classLoader.getResource("data/dimensionalfonts/forge/biome_modifier/add_dimensional_font.json") != null)
    }

    @Test
    fun wetOvergrowthRecognizesModdedWetAndLushBiomeNames() {
        listOf(
            "byg:temperate_rainforest",
            "byg:tropical_rainforest",
            "byg:lush_stacks",
            "byg:cypress_swamplands",
            "terralith:orchid_swamp",
            "biomesoplenty:bayou",
            "biomesoplenty:fen",
            "biomesoplenty:wetland",
            "atmospheric:rainforest",
            "regions_unexplored:willow_forest",
            "minecraft:mushroom_fields"
        ).forEach { id ->
            assertTrue(
                WetBiomeClassifier.matchesId(id),
                "Expected wet overgrowth to recognize $id"
            )
        }

        listOf(
            "minecraft:desert",
            "minecraft:badlands",
            "terralith:volcanic_crater",
            "byg:arid_highlands"
        ).forEach { id ->
            assertFalse(
                WetBiomeClassifier.matchesId(id),
                "Expected wet overgrowth to ignore dry biome $id"
            )
        }
    }
}
