package com.bettercontent.dimensiondrink.content

import com.bettercontent.dimensiondrink.ObeliskConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChargeEconomyTest {
    @Test
    fun `standard solo font has a two minute active budget`() {
        val usableCharge = ObeliskConstants.MAX_CHARGE_STORAGE - ObeliskConstants.START_CHARGE_COST
        val soloDrain = ObeliskConstants.BASE_CHARGE_DRAIN_PER_SECOND +
            ObeliskConstants.PER_PLAYER_CHARGE_DRAIN_PER_SECOND

        assertEquals(120.0, usableCharge / soloDrain)
    }

    @Test
    fun `positive efficiency modifiers reduce costs and drains`() {
        val efficiency = ObeliskModifier(ChargeStat.BASE_DRAIN_EFFICIENCY, 25)
        assertTrue(efficiency.reduce(100.0) < 100.0)
        assertEquals(80.0, efficiency.reduce(100.0))
    }
}
