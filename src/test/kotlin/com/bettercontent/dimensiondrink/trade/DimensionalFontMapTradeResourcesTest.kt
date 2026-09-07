package com.bettercontent.dimensiondrink.trade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DimensionalFontMapTradeResourcesTest {
    @Test
    fun fontMapsDoNotRetainTheRemoteStructureSearchTag() {
        assertTrue(
            javaClass.classLoader.getResource("data/dimension_drink/tags/worldgen/structure/on_dimensional_font_maps.json") == null,
            "Font maps must query the no-load discovery index instead of locating remote structures"
        )
    }

    @Test
    fun fontMapNameAndDestinationAreLocalized() {
        val language = kotlin.test.assertNotNull(
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
