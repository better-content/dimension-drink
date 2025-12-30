package dev.yourname.obelisks.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.yourname.obelisks.content.ObeliskBlockEntity
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders beacon-style beams above obelisks following Minecraft best practices.
 * Uses BlockEntityRenderer pattern (more efficient than event-based rendering).
 * Based on vanilla BeaconRenderer implementation.
 */
@OnlyIn(Dist.CLIENT)
class ObeliskBlockEntityRenderer(context: BlockEntityRendererProvider.Context) : 
    BlockEntityRenderer<ObeliskBlockEntity> {

    companion object {
        private val BEAM_LOCATION = ResourceLocation("textures/entity/beacon_beam.png")
        private const val BEAM_RADIUS = 0.2f
        private const val MAX_RENDER_Y = 1024 // Build height limit
    }

    override fun render(
        blockEntity: ObeliskBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int
    ) {
        // Only render if beam should be visible
        if (!shouldRenderBeam(blockEntity)) return

        val level = blockEntity.level ?: return
        val gameTime = level.gameTime

        // Calculate beam height (from block to build limit)
        val beamHeight = (MAX_RENDER_Y - blockEntity.blockPos.y).toFloat()
        if (beamHeight <= 0) return

        poseStack.pushPose()

        // Translate to center of block, starting at top
        poseStack.translate(0.5, 1.0, 0.5)

        // Animate over time
        val time = (gameTime + partialTick) % 40.0

        // Get appropriate render type for beam - use non-translucent version for proper sorting
        val renderType = RenderType.beaconBeam(BEAM_LOCATION, false)
        val vertexConsumer = bufferSource.getBuffer(renderType)

        // Choose color based on obelisk state
        val color = getBeamColor(blockEntity)

        // Render the beam using vanilla-style quad rendering
        renderBeamSegment(
            poseStack,
            vertexConsumer,
            color,
            time,
            beamHeight
        )

        poseStack.popPose()
    }

    /**
     * Determines if beam should render based on obelisk state.
     * Uses the obelisk's shouldShowBeam() method which checks visibility flag and charge state.
     */
    private fun shouldRenderBeam(blockEntity: ObeliskBlockEntity): Boolean {
        return blockEntity.shouldShowBeam()
    }

    /**
     * Gets beam color - each obelisk has its own unique random color.
     * Returns RGB as float array [0.0-1.0].
     */
    private fun getBeamColor(blockEntity: ObeliskBlockEntity): FloatArray {
        return blockEntity.getBeamColorFloats()
    }

    /**
     * Renders a single beam segment using vanilla quad rendering.
     * Creates a four-sided beam with texture scrolling animation.
     */
    private fun renderBeamSegment(
        poseStack: PoseStack,
        consumer: VertexConsumer,
        color: FloatArray,
        time: Double,
        height: Float
    ) {
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()

        // UV coordinates
        val u0 = 0f
        val u1 = 1f
        val v0 = 0f
        val v1 = height / 32f // Scale texture based on height

        // Animated texture scrolling
        val vOffset = ((-time) % 1.0).toFloat()

        // Alpha values (fade at top) - using fuller alpha since we're not using translucent pass
        val alphaBottom = 0.8f
        val alphaTop = 0.15f

        // Render four sides of the beam
        for (i in 0 until 4) {
            val angle1 = Math.toRadians((i * 90f + 45f).toDouble())
            val angle2 = Math.toRadians(((i + 1) * 90f + 45f).toDouble())

            val x1 = cos(angle1).toFloat() * BEAM_RADIUS
            val z1 = sin(angle1).toFloat() * BEAM_RADIUS
            val x2 = cos(angle2).toFloat() * BEAM_RADIUS
            val z2 = sin(angle2).toFloat() * BEAM_RADIUS

            // Calculate normals for lighting
            val normalX = ((x1 + x2) * 0.5f)
            val normalZ = ((z1 + z2) * 0.5f)
            val normalLength = Mth.sqrt(normalX * normalX + normalZ * normalZ)
            val nx = normalX / normalLength
            val nz = normalZ / normalLength

            // Bottom-left vertex
            addVertex(consumer, matrix, normal, x1, 0f, z1, 
                     u1, v0 + vOffset, color, alphaBottom, nx, nz)

            // Bottom-right vertex
            addVertex(consumer, matrix, normal, x2, 0f, z2, 
                     u0, v0 + vOffset, color, alphaBottom, nx, nz)

            // Top-right vertex
            addVertex(consumer, matrix, normal, x2, height, z2, 
                     u0, v1 + vOffset, color, alphaTop, nx, nz)

            // Top-left vertex
            addVertex(consumer, matrix, normal, x1, height, z1, 
                     u1, v1 + vOffset, color, alphaTop, nx, nz)
        }
    }

    /**
     * Adds a single vertex to the buffer with all required data.
     */
    private fun addVertex(
        consumer: VertexConsumer,
        matrix: Matrix4f,
        normalMatrix: org.joml.Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, v: Float,
        color: FloatArray,
        alpha: Float,
        normalX: Float, normalZ: Float
    ) {
        consumer.vertex(matrix, x, y, z)
            .color(color[0], color[1], color[2], alpha)
            .uv(u, v)
            .overlayCoords(0, 10)
            .uv2(LightTexture.FULL_BRIGHT) // Full bright - beams emit light
            .normal(normalMatrix, normalX, 0f, normalZ)
            .endVertex()
    }

    override fun shouldRenderOffScreen(blockEntity: ObeliskBlockEntity): Boolean {
        // Render beams even when block is not in view frustum
        return true
    }

    override fun getViewDistance(): Int {
        // Render beams from farther away (256 blocks)
        return 256
    }

    override fun shouldRender(blockEntity: ObeliskBlockEntity, cameraPos: net.minecraft.world.phys.Vec3): Boolean {
        // Always render if beam should show and within view distance
        return shouldRenderBeam(blockEntity) && 
               blockEntity.blockPos.distToCenterSqr(cameraPos) < (getViewDistance() * getViewDistance()).toDouble()
    }
}
