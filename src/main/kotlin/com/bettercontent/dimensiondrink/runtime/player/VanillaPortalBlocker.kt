package com.bettercontent.dimensiondrink.runtime.player

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.floor

object VanillaPortalBlocker {

    @SubscribeEvent
    fun onPortalSpawn(event: BlockEvent.PortalSpawnEvent) {
        event.isCanceled = true
    }

    @SubscribeEvent
    fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        if (isPortalSystemBlock(event.placedBlock.block)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val held = event.itemStack.item
        val clicked = event.level.getBlockState(event.pos)
        val heldIdPath = BuiltInRegistries.ITEM.getKey(held).path

        val tryingToIgniteNetherPortal =
            (held == Items.FLINT_AND_STEEL || held == Items.FIRE_CHARGE) && clicked.`is`(Blocks.OBSIDIAN)
        val tryingToActivateEndPortal = held == Items.ENDER_EYE && clicked.`is`(Blocks.END_PORTAL_FRAME)
        val tryingToUsePortalSystemItem = heldIdPath.contains("portal") || heldIdPath.contains("gateway")
        val interactingWithPortalSystemBlock = isPortalSystemBlock(clicked.block)

        if (tryingToIgniteNetherPortal || tryingToActivateEndPortal || (tryingToUsePortalSystemItem && interactingWithPortalSystemBlock)) {
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onEntityTravelToDimension(event: EntityTravelToDimensionEvent) {
        if (isEntityTouchingPortalSystem(event.entity)) {
            event.isCanceled = true
        }
    }

    private fun isEntityTouchingPortalSystem(entity: Entity): Boolean {
        val level = entity.level()
        val bounds = entity.boundingBox
        val minX = floor(bounds.minX).toInt()
        val minY = floor(bounds.minY).toInt()
        val minZ = floor(bounds.minZ).toInt()
        val maxX = floor(bounds.maxX).toInt()
        val maxY = floor(bounds.maxY).toInt()
        val maxZ = floor(bounds.maxZ).toInt()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val state = level.getBlockState(BlockPos(x, y, z))
                    if (isPortalSystemBlock(state.block)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isPortalSystemBlock(block: Block): Boolean {
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY || block == Blocks.END_PORTAL_FRAME) {
            return true
        }
        val path = BuiltInRegistries.BLOCK.getKey(block).path
        return path.contains("portal") || path.contains("gateway")
    }
}
