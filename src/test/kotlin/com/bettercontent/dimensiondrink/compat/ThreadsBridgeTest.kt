package com.bettercontent.dimensiondrink.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadsBridgeTest {
    @Test
    fun `completed font route reuses the active end-route correlation`() {
        assertEquals(
            ThreadsBridge.CorrelatedSignal(
                type = "font_route_completed",
                value = "returned",
                correlationToken = "player:end-route:42"
            ),
            ThreadsBridge.endRouteCompletion("player:end-route:42")
        )
    }

    @Test
    fun `completed font route stays silent without an active correlation`() {
        assertNull(ThreadsBridge.endRouteCompletion(null))
        assertNull(ThreadsBridge.endRouteCompletion(""))
    }
}
