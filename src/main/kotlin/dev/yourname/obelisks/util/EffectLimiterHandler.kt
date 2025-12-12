package dev.yourname.obelisks.util

import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Event handler to reset EffectLimiter counters each tick.
 */
object EffectLimiterHandler {
    private var tickNumber = 0L

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase == TickEvent.Phase.START) {
            tickNumber++
            EffectLimiter.onServerTick(tickNumber)
        }
    }
}
