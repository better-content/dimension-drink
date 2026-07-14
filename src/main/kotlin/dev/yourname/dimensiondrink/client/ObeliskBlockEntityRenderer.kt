package dev.yourname.dimensiondrink.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.yourname.dimensiondrink.content.ObeliskBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
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
        renderReservoir(obelisk, poseStack, bufferSource)
        renderHeart(obelisk, partialTick, poseStack, bufferSource, packedLight)
    }

    private fun renderReservoir(
        obelisk: ObeliskBlockEntity,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource
    ) {
        val percent = obelisk.getBloodPercent().toFloat().coerceIn(0.0f, 1.0f)
        if (percent <= 0.01f) return

        val top = Mth.lerp(percent, LOWER_JUICE_Y, UPPER_JUICE_Y)
        val consumer = bufferSource.getBuffer(RenderType.translucent())
        val sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(WATER_STILL)
        renderFluidVolume(
            poseStack,
            consumer,
            sprite,
            4.15f / 16.0f,
            LOWER_JUICE_Y - 0.02f,
            top,
            4.15f / 16.0f,
            11.85f / 16.0f,
            11.85f / 16.0f,
            LightTexture.FULL_BRIGHT,
            224,
            TRIP_JUICE_RED,
            TRIP_JUICE_GREEN,
            TRIP_JUICE_BLUE
        )

        val meniscusY = (top + 0.006f).coerceAtMost(UPPER_JUICE_Y + 0.012f)
        renderFluidSurface(
            poseStack,
            consumer,
            sprite,
            3.95f / 16.0f,
            meniscusY,
            3.95f / 16.0f,
            12.05f / 16.0f,
            12.05f / 16.0f,
            LightTexture.FULL_BRIGHT,
            (150 + percent * 80.0f).toInt().coerceIn(150, 230),
            MENISCUS_RED,
            MENISCUS_GREEN,
            MENISCUS_BLUE
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
        poseStack.scale(0.5f, 0.5f, 0.5f)
        Minecraft.getInstance().itemRenderer.renderStatic(
            heart,
            ItemDisplayContext.FIXED,
            packedLight,
            OverlayTexture.NO_OVERLAY,
            poseStack,
            bufferSource,
            level,
            0
        )
        poseStack.popPose()
    }

    private fun renderFluidVolume(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
        minX: Float,
        minY: Float,
        y: Float,
        minZ: Float,
        maxX: Float,
        maxZ: Float,
        packedLight: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int
    ) {
        val pose = poseStack.last()
        quad(consumer, pose, sprite, minX, y, minZ, minX, y, maxZ, maxX, y, maxZ, maxX, y, minZ, 0.0f, 1.0f, 0.0f, packedLight, alpha, red, green, blue)
        quad(consumer, pose, sprite, minX, minY, minZ, maxX, minY, minZ, maxX, y, minZ, minX, y, minZ, 0.0f, 0.0f, -1.0f, packedLight, alpha, red, green, blue)
        quad(consumer, pose, sprite, maxX, minY, maxZ, minX, minY, maxZ, minX, y, maxZ, maxX, y, maxZ, 0.0f, 0.0f, 1.0f, packedLight, alpha, red, green, blue)
        quad(consumer, pose, sprite, minX, minY, maxZ, minX, minY, minZ, minX, y, minZ, minX, y, maxZ, -1.0f, 0.0f, 0.0f, packedLight, alpha, red, green, blue)
        quad(consumer, pose, sprite, maxX, minY, minZ, maxX, minY, maxZ, maxX, y, maxZ, maxX, y, minZ, 1.0f, 0.0f, 0.0f, packedLight, alpha, red, green, blue)
    }

    private fun renderFluidSurface(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
        minX: Float,
        y: Float,
        minZ: Float,
        maxX: Float,
        maxZ: Float,
        packedLight: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int
    ) {
        val pose = poseStack.last()
        quad(consumer, pose, sprite, minX, y, minZ, minX, y, maxZ, maxX, y, maxZ, maxX, y, minZ, 0.0f, 1.0f, 0.0f, packedLight, alpha, red, green, blue)
    }

    private fun quad(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
        x3: Float,
        y3: Float,
        z3: Float,
        x4: Float,
        y4: Float,
        z4: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        packedLight: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int
    ) {
        vertex(consumer, pose, sprite, x1, y1, z1, 0.0f, 0.0f, packedLight, alpha, red, green, blue, normalX, normalY, normalZ)
        vertex(consumer, pose, sprite, x2, y2, z2, 0.0f, 1.0f, packedLight, alpha, red, green, blue, normalX, normalY, normalZ)
        vertex(consumer, pose, sprite, x3, y3, z3, 1.0f, 1.0f, packedLight, alpha, red, green, blue, normalX, normalY, normalZ)
        vertex(consumer, pose, sprite, x4, y4, z4, 1.0f, 0.0f, packedLight, alpha, red, green, blue, normalX, normalY, normalZ)
    }

    private fun vertex(
        consumer: VertexConsumer,
        pose: PoseStack.Pose,
        sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        packedLight: Int,
        alpha: Int,
        red: Int,
        green: Int,
        blue: Int,
        normalX: Float,
        normalY: Float,
        normalZ: Float
    ) {
        consumer.vertex(pose.pose(), x, y, z)
            .color(red, green, blue, alpha)
            .uv(sprite.getU((u * 16.0f).toDouble()), sprite.getV((v * 16.0f).toDouble()))
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(pose.normal(), normalX, normalY, normalZ)
            .endVertex()
    }

    companion object {
        private val WATER_STILL = ResourceLocation("minecraft", "block/water_still")
        private const val LOWER_JUICE_Y = 4.28f / 16.0f
        private const val UPPER_JUICE_Y = 8.72f / 16.0f
        private const val TRIP_JUICE_RED = 70
        private const val TRIP_JUICE_GREEN = 232
        private const val TRIP_JUICE_BLUE = 210
        private const val MENISCUS_RED = 190
        private const val MENISCUS_GREEN = 126
        private const val MENISCUS_BLUE = 255
    }
}
