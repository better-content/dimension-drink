package com.bettercontent.dimensiondrink.trade

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DimensionalFontMapTradeResourcesTest {
    @Test
    fun dimensionalFontStructureIsDiscoverableByMapTrades() {
        val tag = assertNotNull(
            javaClass.classLoader.getResource(
                "data/dimension_drink/tags/worldgen/structure/on_dimensional_font_maps.json"
            )
        ).readText()

        assertTrue(
            Regex("\\\"dimension_drink:dimensional_font\\\"").containsMatchIn(tag),
            "Font-map structure tag should contain the production dimensional font structure"
        )
    }

    @Test
    fun fontMapNameAndDestinationAreLocalized() {
        val language = assertNotNull(
            javaClass.classLoader.getResource("assets/dimension_drink/lang/en_us.json")
        ).readText()

        assertTrue(language.contains("item.dimension_drink.dimensional_font_map"))
        assertTrue(language.contains("item.dimension_drink.dimensional_font_map.destination"))
    }
}
