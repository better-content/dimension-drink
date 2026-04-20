package dev.yourname.instanceddimensions.engine.instance

import com.google.common.collect.ImmutableList
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.progress.ChunkProgressListener
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.LevelStorageSource

object InstanceLevelFactory {

    private val logger = LogUtils.getLogger()

    fun create(server: MinecraftServer, template: InstanceTemplate, record: InstanceRecord): CreatedInstanceLevel {
        val startedAt = System.nanoTime()
        logger.info(
            "InstanceLevelFactory.create start instance={} template={} level={} stem={}",
            record.id,
            template.id,
            record.levelKey.location(),
            template.stem
        )
        val stem = resolveStem(server, template)
        val createdLevel = createLevel(server, record, stem)
        createdLevel.worldBorder.applySettings(record.levelState.worldBorderSettings())
        logger.info(
            "InstanceLevelFactory.create complete instance={} level={} elapsed={}ms spawn={} borderSize={}",
            record.id,
            record.levelKey.location(),
            (System.nanoTime() - startedAt) / 1_000_000L,
            createdLevel.sharedSpawnPos,
            createdLevel.worldBorder.size
        )
        return CreatedInstanceLevel(createdLevel)
    }

    private fun createLevel(server: MinecraftServer, record: InstanceRecord, stem: LevelStem): ServerLevel {
        val serverLevelData = InstanceLevelData(record.levelState.deepCopy())
        val debugWorld = server.worldData.isDebugWorld
        val instanceSeed = resolveInstanceSeed(server, record)
        val obfuscatedSeed = BiomeManager.obfuscateSeed(instanceSeed)
        logger.info(
            "Creating runtime level backing objects instance={} level={} seed={} obfuscatedSeed={} debugWorld={}",
            record.id,
            record.levelKey.location(),
            instanceSeed,
            obfuscatedSeed,
            debugWorld
        )

        return RuntimeServerLevel.create(
            server,
            currentServerExecutor(server),
            currentStorageSource(server),
            serverLevelData,
            record.levelKey,
            stem,
            NoOpChunkProgressListener,
            debugWorld,
            instanceSeed,
            obfuscatedSeed,
            ImmutableList.of(),
            false,
            null
        )
    }

    private fun resolveStem(server: MinecraftServer, template: InstanceTemplate): LevelStem {
        val stemLocation = ResourceLocation.tryParse(template.stem)
            ?: error("Invalid level stem for template ${template.id}: ${template.stem}")
        logger.info("Resolving level stem {} for template {}", stemLocation, template.id)
        return server.registryAccess()
            .registryOrThrow(net.minecraft.core.registries.Registries.LEVEL_STEM)
            .get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, stemLocation))
            ?.also {
                logger.info("Resolved level stem {} for template {}", stemLocation, template.id)
            }
            ?: error("Unknown level stem for template ${template.id}: $stemLocation")
    }

    @Suppress("DEPRECATION")
    private fun currentServerExecutor(server: MinecraftServer) = server as java.util.concurrent.Executor

    private fun currentStorageSource(server: MinecraftServer): LevelStorageSource.LevelStorageAccess {
        val serverClass = MinecraftServer::class.java
        val preferredNames = listOf("storageSource", "f_129744_")

        preferredNames.forEach { fieldName ->
            runCatching {
                val resolved = serverClass
                    .getDeclaredField(fieldName)
                    .apply { isAccessible = true }
                    .get(server) as LevelStorageSource.LevelStorageAccess
                logger.info("Resolved MinecraftServer LevelStorageAccess via field {}", fieldName)
                return resolved
            }
        }

        serverClass.declaredFields
            .firstOrNull { LevelStorageSource.LevelStorageAccess::class.java.isAssignableFrom(it.type) }
            ?.let { field ->
                field.isAccessible = true
                logger.info("Resolved MinecraftServer LevelStorageAccess via fallback field {}", field.name)
                return field.get(server) as LevelStorageSource.LevelStorageAccess
            }

        error("Could not resolve MinecraftServer LevelStorageAccess field in ${serverClass.name}")
    }

    private fun resolveInstanceSeed(server: MinecraftServer, record: InstanceRecord): Long {
        return if (record.instanceSeed == InstanceRecord.UNSET_SEED) {
            server.worldData.worldGenOptions().seed()
        } else {
            record.instanceSeed
        }
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
