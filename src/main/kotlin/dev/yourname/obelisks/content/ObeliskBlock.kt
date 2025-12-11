package dev.yourname.obelisks.content

import dev.yourname.obelisks.dimension.DimensionCoordinator
import dev.yourname.obelisks.player.getRunInfo
import dev.yourname.obelisks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class ObeliskBlock(properties: Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ObeliskBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape =
        RenderShape.MODEL

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS
        }

        val serverLevel = level as ServerLevel
        val serverPlayer = player as ServerPlayer
        val be = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return InteractionResult.PASS

        // Check if player is returning from a run
        val playerRunInfo = serverPlayer.getRunInfo()
        if (playerRunInfo?.isInRun() == true) {
            // Player is in a run, attempting to return
            player.sendSystemMessage(Component.literal("Use void fall or return pad to exit the run!"))
            return InteractionResult.SUCCESS
        }

        // Use DimensionCoordinator for ACID-like transaction
        val result = DimensionCoordinator.enterDimension(serverPlayer, be, pos, serverLevel)

        result.onFailure { error, _ ->
            player.sendSystemMessage(Component.literal("Failed to activate obelisk: $error"))
            return InteractionResult.FAIL
        }

        return InteractionResult.SUCCESS
    }

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(
        state: BlockState,
        level: Level,
        pos: BlockPos
    ): Int {
        val be = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return 0
        // crude redstone output for debug: clamp FE to 0..15
        return (be.feStored.coerceIn(0, 15000) / 1000)
    }

    override fun isPathfindable(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        type: net.minecraft.world.level.pathfinder.PathComputationType
    ): Boolean = false
}
