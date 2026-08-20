package com.bettercontent.dimensiondrink.trade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun wanderingTraderFactoryExposesTheFontListing() {
        assertIs<DimensionalFontMapListing>(DimensionalFontMapTrades.wanderingTraderListing(0))
    }

    @Test
    fun soldFontCycleResetsOnlyAfterEveryEligibleType() {
        val eligible = linkedSetOf("overworld", "nether", "end")

        assertEquals(
            linkedSetOf("overworld", "nether"),
            DimensionalFontMapTrades.advanceSoldTypes(setOf("overworld"), "nether", eligible)
        )
        assertEquals(
            emptySet(),
            DimensionalFontMapTrades.advanceSoldTypes(setOf("overworld", "nether"), "end", eligible)
        )
    }
}
