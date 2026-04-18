package dev.yourname.obelisks.portal

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object PortalBlocker {

    private val message = Component.literal("Portals are disabled. Use an Obelisk to travel between dimensions.")

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.itemStack.item != Items.FLINT_AND_STEEL) return
        if (event.level.getBlockState(event.pos).block != Blocks.OBSIDIAN) return
        if (!isPotentialPortalFrame(event.level, event.pos)) return

        event.isCanceled = true
        if (!event.level.isClientSide) {
            event.entity.displayClientMessage(message, true)
        }
    }

    @SubscribeEvent
    fun onPortalSpawn(event: BlockEvent.PortalSpawnEvent) {
        event.isCanceled = true
        if (!event.level.isClientSide) {
            event.level.players().forEach { player ->
                if (player.blockPosition().distSqr(event.pos) <= 64.0 * 64.0) {
                    player.displayClientMessage(message, true)
                }
            }
        }
    }

    @SubscribeEvent
    fun onNetherPortalPlace(event: BlockEvent.EntityPlaceEvent) {
        if (event.placedBlock.block == Blocks.NETHER_PORTAL || event.placedBlock.block == Blocks.END_PORTAL) {
            event.isCanceled = true
        }
    }

    private fun isPotentialPortalFrame(level: net.minecraft.world.level.LevelAccessor, pos: net.minecraft.core.BlockPos): Boolean {
        val neighbors = listOf(pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west())
        return neighbors.any { level.getBlockState(it).block == Blocks.OBSIDIAN }
    }
}
