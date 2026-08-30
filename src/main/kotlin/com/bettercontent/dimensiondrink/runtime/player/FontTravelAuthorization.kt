package com.bettercontent.dimensiondrink.runtime.player

import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import java.util.UUID

/** A one-call capability for Dimension Drink's own entry and return teleports. */
object FontTravelAuthorization {
    private data class Permit(val destination: ResourceKey<Level>)
    private val permits = ThreadLocal.withInitial { linkedMapOf<UUID, Permit>() }

    fun <T> authorize(player: ServerPlayer, destination: ResourceKey<Level>, action: () -> T): T {
        val active = permits.get()
        check(player.uuid !in active) { "Nested Font travel authorization for ${player.uuid}" }
        active[player.uuid] = Permit(destination)
        return try {
            action()
        } finally {
            active.remove(player.uuid)
            if (active.isEmpty()) permits.remove()
        }
    }

    fun permits(player: ServerPlayer, destination: ResourceKey<Level>): Boolean =
        permits.get()[player.uuid]?.destination == destination

    fun clear(playerId: UUID) {
        permits.get().remove(playerId)
    }
}
