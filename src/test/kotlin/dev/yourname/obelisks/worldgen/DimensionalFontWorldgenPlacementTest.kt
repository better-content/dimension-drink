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
            "Chunk-sliced dimensional font graveyards must run every chunk; the feature applies deterministic 1/64 site rarity internally"
        )
        assertFalse(
            Regex(""""type"\s*:\s*"minecraft:heightmap"""").containsMatchIn(json),
            "The feature scans each selected column itself; a heightmap modifier can bias placement onto foliage"
        )
    }
}
