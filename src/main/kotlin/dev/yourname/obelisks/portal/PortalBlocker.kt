package dev.yourname.obelisks.portal

import net.minecraft.world.level.block.Blocks
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.level.BlockEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/**
 * Blocks vanilla portal creation to ensure obelisks are the only dimension travel method.
 * Performance-friendly: only fires on specific events, no world scanning.
 */
object PortalBlocker {

    /**
     * Prevents Nether portal ignition with flint and steel.
     */
    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level
        val pos = event.pos
        val itemStack = event.itemStack

        // Check if player is trying to ignite a nether portal
        if (itemStack.item == net.minecraft.world.item.Items.FLINT_AND_STEEL) {
            val blockState = level.getBlockState(pos)

            // Check if they're clicking obsidian (potential portal frame)
            if (blockState.block == Blocks.OBSIDIAN) {
                // Check if there's a valid portal frame structure
                if (isPartOfPortalFrame(level, pos)) {
                    event.isCanceled = true
                    if (!level.isClientSide && event.entity != null) {
                        event.entity.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                "§cNether portals are disabled. Use an Obelisk to travel between dimensions."
                            ),
                            true // actionbar
                        )
                    }
                }
            }
        }
    }

    /**
     * Prevents Nether portal blocks from forming.
     */
    @SubscribeEvent
    fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
        if (event.placedBlock.block == Blocks.NETHER_PORTAL) {
            event.isCanceled = true
        }
    }

    /**
     * Prevents End portal activation.
     */
    @SubscribeEvent
    fun onPortalSpawn(event: BlockEvent.PortalSpawnEvent) {
        // Cancel all portal spawn events
        event.isCanceled = true

        if (!event.level.isClientSide) {
            // Optionally notify nearby players
            val pos = event.pos
            event.level.players().forEach { player ->
                if (player.blockPosition().distSqr(pos) < 64 * 64) {
                    player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                            "§cPortals are disabled. Use an Obelisk to travel between dimensions."
                        ),
                        true
                    )
                }
            }
        }
    }

    /**
     * Checks if an obsidian block is part of a potential portal frame.
     * Simple heuristic: check if there are nearby obsidian blocks in a portal-like pattern.
     */
    private fun isPartOfPortalFrame(level: net.minecraft.world.level.LevelAccessor, pos: net.minecraft.core.BlockPos): Boolean {
        // Check if there are obsidian blocks above/below (vertical frame)
        val above = level.getBlockState(pos.above())
        val below = level.getBlockState(pos.below())

        if (above.block == Blocks.OBSIDIAN || below.block == Blocks.OBSIDIAN) {
            return true
        }

        // Check horizontal neighbors
        val neighbors = listOf(
            pos.north(), pos.south(), pos.east(), pos.west()
        )

        return neighbors.any { level.getBlockState(it).block == Blocks.OBSIDIAN }
    }
}
