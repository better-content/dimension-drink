package com.bettercontent.dimensiondrink.api.event

import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/**
 * Posted after a surviving participant has been transported home by a successfully completed
 * dimensional Font run. Early exits, collapsed runs, logout returns, and death returns do not post it.
 */
class FontAggregateReturnEvent(
    val player: ServerPlayer,
    val runId: UUID,
    val definitionId: ResourceLocation,
    val targetDimension: ResourceKey<Level>,
    val aggregateId: ResourceLocation
) : Event()
