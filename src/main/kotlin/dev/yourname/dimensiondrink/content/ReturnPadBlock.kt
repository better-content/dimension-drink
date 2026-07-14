package dev.yourname.dimensiondrink.content

import dev.yourname.dimensiondrink.runtime.run.RunRegistry
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class ReturnPadBlock(properties: Properties) : Block(properties) {

    companion object {
        private val SHAPE: VoxelShape = listOf(
            box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0),
            box(3.0, 0.0, 0.0, 13.0, 2.0, 1.0),
            box(3.0, 0.0, 15.0, 13.0, 2.0, 16.0),
            box(15.0, 0.0, 3.0, 16.0, 2.0, 13.0),
            box(0.0, 0.0, 3.0, 1.0, 2.0, 13.0)
        ).reduce(Shapes::or)

        fun hasValidObsidianRing(level: Level, centerPos: BlockPos): Boolean {
            for (x in -1..1) {
                for (z in -1..1) {
                    if (x == 0 && z == 0) continue
                    if (level.getBlockState(centerPos.offset(x, 0, z)).block != Blocks.OBSIDIAN) return false
                }
            }
            return true
        }

        fun hasValidAirCube(level: Level, centerPos: BlockPos): Boolean {
            for (y in 1..3) {
                for (x in -1..1) {
                    for (z in -1..1) {
                        if (!level.getBlockState(centerPos.offset(x, y, z)).isAir) return false
                    }
                }
            }
            return true
        }

        fun isValidPlacement(level: Level, pos: BlockPos): Boolean = hasValidObsidianRing(level, pos) && hasValidAirCube(level, pos)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        return if (isValidPlacement(context.level, context.clickedPos)) super.getStateForPlacement(context) else null
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        RunRegistry.returnPlayer(serverPlayer)
        return InteractionResult.CONSUME
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        repeat(3 + random.nextInt(3)) {
            val angle = random.nextDouble() * Math.PI * 2.0
            val distance = random.nextDouble() * 3.0
            val x = pos.x + 0.5 + kotlin.math.cos(angle) * distance
            val y = pos.y + 0.125 + random.nextDouble() * 1.5
            val z = pos.z + 0.5 + kotlin.math.sin(angle) * distance
            val vx = (random.nextDouble() - 0.5) * 0.02
            val vy = random.nextDouble() * 0.05 + 0.01
            val vz = (random.nextDouble() - 0.5) * 0.02
            level.addParticle(
                if (random.nextBoolean()) net.minecraft.core.particles.ParticleTypes.PORTAL else net.minecraft.core.particles.ParticleTypes.END_ROD,
                x, y, z, vx, vy, vz
            )
        }
    }
}
