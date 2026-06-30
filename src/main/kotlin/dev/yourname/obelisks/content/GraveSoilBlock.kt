package dev.yourname.obelisks.content

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

class GraveSoilBlock(properties: Properties) : Block(properties) {
    companion object {
        val CHARGING: BooleanProperty = BooleanProperty.create("charging")
    }

    init {
        registerDefaultState(stateDefinition.any().setValue(CHARGING, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CHARGING)
    }

    override fun playerDestroy(level: Level, player: Player, pos: BlockPos, state: BlockState, blockEntity: BlockEntity?, stack: ItemStack) {
        if (level is ServerLevel) {
            level.sendParticles(
                ParticleTypes.SOUL,
                pos.x + 0.5,
                pos.y + 0.8,
                pos.z + 0.5,
                10,
                0.35,
                0.25,
                0.35,
                0.025
            )
            level.sendParticles(
                ParticleTypes.SMOKE,
                pos.x + 0.5,
                pos.y + 0.45,
                pos.z + 0.5,
                6,
                0.3,
                0.15,
                0.3,
                0.01
            )
            level.playSound(null, pos, SoundEvents.SOUL_ESCAPE, SoundSource.BLOCKS, 0.22f, 0.65f + level.random.nextFloat() * 0.25f)
        }
        super.playerDestroy(level, player, pos, state, blockEntity, stack)
    }
}
