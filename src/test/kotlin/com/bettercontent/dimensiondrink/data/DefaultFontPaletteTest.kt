package com.bettercontent.dimensiondrink.data

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertFalse

class DefaultFontPaletteTest {
    @Test
    fun bundledDefaultFontPalettesDoNotRequestBareEmptyFlowerPots() {
        val resourceNames = listOf(
            "overworld.json",
            "otherside.json",
            "undergarden.json",
            "nether.json"
        )
        resourceNames.forEach { name ->
            val stream = javaClass.classLoader.getResourceAsStream("defaults/fonts/$name")
                ?: error("Missing bundled default font resource $name")
            val root = stream.reader().use { JsonParser.parseReader(it).asJsonObject }
            val palette = root.getAsJsonObject("cultivationPalette")
            val decorations = palette.getAsJsonArray("decorations")
            val trophies = palette.getAsJsonArray("trophyBlocks")
            assertFalse(
                decorations.any { it.asString == "minecraft:flower_pot" },
                "Expected $name decorations to avoid bare empty flower pots"
            )
            if (trophies != null) {
                assertFalse(
                    trophies.any { it.asString == "minecraft:flower_pot" },
                    "Expected $name trophyBlocks to avoid bare empty flower pots"
                )
            }
        }
    }
}
