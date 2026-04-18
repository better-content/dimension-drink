package dev.yourname.instanceddimensions.engine.instance

import com.google.common.collect.ImmutableList
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.progress.ChunkProgressListener
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.LevelStorageSource

object InstanceLevelFactory {

    fun create(server: MinecraftServer, template: InstanceTemplate, record: InstanceRecord): CreatedInstanceLevel {
        val stem = resolveStem(server, template)
        val createdLevel = createLevel(server, record, stem)
        createdLevel.worldBorder.applySettings(record.levelState.worldBorderSettings())
        return CreatedInstanceLevel(createdLevel)
    }

    private fun createLevel(server: MinecraftServer, record: InstanceRecord, stem: LevelStem): ServerLevel {
        val serverLevelData = InstanceLevelData(record.levelState.deepCopy())
        val debugWorld = server.worldData.isDebugWorld
        val obfuscatedSeed = BiomeManager.obfuscateSeed(server.worldData.worldGenOptions().seed())

        return ServerLevel(
            server,
            currentServerExecutor(server),
            currentStorageSource(server),
            serverLevelData,
            record.levelKey,
            stem,
            NoOpChunkProgressListener,
            debugWorld,
            obfuscatedSeed,
            ImmutableList.of(),
            false,
            null
        )
    }

    private fun resolveStem(server: MinecraftServer, template: InstanceTemplate): LevelStem {
        return server.registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
            .get(template.stem)
            ?: error("Unknown level stem for template ${template.id}: ${template.stem.location()}")
    }

    @Suppress("DEPRECATION")
    private fun currentServerExecutor(server: MinecraftServer) = server as java.util.concurrent.Executor

    private fun currentStorageSource(server: MinecraftServer): LevelStorageSource.LevelStorageAccess {
        val serverClass = MinecraftServer::class.java
        val preferredNames = listOf("storageSource", "f_129744_")

        preferredNames.forEach { fieldName ->
            runCatching {
                return serverClass
                    .getDeclaredField(fieldName)
                    .apply { isAccessible = true }
                    .get(server) as LevelStorageSource.LevelStorageAccess
            }
        }

        serverClass.declaredFields
            .firstOrNull { LevelStorageSource.LevelStorageAccess::class.java.isAssignableFrom(it.type) }
            ?.let { field ->
                field.isAccessible = true
                return field.get(server) as LevelStorageSource.LevelStorageAccess
            }

        error("Could not resolve MinecraftServer LevelStorageAccess field in ${serverClass.name}")
    }

    private object NoOpChunkProgressListener : ChunkProgressListener {
        override fun updateSpawnPos(p_9617_: net.minecraft.world.level.ChunkPos) = Unit
        override fun onStatusChange(
            p_9618_: net.minecraft.world.level.ChunkPos,
            p_9619_: net.minecraft.world.level.chunk.ChunkStatus?
        ) = Unit

        override fun start() = Unit
        override fun stop() = Unit
    }

    data class CreatedInstanceLevel(val level: ServerLevel)
}
