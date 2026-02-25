package dev.yourname.obelisks.content

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * A decorative block that emits ambient particles.
 * Placed in the ground near obelisks during worldgen.
 */
class ParticleEmitterBlock(properties: Properties) : Block(properties) {

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        // Disabled - no particles
    }
}
