package com.bettercontent.dimensiondrink.compat

import com.bettercontent.dimensiondrink.api.event.FontAggregateReturnEvent
import com.bettercontent.dimensiondrink.api.event.FontEnterEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Optional Threads bridge driven by successful Font transport events. */
object ThreadsBridge {
    private const val END_ROUTE_THREAD = "the_end_is_not_a_door"

    @SubscribeEvent
    fun onEnter(event: FontEnterEvent) = emit(event.player, "font_transit", "depart", event.runId.toString())

    @SubscribeEvent
    fun onAggregateReturn(event: FontAggregateReturnEvent) {
        emit(event.player, "font_transit", "return", event.runId.toString())
        endRouteCompletion(activeCorrelation(event.player, END_ROUTE_THREAD))?.let { completion ->
            emit(event.player, completion.type, completion.value, completion.correlationToken)
        }
    }

    internal fun endRouteCompletion(correlationToken: String?): CorrelatedSignal? =
        correlationToken?.takeUnless(String::isBlank)
            ?.let { CorrelatedSignal("font_route_completed", "returned", it) }

    private fun activeCorrelation(player: ServerPlayer, threadId: String): String? {
        return try {
            Class.forName("com.bettercontent.threads.api.ThreadSignals")
                .getMethod("activeCorrelation", ServerPlayer::class.java, String::class.java)
                .invoke(null, player, threadId) as? String
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoSuchMethodException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

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

    internal data class CorrelatedSignal(
        val type: String,
        val value: String,
        val correlationToken: String
    )
}
