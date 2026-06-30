package dev.yourname.obelisks.worldgen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimensionalFontWorldgenPlacementTest {
    @Test
    fun placedFeatureRunsEveryChunkAndColumnScanned() {
        val resource = javaClass.classLoader.getResource(
            "data/dimensionalfonts/worldgen/placed_feature/dimensional_font_placed.json"
        )
        val json = assertNotNull(resource).readText()

        assertTrue(
            Regex(""""type"\s*:\s*"minecraft:rarity_filter"""").containsMatchIn(json),
            "Dimensional font worldgen should keep an explicit rarity contract"
        )
        assertTrue(
            Regex(""""chance"\s*:\s*1\b""").containsMatchIn(json),
            "Dimensional font graveyards are chunk-sliced structures, so the placed feature must execute every chunk"
        )
        assertFalse(
            Regex(""""type"\s*:\s*"minecraft:heightmap"""").containsMatchIn(json),
            "The feature scans each selected column itself; a heightmap modifier can bias placement onto foliage"
        )
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
