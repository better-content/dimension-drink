package com.bettercontent.dimensiondrink.compat

import com.bettercontent.dimensiondrink.api.event.FontAggregateReturnEvent
import com.bettercontent.dimensiondrink.api.event.FontEnterEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Optional Threads bridge driven by successful Font transport events. */
object ThreadsBridge {
    @SubscribeEvent
    fun onEnter(event: FontEnterEvent) = emit(event.player, "font_transit", "depart", event.runId.toString())

    @SubscribeEvent
    fun onAggregateReturn(event: FontAggregateReturnEvent) =
        emit(event.player, "font_transit", "return", event.runId.toString())

    private fun emit(player: ServerPlayer, type: String, value: String, token: String) {
        try {
            Class.forName("com.bettercontent.threads.api.ThreadSignals")
                .getMethod("emit", ServerPlayer::class.java, String::class.java, String::class.java, String::class.java)
                .invoke(null, player, type, value, token)
        } catch (_: ClassNotFoundException) {
        } catch (_: NoSuchMethodException) {
        } catch (_: ReflectiveOperationException) {
        }
    }
}
