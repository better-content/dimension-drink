package dev.yourname.obelisks.network

import dev.yourname.obelisks.ObelisksConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

object ModNetwork {
    private const val PROTOCOL_VERSION = "1"

    val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation(ObelisksConstants.MOD_ID, "main"),
        { PROTOCOL_VERSION },
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    )

    private var packetId = 0

    fun register() {
        CHANNEL.messageBuilder(TeleportButtonPacket::class.java, packetId++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(TeleportButtonPacket::encode)
            .decoder(TeleportButtonPacket::decode)
            .consumerMainThread(TeleportButtonPacket::handle)
            .add()
    }

    fun <T> sendToServer(packet: T) {
        CHANNEL.sendToServer(packet)
    }

    fun <T> sendToPlayer(packet: T, player: ServerPlayer) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
    }
}
