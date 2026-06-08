package dev.yourname.obelisks.worldgen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimensionalFontWorldgenPlacementTest {
    @Test
    fun placedFeatureKeepsFontAttemptsCommonAndColumnScanned() {
        val resource = javaClass.classLoader.getResource(
            "data/dimensionalfonts/worldgen/placed_feature/dimensional_font_placed.json"
        )
        val json = assertNotNull(resource).readText()

        assertTrue(
            Regex(""""type"\s*:\s*"minecraft:rarity_filter"""").containsMatchIn(json),
            "Dimensional font worldgen should keep an explicit rarity contract"
        )
        assertTrue(
            Regex(""""chance"\s*:\s*(?:[1-9]|10)\b""").containsMatchIn(json),
            "Dimensional fonts should use a bounded, low-frequency attempt schedule"
        )
        assertFalse(
            Regex(""""type"\s*:\s*"minecraft:heightmap"""").containsMatchIn(json),
            "The feature scans each selected column itself; a heightmap modifier can bias placement onto foliage"
        )
    }
}
