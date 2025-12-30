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
        // Spawn particles less frequently to avoid performance issues
        if (random.nextFloat() > 0.3f) return

        // Random position on top of the block
        val xOffset = random.nextDouble()
        val yOffset = 1.0
        val zOffset = random.nextDouble()

        val x = pos.x + xOffset
        val y = pos.y + yOffset
        val z = pos.z + zOffset

        // Velocity - particles drift upward slowly
        val xSpeed = (random.nextDouble() - 0.5) * 0.02
        val ySpeed = random.nextDouble() * 0.05 + 0.02
        val zSpeed = (random.nextDouble() - 0.5) * 0.02

        // Use mystical particle types
        val particleType = when (random.nextInt(3)) {
            0 -> ParticleTypes.END_ROD
            1 -> ParticleTypes.PORTAL
            else -> ParticleTypes.GLOW
        }

        level.addParticle(particleType, x, y, z, xSpeed, ySpeed, zSpeed)
    }
}
