package dev.yourname.dimensiondrink.content

import dev.yourname.dimensiondrink.runtime.backend.ArrivalSiteLayout
import dev.yourname.dimensiondrink.runtime.run.RunRegistry
import dev.yourname.dimensiondrink.registry.ModBlockEntities
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.AxeItem
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.BlockHitResult
import org.joml.Vector3f

class ObeliskBlock(
    properties: Properties,
    private val returnOnly: Boolean = false
) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ObeliskBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (blockEntityType != ModBlockEntities.OBELISK.get()) return null
        @Suppress("UNCHECKED_CAST")
        return BlockEntityTicker { tickLevel: Level, tickPos: BlockPos, _: BlockState, blockEntity: T ->
            if (blockEntity is ObeliskBlockEntity) blockEntity.tick(tickLevel, tickPos)
        }
    }

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
        val held = player.getItemInHand(hand)

        if (returnOnly) {
            if (!held.isEmpty || player.isShiftKeyDown) return InteractionResult.PASS
            val result = RunRegistry.drinkReturnFont(serverPlayer)
            if (result.startsWith("Drinking")) {
                serverPlayer.swing(hand, true)
                level.playSound(null, pos, SoundEvents.HONEY_DRINK, SoundSource.BLOCKS, 0.7f, 0.75f)
            }
            return InteractionResult.CONSUME
        }

        if (held.item is AxeItem) {
            val scraped = obelisk.scrapeAltarCopperOxidation(level)
            if (scraped > 0) {
                held.hurtAndBreak(1, serverPlayer) { brokenPlayer -> brokenPlayer.broadcastBreakEvent(hand) }
                level.playSound(null, pos, SoundEvents.AXE_SCRAPE, SoundSource.BLOCKS, 0.85f, 0.95f + level.random.nextFloat() * 0.2f)
                return InteractionResult.CONSUME
            }
        }

        if (player.isShiftKeyDown && held.isEmpty) {
            val removed = obelisk.removeHeart()
            if (!removed.isEmpty && !serverPlayer.inventory.add(removed)) {
                serverPlayer.drop(removed, false)
            }
            return InteractionResult.CONSUME
        }

        if (!held.isEmpty) {
            if (obelisk.placeHeart(held)) {
                level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 0.45f, 0.85f)
                return InteractionResult.CONSUME
            }
            return InteractionResult.PASS
        }

        val result = RunRegistry.activateObelisk(serverPlayer, obelisk, pos)
        if (result != null) {
            if (result.startsWith("Drinking")) {
                serverPlayer.swing(hand, true)
                level.playSound(null, pos, SoundEvents.HONEY_DRINK, SoundSource.BLOCKS, 0.7f, 0.75f)
            }
        }
        return InteractionResult.CONSUME
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (returnOnly) {
            animateReturnFontTick(level, pos, random)
            return
        }
        val obelisk = level.getBlockEntity(pos) as? ObeliskBlockEntity ?: return
        val blood = obelisk.getEnergyPercent()
        val ready = obelisk.isReadyToOpen()
        val lowBlood = obelisk.isLowBloodWarning()
        if (!ready && !lowBlood && !obelisk.shouldShowBeam() && blood < 0.25) {
            return
        }

        val dust = DustParticleOptions(Vector3f(0.42f, 0.02f, 0.03f), 0.8f)
        repeat(if (obelisk.isRunActive() || ready) 4 else 2) {
            val x = pos.x + 0.5 + (random.nextDouble() - 0.5) * 0.8
            val y = pos.y + 0.9 + random.nextDouble() * 1.4
            val z = pos.z + 0.5 + (random.nextDouble() - 0.5) * 0.8
            level.addParticle(dust, x, y, z, 0.0, 0.02 + random.nextDouble() * 0.02, 0.0)
        }

        if ((obelisk.isRunActive() || ready) && random.nextFloat() < 0.45f) {
            level.addParticle(
                if (ready) ParticleTypes.SOUL_FIRE_FLAME else ParticleTypes.PORTAL,
                pos.x + 0.5 + (random.nextDouble() - 0.5),
                pos.y + 1.0 + random.nextDouble(),
                pos.z + 0.5 + (random.nextDouble() - 0.5),
                (random.nextDouble() - 0.5) * 0.2,
                -random.nextDouble() * 0.1,
                (random.nextDouble() - 0.5) * 0.2
            )
        }

        if (ready && random.nextFloat() < 0.25f) {
            level.addParticle(
                ParticleTypes.END_ROD,
                pos.x + 0.5 + (random.nextDouble() - 0.5) * 1.5,
                pos.y + 0.35 + random.nextDouble() * 1.6,
                pos.z + 0.5 + (random.nextDouble() - 0.5) * 1.5,
                0.0,
                0.01 + random.nextDouble() * 0.03,
                0.0
            )
        }

        if (lowBlood) {
            repeat(2) {
                val radius = 3.0 + random.nextDouble() * 8.0
                val angle = random.nextDouble() * Math.PI * 2.0
                level.addParticle(
                    if (random.nextBoolean()) ParticleTypes.SMOKE else ParticleTypes.SOUL,
                    pos.x + 0.5 + kotlin.math.cos(angle) * radius,
                    pos.y + 0.2 + random.nextDouble() * 0.9,
                    pos.z + 0.5 + kotlin.math.sin(angle) * radius,
                    (random.nextDouble() - 0.5) * 0.04,
                    0.01 + random.nextDouble() * 0.03,
                    (random.nextDouble() - 0.5) * 0.04
                )
            }
        }
    }

    private fun animateReturnFontTick(level: Level, pos: BlockPos, random: RandomSource) {
        val verdigris = DustParticleOptions(Vector3f(0.34f, 0.76f, 0.58f), 0.72f)
        val offsets = ArrivalSiteLayout.floorOffsets()
        repeat(4) {
            val offset = offsets[random.nextInt(offsets.size)]
            val copperPos = pos.offset(offset)
            if (!ArrivalSiteLayout.isVerdigrisCopper(level.getBlockState(copperPos).block)) return@repeat
            level.addParticle(
                verdigris,
                copperPos.x + 0.25 + random.nextDouble() * 0.5,
                copperPos.y + 1.02 + random.nextDouble() * 0.18,
                copperPos.z + 0.25 + random.nextDouble() * 0.5,
                (random.nextDouble() - 0.5) * 0.01,
                0.018 + random.nextDouble() * 0.03,
                (random.nextDouble() - 0.5) * 0.01
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
