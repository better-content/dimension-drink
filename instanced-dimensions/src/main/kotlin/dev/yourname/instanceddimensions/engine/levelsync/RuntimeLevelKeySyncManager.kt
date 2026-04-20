package dev.yourname.instanceddimensions.engine.levelsync

import com.mojang.logging.LogUtils
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
    private val logger = LogUtils.getLogger()

    private val channel: SimpleChannel = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation.fromNamespaceAndPath(MOD_ID, NETWORK_CHANNEL))
        .networkProtocolVersion { PROTOCOL_VERSION }
        .clientAcceptedVersions(PROTOCOL_VERSION::equals)
        .serverAcceptedVersions(PROTOCOL_VERSION::equals)
        .simpleChannel()

    private var nextMessageId = 0
    private var initialized = false
    private val knownRuntimeLevelsByPlayer = linkedMapOf<java.util.UUID, LinkedHashSet<ResourceKey<Level>>>()

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
        logger.info("Initialized runtime level sync channel {} protocol={}", NETWORK_CHANNEL, PROTOCOL_VERSION)
    }

    fun announceRuntimeLevel(server: MinecraftServer, levelKey: ResourceKey<Level>) {
        if (server.playerList.players.isEmpty()) {
            logger.info("Skipping runtime level announce for {} because no players are connected", levelKey.location())
            return
        }
        logger.info("Announcing runtime level {} to {} players", levelKey.location(), server.playerList.players.size)
        server.playerList.players.forEach { player ->
            val knownLevels = knownRuntimeLevelsByPlayer.getOrPut(player.uuid) { linkedSetOf() }
            if (knownLevels.add(levelKey)) {
                sendDelta(player, additions = listOf(levelKey), removals = emptyList())
            }
        }
    }

    fun revokeRuntimeLevel(server: MinecraftServer, levelKey: ResourceKey<Level>) {
        if (server.playerList.players.isEmpty()) {
            logger.info("Skipping runtime level revoke for {} because no players are connected", levelKey.location())
            return
        }
        logger.info("Revoking runtime level {} from {} players", levelKey.location(), server.playerList.players.size)
        server.playerList.players.forEach { player ->
            val knownLevels = knownRuntimeLevelsByPlayer[player.uuid] ?: return@forEach
            if (knownLevels.remove(levelKey)) {
                sendDelta(player, additions = emptyList(), removals = listOf(levelKey))
            }
            if (knownLevels.isEmpty()) {
                knownRuntimeLevelsByPlayer.remove(player.uuid)
            }
        }
    }

    fun ensurePlayerKnowsLevel(player: ServerPlayer, levelKey: ResourceKey<Level>) {
        if (!player.connection.connection.isConnected) {
            logger.info("Skipping ensurePlayerKnowsLevel for {} because player {} is disconnected", levelKey.location(), player.scoreboardName)
            return
        }
        val knownLevels = knownRuntimeLevelsByPlayer.getOrPut(player.uuid) { linkedSetOf() }
        if (knownLevels.add(levelKey)) {
            logger.info("Ensuring player {} knows runtime level {}", player.scoreboardName, levelKey.location())
            sendDelta(player, additions = listOf(levelKey), removals = emptyList())
        }
    }

    fun syncRuntimeLevels(player: ServerPlayer, levelKeys: Collection<ResourceKey<Level>>) {
        if (!player.connection.connection.isConnected) {
            logger.info("Skipping syncRuntimeLevels for disconnected player {}", player.scoreboardName)
            return
        }
        val desiredLevels = LinkedHashSet(levelKeys)
        val knownLevels = knownRuntimeLevelsByPlayer.getOrPut(player.uuid) { linkedSetOf() }
        val additions = desiredLevels.filterNot(knownLevels::contains)
        val removals = knownLevels.filterNot(desiredLevels::contains)
        logger.info(
            "Syncing runtime levels for player {} desired={} additions={} removals={}",
            player.scoreboardName,
            desiredLevels.map { it.location().toString() },
            additions.map { it.location().toString() },
            removals.map { it.location().toString() }
        )
        knownLevels.clear()
        knownLevels.addAll(desiredLevels)
        if (knownLevels.isEmpty()) {
            knownRuntimeLevelsByPlayer.remove(player.uuid)
        }
        sendDelta(player, additions = additions, removals = removals)
    }

    fun forgetPlayer(playerId: java.util.UUID) {
        logger.info("Forgetting runtime level sync state for player {}", playerId)
        knownRuntimeLevelsByPlayer.remove(playerId)
    }

    fun reset() {
        logger.info("Resetting runtime level sync state for {} players", knownRuntimeLevelsByPlayer.size)
        knownRuntimeLevelsByPlayer.clear()
    }

    private fun sendDelta(
        player: ServerPlayer,
        additions: Collection<ResourceKey<Level>>,
        removals: Collection<ResourceKey<Level>>
    ) {
        if (!player.connection.connection.isConnected || (additions.isEmpty() && removals.isEmpty())) {
            return
        }
        logger.info(
            "Sending runtime level delta to player {} additions={} removals={}",
            player.scoreboardName,
            additions.map { it.location().toString() },
            removals.map { it.location().toString() }
        )
        channel.send(PacketDistributor.PLAYER.with { player }, RuntimeLevelKeysPacket(additions = additions.toList(), removals = removals.toList()))
    }

    private fun nextId(): Int = nextMessageId++
}
