package com.bettercontent.dimensiondrink.trade

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.item.Items
import net.minecraft.world.item.ItemStack

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

    @Test
    fun publicSellerRotationApiReadsPersistedSoldTypes() {
        val sellerData = CompoundTag().apply {
            put("dimension_drink:font_map_sold_types", ListTag().apply {
                add(StringTag.valueOf("overworld"))
                add(StringTag.valueOf("nether"))
            })
        }

        assertEquals(
            linkedSetOf("overworld", "nether"),
            DimensionalFontMapTrades.soldDefinitionIds(sellerData)
        )
    }

    @Test
    fun authoredOfferUsesTheSellerSuppliedCurrencyAndKeepsFontIdentity() {
        TestMinecraftBootstrap.bootstrap()
        val currency = Items.AMETHYST_SHARD
        val map = ItemStack(Items.FILLED_MAP).apply {
            getOrCreateTag().putString(DimensionalFontMapListing.DEFINITION_TAG, "nether")
        }

        val offer = DimensionalFontMapListing.createOffer(map, 3, currency)

        assertSame(currency, offer.baseCostA.item)
        assertEquals(DimensionalFontMapListing.COST, offer.baseCostA.count)
        assertEquals("nether", offer.result.tag?.getString(DimensionalFontMapListing.DEFINITION_TAG))
        assertEquals(DimensionalFontMapListing.MAX_USES, offer.maxUses)
        assertEquals(3, offer.xp)
    }
}
