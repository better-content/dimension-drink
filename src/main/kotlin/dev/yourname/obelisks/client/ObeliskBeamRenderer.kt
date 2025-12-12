package dev.yourname.obelisks.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Matrix4f

/**
 * Renders beacon-style beams above active obelisks.
 * Based on vanilla BeaconRenderer code.
 */
@OnlyIn(Dist.CLIENT)
object ObeliskBeamRenderer {

    private val BEAM_LOCATION = ResourceLocation("textures/entity/beacon_beam.png")

    @SubscribeEvent
    fun onRenderLevelStage(event: RenderLevelStageEvent) {
        if (!ObelisksConstants.OBELISK_BEAM_ENABLED) return
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return

        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val camera = minecraft.gameRenderer.mainCamera
        val cameraPos = camera.position

        val poseStack = event.poseStack
        val bufferSource = minecraft.renderBuffers().bufferSource()
        val partialTick = event.partialTick

        // Find all active obelisks within render distance
        val renderDistance = minecraft.options.renderDistance().get() * 16
        val playerPos = minecraft.player?.blockPosition() ?: return

        for (x in -renderDistance..renderDistance step 16) {
            for (z in -renderDistance..renderDistance step 16) {
                val chunkPos = playerPos.offset(x, 0, z)
                val chunk = level.getChunk(chunkPos.x shr 4, chunkPos.z shr 4)

                // Check all block entities in this chunk
                for (be in chunk.blockEntities.values) {
                    if (be is ObeliskBlockEntity && be.isRunActive()) {
                        renderBeam(poseStack, bufferSource, be.blockPos, cameraPos, partialTick, level)
                    }
                }
            }
        }
    }

    private fun renderBeam(
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        pos: BlockPos,
        cameraPos: Vec3,
        partialTick: Float,
        level: Level
    ) {
        poseStack.pushPose()

        // Translate to block position relative to camera
        val relX = pos.x.toDouble() - cameraPos.x
        val relY = pos.y.toDouble() - cameraPos.y
        val relZ = pos.z.toDouble() - cameraPos.z
        poseStack.translate(relX, relY, relZ)

        // Get game time for animation
        val gameTime = level.gameTime
        val time = (gameTime + partialTick.toDouble()) % 40.0
        val beamHeight = 256f // Beam goes all the way up

        // Render the beam
        val vertexConsumer = bufferSource.getBuffer(RenderType.beaconBeam(BEAM_LOCATION, true))
        renderBeamSegment(
            poseStack,
            vertexConsumer,
            partialTick,
            time,
            beamHeight,
            floatArrayOf(0.3f, 0.6f, 1.0f) // Light blue color
        )

        poseStack.popPose()
    }

    private fun renderBeamSegment(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        partialTick: Float,
        time: Double,
        height: Float,
        color: FloatArray
    ) {
        val matrix = poseStack.last().pose()
        val beamRadius = 0.2f
        val u0 = 0f
        val u1 = 1f
        val v0 = 0f
        val v1 = height / 32f

        // Animate texture scrolling
        val vOffset = ((-time) % 1.0).toFloat()

        // Render beam quad (4 sides)
        for (i in 0 until 4) {
            val angle = (i * 90f + 45f) * Mth.DEG_TO_RAD
            val x1 = Mth.cos(angle) * beamRadius
            val z1 = Mth.sin(angle) * beamRadius
            val nextAngle = ((i + 1) * 90f + 45f) * Mth.DEG_TO_RAD
            val x2 = Mth.cos(nextAngle) * beamRadius
            val z2 = Mth.sin(nextAngle) * beamRadius

            // Bottom vertices
            consumer.vertex(matrix, x1 + 0.5f, 1f, z1 + 0.5f)
                .color(color[0], color[1], color[2], 1.0f)
                .uv(u1, v0 + vOffset)
                .overlayCoords(0, 10)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0f, 1f, 0f)
                .endVertex()

            consumer.vertex(matrix, x2 + 0.5f, 1f, z2 + 0.5f)
                .color(color[0], color[1], color[2], 1.0f)
                .uv(u0, v0 + vOffset)
                .overlayCoords(0, 10)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0f, 1f, 0f)
                .endVertex()

            // Top vertices
            consumer.vertex(matrix, x2 + 0.5f, 1f + height, z2 + 0.5f)
                .color(color[0], color[1], color[2], 0.2f)
                .uv(u0, v1 + vOffset)
                .overlayCoords(0, 10)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0f, 1f, 0f)
                .endVertex()

            consumer.vertex(matrix, x1 + 0.5f, 1f + height, z1 + 0.5f)
                .color(color[0], color[1], color[2], 0.2f)
                .uv(u1, v1 + vOffset)
                .overlayCoords(0, 10)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0f, 1f, 0f)
                .endVertex()
        }
    }
}
