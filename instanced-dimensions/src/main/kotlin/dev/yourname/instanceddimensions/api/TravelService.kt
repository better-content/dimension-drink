package dev.yourname.instanceddimensions.api

import net.minecraft.server.level.ServerPlayer
import java.util.UUID

interface TravelService {
    fun enterInstance(player: ServerPlayer, instanceId: UUID): TravelEnterResult
    fun returnPlayer(player: ServerPlayer): Boolean
    fun hasReturnAnchor(playerId: UUID): Boolean
}
