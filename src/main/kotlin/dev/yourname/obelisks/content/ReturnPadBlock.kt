package dev.yourname.obelisks.content

import dev.yourname.obelisks.player.PlayerReturnHandler
import dev.yourname.obelisks.player.getRunInfo
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Return pad that teleports players back to their origin obelisk when right-clicked.
 * 
 * Placement Requirements:
 * - Must be placed at the center of a 3x3 ring of obsidian blocks
 * - Must have 3x3x3 cube of air above it (Y+1 to Y+3)
 */
class ReturnPadBlock(properties: Properties) : Block(properties) {

    companion object {
        // Make it a pressure plate shape
        private val SHAPE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0)

        /**
         * Validates that the return pad has a 3x3 obsidian ring around it.
         * Returns true if valid, false otherwise.
         */
        fun hasValidObsidianRing(level: Level, centerPos: BlockPos): Boolean {
            // Check 3x3 ring around center (excluding center itself)
            for (x in -1..1) {
                for (z in -1..1) {
                    // Skip center
                    if (x == 0 && z == 0) continue

                    val checkPos = centerPos.offset(x, 0, z)
                    val blockState = level.getBlockState(checkPos)
                    
                    if (blockState.block != Blocks.OBSIDIAN) {
                        return false
                    }
                }
            }
            return true
        }

        /**
         * Validates that there's a 3x3x3 cube of air above the return pad.
         * Checks Y+1, Y+2, Y+3 levels.
         */
        fun hasValidAirCube(level: Level, centerPos: BlockPos): Boolean {
            // Check 3 layers above (Y+1 to Y+3)
            for (y in 1..3) {
                for (x in -1..1) {
                    for (z in -1..1) {
                        val checkPos = centerPos.offset(x, y, z)
                        val blockState = level.getBlockState(checkPos)
                        
                        if (!blockState.isAir) {
                            return false
                        }
                    }
                }
            }
            return true
        }

        /**
         * Full validation: obsidian ring + air cube.
         */
        fun isValidPlacement(level: Level, pos: BlockPos): Boolean {
            return hasValidObsidianRing(level, pos) && hasValidAirCube(level, pos)
        }
    }

    override fun canBeReplaced(state: BlockState, context: BlockPlaceContext): Boolean {
        return false
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val level = context.level
        val pos = context.clickedPos

        // Validate placement
        if (!isValidPlacement(level, pos)) {
            if (context.player is ServerPlayer) {
                (context.player as ServerPlayer).sendSystemMessage(
                    Component.literal("Return pad requires: 3x3 obsidian ring at base and 3x3x3 air cube above!")
                )
            }
            return null
        }

        return super.getStateForPlacement(context)
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext
    ): VoxelShape = SHAPE

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        if (player !is ServerPlayer) return InteractionResult.PASS

        // No validation required - return pad works regardless of structure state
        // Use PlayerReturnHandler to return player
        PlayerReturnHandler.returnPlayer(player)
        return InteractionResult.SUCCESS
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)

        // No longer validate structure on neighbor change - return pad persists regardless of structure
    }
}
