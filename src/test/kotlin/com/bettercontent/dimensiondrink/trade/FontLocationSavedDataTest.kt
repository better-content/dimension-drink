package com.bettercontent.dimensiondrink.trade

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FontLocationSavedDataTest {
    @Test
    fun nearestUsesDistanceThenStableDefinitionTieBreak() {
        val origin = BlockPos.ZERO
        val nearest = FontLocationSavedData.selectNearest(
            listOf(
                BlockPos(5, 0, 0) to "nether",
                BlockPos(-5, 0, 0) to "aether",
                BlockPos(20, 0, 0) to "bumblezone"
            ),
            origin,
            Pair<BlockPos, String>::first,
            Pair<BlockPos, String>::second
        )
        assertEquals("aether", nearest?.second)
    }

    @Test
    fun newWorldSchemaRejectsUnknownVersions() {
        val tag = CompoundTag().apply { putInt("schema", 2) }
        assertFailsWith<IllegalArgumentException> { FontLocationSavedData.load(tag) }
    }

    @Test
    fun mapSalesAccumulateAndPersistByDefinition() {
        val data = FontLocationSavedData.load(CompoundTag().apply { putInt("schema", 1) })
        data.recordMapSale("nether")
        data.recordMapSale("nether")
        data.recordMapSale("aether")
        assertEquals(mapOf("aether" to 1L, "nether" to 2L), data.salesSnapshot())

        val saved = data.save(CompoundTag())
        assertEquals(2L, saved.getCompound("maps_sold").getLong("nether"))
    }
}
