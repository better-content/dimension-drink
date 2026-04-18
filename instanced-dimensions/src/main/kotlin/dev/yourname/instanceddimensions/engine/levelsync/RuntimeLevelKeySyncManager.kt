package dev.yourname.instanceddimensions.engine.levelsync

import dev.yourname.instanceddimensions.MOD_ID
import dev.yourname.instanceddimensions.NETWORK_CHANNEL
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

object RuntimeLevelKeySyncManager {

    private const val PROTOCOL_VERSION = "1"

    private val channel: SimpleChannel = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(MOD_ID, NETWORK_CHANNEL))
        .networkProtocolVersion { PROTOCOL_VERSION }
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel()

    private var nextMessageId = 0
    private var initialized = false

    fun init() {
        if (initialized) {
            return
        }

        channel.messageBuilder(RuntimeLevelKeysPacket::class.java, nextId(), NetworkDirection.PLAY_TO_CLIENT)
            .decoder(RuntimeLevelKeysPacket::decode)
            .encoder(RuntimeLevelKeysPacket::encode)
            .consumerMainThread(RuntimeLevelKeysPacket::handle)
            .add()

        initialized = true
    }

    fun announceRuntimeLevel(server: MinecraftServer, levelKey: ResourceKey<Level>) {
        if (server.playerList.players.isEmpty()) {
            return
        }
        channel.send(PacketDistributor.ALL.noArg(), RuntimeLevelKeysPacket(additions = listOf(levelKey), removals = emptyList()))
    }

    fun revokeRuntimeLevel(server: MinecraftServer, levelKey: ResourceKey<Level>) {
        if (server.playerList.players.isEmpty()) {
            return
        }
        channel.send(PacketDistributor.ALL.noArg(), RuntimeLevelKeysPacket(additions = emptyList(), removals = listOf(levelKey)))
    }

    fun ensurePlayerKnowsLevel(player: ServerPlayer, levelKey: ResourceKey<Level>) {
        if (!player.connection.connection.isConnected) {
            return
        }
        channel.send(PacketDistributor.PLAYER.with { player }, RuntimeLevelKeysPacket(additions = listOf(levelKey), removals = emptyList()))
    }

    fun syncRuntimeLevels(player: ServerPlayer, levelKeys: Collection<ResourceKey<Level>>) {
        if (!player.connection.connection.isConnected || levelKeys.isEmpty()) {
            return
        }
        channel.send(PacketDistributor.PLAYER.with { player }, RuntimeLevelKeysPacket(additions = levelKeys.toList(), removals = emptyList()))
    }

    private fun nextId(): Int = nextMessageId++
}
