package dev.yourname.obelisks.content

import dev.yourname.obelisks.runtime.run.RunRegistry
import dev.yourname.obelisks.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
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
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import org.joml.Vector3f

class ObeliskBlock(properties: Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ObeliskBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>
    ): BlockEntityTicker<T>? = null

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
        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return InteractionResult.PASS

        if (player.isShiftKeyDown) {
            obelisk.cycleTemplate()
            serverPlayer.sendSystemMessage(Component.literal("Obelisk attuned to ${obelisk.targetTemplateId}"))
            return InteractionResult.CONSUME
        }

        val result = RunRegistry.activateObelisk(serverPlayer, obelisk, pos)
        if (result != null) {
            serverPlayer.sendSystemMessage(Component.literal(result))
            if (result.startsWith("Initializing")) {
                level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 0.8f, 1.1f)
            }
        }
        return InteractionResult.CONSUME
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return
        if (!obelisk.shouldShowBeam() && obelisk.getEnergyPercent() < 0.25) {
            return
        }

        val colors = obelisk.getBeamColorFloats()
        val dust = DustParticleOptions(Vector3f(colors[0], colors[1], colors[2]), 1.0f)
        repeat(if (obelisk.isRunActive()) 4 else 2) {
            val x = pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.8
            val y = pos.y + 0.9 + random.nextDouble() * 1.4
            val z = pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.8
            level.addParticle(dust, x, y, z, 0.0, 0.02 + random.nextDouble() * 0.02, 0.0)
        }

        if (obelisk.isRunActive() && random.nextFloat() < 0.35f) {
            level.addParticle(
                ParticleTypes.PORTAL,
                pos.x + 0.5 + (random.nextDouble() - 0.5),
                pos.y + 1.0 + random.nextDouble(),
                pos.z + 0.5 + (random.nextDouble() - 0.5),
                (random.nextDouble() - 0.5) * 0.2,
                -random.nextDouble() * 0.1,
                (random.nextDouble() - 0.5) * 0.2
            )
        }
    }

    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int {
        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return 0
        return (obelisk.getEnergyPercent() * 15.0).toInt().coerceIn(0, 15)
    }

    override fun isPathfindable(state: BlockState, level: BlockGetter, pos: BlockPos, type: PathComputationType): Boolean = false
}
