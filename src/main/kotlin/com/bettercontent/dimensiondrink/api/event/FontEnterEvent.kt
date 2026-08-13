package com.bettercontent.dimensiondrink.api.event

import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.eventbus.api.Event
import java.util.UUID

/** Posted after a dimensional Font has successfully transported a player into its run. */
class FontEnterEvent(
    val player: ServerPlayer,
    val runId: UUID,
    val definitionId: ResourceLocation,
    val targetDimension: ResourceKey<Level>,
    val aggregateId: ResourceLocation
) : Event()
