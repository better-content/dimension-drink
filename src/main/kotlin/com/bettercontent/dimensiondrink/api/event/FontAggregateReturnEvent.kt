package com.bettercontent.dimensiondrink.api.event

import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/**
 * Posted exactly once after a living Font participant has been transported home. This is factual
 * extraction evidence: voluntary returns and charge-expiry extraction qualify, while final death,
 * logout without a transport, and a rejected transport do not. Challenge rewards remain separate.
 */
class FontAggregateReturnEvent(
    val player: ServerPlayer,
    val runId: UUID,
    val definitionId: ResourceLocation,
    val targetDimension: ResourceKey<Level>,
    val aggregateId: ResourceLocation
) : Event()
