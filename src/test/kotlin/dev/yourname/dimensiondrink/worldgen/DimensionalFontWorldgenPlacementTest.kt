package dev.yourname.dimensiondrink.worldgen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimensionalFontWorldgenPlacementTest {
    @Test
    fun structureSetOwnsNaturalDimensionalFontPlacement() {
        val structureSet = assertNotNull(
            javaClass.classLoader.getResource("data/dimensiondrink/worldgen/structure_set/dimensional_fonts.json")
        ).readText()
        val structure = assertNotNull(
            javaClass.classLoader.getResource("data/dimensiondrink/worldgen/structure/dimensional_font.json")
        ).readText()
        val biomeTag = assertNotNull(
            javaClass.classLoader.getResource("data/dimensiondrink/tags/worldgen/biome/has_structure/dimensional_font.json")
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
            Regex(""""type"\s*:\s*"dimensiondrink:dimensional_font"""").containsMatchIn(structure),
            "Natural dimensional font placement should use the custom structure type"
        )
        assertTrue(
            Regex(""""biomes"\s*:\s*"#dimensiondrink:has_structure/dimensional_font"""").containsMatchIn(structure),
            "Natural dimensional font placement should use the curated land-biome tag, not all Overworld biomes"
        )
        assertFalse(
            Regex("ocean|river|beach", RegexOption.IGNORE_CASE).containsMatchIn(biomeTag),
            "Dimensional font structure biome tag must not include oceans, rivers, beaches, or their variants"
        )
        assertFalse(javaClass.classLoader.getResource("data/dimensiondrink/worldgen/placed_feature/dimensional_font_placed.json") != null)
        assertFalse(javaClass.classLoader.getResource("data/dimensiondrink/worldgen/configured_feature/dimensional_font.json") != null)
        assertFalse(javaClass.classLoader.getResource("data/dimensiondrink/forge/biome_modifier/add_dimensional_font.json") != null)
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

    @Test
    fun defaultFontDecorationsKeepAzaleasPotted() {
        listOf("overworld", "otherside").forEach { fontId ->
            val definition = assertNotNull(
                javaClass.classLoader.getResource("defaults/fonts/$fontId.json")
            ).readText()
            assertFalse(
                Regex(""""minecraft:flowering_azalea"""").containsMatchIn(definition),
                "Default $fontId font decorations should not place raw azalea bushes"
            )
            assertTrue(
                Regex(""""minecraft:potted_flowering_azalea_bush"""").containsMatchIn(definition),
                "Default $fontId font decorations should use potted flowering azalea bushes"
            )
        }
    }
}
