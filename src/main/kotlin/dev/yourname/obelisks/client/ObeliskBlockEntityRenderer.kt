package dev.yourname.obelisks.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.yourname.obelisks.MOD_ID
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import com.mojang.math.Axis

class ObeliskBlockEntityRenderer(
    @Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context
) : BlockEntityRenderer<ObeliskBlockEntity> {
    override fun render(
        obelisk: ObeliskBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        renderBlood(obelisk, poseStack, bufferSource, packedLight)
        renderHeart(obelisk, partialTick, poseStack, bufferSource, packedLight)
    }

    private fun renderBlood(
        obelisk: ObeliskBlockEntity,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val percent = obelisk.getBloodPercent().toFloat().coerceIn(0.0f, 1.0f)
        if (percent <= 0.01f) return

        val top = Mth.lerp(percent, LOWER_BLOOD_Y, UPPER_BLOOD_Y)
        val consumer = bufferSource.getBuffer(RenderType.entityTranslucent(BLOOD_TEXTURE))
        renderBox(
            poseStack,
            consumer,
            4.0f / 16.0f,
            LOWER_BLOOD_Y,
            4.0f / 16.0f,
            12.0f / 16.0f,
            top,
            12.0f / 16.0f,
            packedLight
        )
    }

    private fun renderHeart(
        obelisk: ObeliskBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int
    ) {
        val heart = obelisk.heartStack
        if (heart.isEmpty) return

        val level = obelisk.level
        val time = (level?.gameTime ?: 0L).toFloat() + partialTick
        val rotation = time * 1.35f

        poseStack.pushPose()
        poseStack.translate(0.5, 0.72, 0.5)
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation))
        poseStack.scale(0.48f, 0.48f, 0.48f)
        Minecraft.getInstance().itemRenderer.renderStatic(
            heart,
            ItemDisplayContext.GROUND,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            poseStack,
            bufferSource,
            level,
            0
        )
        poseStack.popPose()
    }

    private fun renderBox(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        minX: Float,
        minY: Float,
        minZ: Float,
        maxX: Float,
        maxY: Float,
        maxZ: Float,
        packedLight: Int
    ) {
        val pose = poseStack.last()
        vertex(consumer, pose, minX, maxY, minZ, 0.0f, 0.0f, packedLight)
        vertex(consumer, pose, minX, maxY, maxZ, 0.0f, 1.0f, packedLight)
        vertex(consumer, pose, maxX, maxY, maxZ, 1.0f, 1.0f, packedLight)
        vertex(consumer, pose, maxX, maxY, minZ, 1.0f, 0.0f, packedLight)

        vertex(consumer, pose, minX, minY, minZ, 0.0f, 0.0f, packedLight)
        vertex(consumer, pose, maxX, minY, minZ, 1.0f, 0.0f, packedLight)
        vertex(consumer, pose, maxX, minY, maxZ, 1.0f, 1.0f, packedLight)
        vertex(consumer, pose, minX, minY, maxZ, 0.0f, 1.0f, packedLight)

        side(consumer, pose, minX, minY, minZ, maxX, maxY, minZ, packedLight)
        side(consumer, pose, maxX, minY, minZ, maxX, maxY, maxZ, packedLight)
        side(consumer, pose, maxX, minY, maxZ, minX, maxY, maxZ, packedLight)
        side(consumer, pose, minX, minY, maxZ, minX, maxY, minZ, packedLight)
    }

    private fun side(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        packedLight: Int
    ) {
        vertex(consumer, pose, x1, y1, z1, 0.0f, 1.0f, packedLight)
        vertex(consumer, pose, x2, y1, z2, 1.0f, 1.0f, packedLight)
        vertex(consumer, pose, x2, y2, z2, 1.0f, 0.0f, packedLight)
        vertex(consumer, pose, x1, y2, z1, 0.0f, 0.0f, packedLight)
    }

    private fun vertex(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        packedLight: Int
    ) {
        consumer.vertex(pose.pose(), x, y, z)
            .color(145, 0, 8, 210)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(pose.normal(), 0.0f, 1.0f, 0.0f)
            .endVertex()
    }

    companion object {
        private val BLOOD_TEXTURE = ResourceLocation(MOD_ID, "textures/block/dimensional_font_blood.png")
        private const val LOWER_BLOOD_Y = 3.14f / 16.0f
        private const val UPPER_BLOOD_Y = 8.92f / 16.0f
    }
}
