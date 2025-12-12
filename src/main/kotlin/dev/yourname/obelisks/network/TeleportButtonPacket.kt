package dev.yourname.obelisks.network

import dev.yourname.obelisks.content.ObeliskBlockEntity
import dev.yourname.obelisks.dimension.DimensionCoordinator
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkEvent
import java.util.function.Supplier

/**
 * Packet sent from client to server when the teleport button is pressed.
 */
class TeleportButtonPacket(
    private val obeliskPos: BlockPos
) {

    fun encode(buf: FriendlyByteBuf) {
        buf.writeBlockPos(obeliskPos)
    }

    companion object {
        fun decode(buf: FriendlyByteBuf): TeleportButtonPacket {
            return TeleportButtonPacket(buf.readBlockPos())
        }

        fun handle(packet: TeleportButtonPacket, ctx: Supplier<NetworkEvent.Context>) {
            ctx.get().enqueueWork {
                val player = ctx.get().sender ?: return@enqueueWork
                val level = player.level() as? ServerLevel ?: return@enqueueWork

                // Get the obelisk block entity
                val be = level.getBlockEntity(packet.obeliskPos) as? ObeliskBlockEntity
                if (be == null) {
                    player.sendSystemMessage(Component.literal("Obelisk not found!"))
                    return@enqueueWork
                }

                // Check if player is in range
                if (player.distanceToSqr(
                        packet.obeliskPos.x.toDouble() + 0.5,
                        packet.obeliskPos.y.toDouble() + 0.5,
                        packet.obeliskPos.z.toDouble() + 0.5
                    ) > 64.0
                ) {
                    player.sendSystemMessage(Component.literal("Too far from obelisk!"))
                    return@enqueueWork
                }

                // Use DimensionCoordinator to enter dimension
                val result = DimensionCoordinator.enterDimension(player, be, packet.obeliskPos, level)

                result.onFailure { error, _ ->
                    player.sendSystemMessage(Component.literal("Failed to activate obelisk: $error"))
                }
            }
            ctx.get().setPacketHandled(true)
        }
    }
}
