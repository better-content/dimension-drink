package dev.yourname.instanceddimensions.engine.instance

import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.progress.ChunkProgressListener
import net.minecraft.world.RandomSequences
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.ServerLevelData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class RuntimeServerLevel private constructor(
    server: MinecraftServer,
    executor: Executor,
    storageAccess: LevelStorageSource.LevelStorageAccess,
    levelData: ServerLevelData,
    levelKey: ResourceKey<Level>,
    stem: LevelStem,
    progressListener: ChunkProgressListener,
    debugWorld: Boolean,
    biomeZoomSeed: Long,
    customSpawners: List<CustomSpawner>,
    tickTime: Boolean,
    randomSequences: RandomSequences?
) : ServerLevel(
    server,
    executor,
    storageAccess,
    levelData,
    levelKey,
    stem,
    progressListener,
    debugWorld,
    biomeZoomSeed,
    customSpawners,
    tickTime,
    randomSequences
) {
    override fun getSeed(): Long = INSTANCE_SEEDS[dimension()] ?: super.getSeed()

    companion object {
        private val INSTANCE_SEEDS = ConcurrentHashMap<ResourceKey<Level>, Long>()

        fun create(
            server: MinecraftServer,
            executor: Executor,
            storageAccess: LevelStorageSource.LevelStorageAccess,
            levelData: ServerLevelData,
            levelKey: ResourceKey<Level>,
            stem: LevelStem,
            progressListener: ChunkProgressListener,
            debugWorld: Boolean,
            instanceSeed: Long,
            biomeZoomSeed: Long,
            customSpawners: List<CustomSpawner>,
            tickTime: Boolean,
            randomSequences: RandomSequences?
        ): RuntimeServerLevel {
            INSTANCE_SEEDS[levelKey] = instanceSeed
            return try {
                RuntimeServerLevel(
                    server,
                    executor,
                    storageAccess,
                    levelData,
                    levelKey,
                    stem,
                    progressListener,
                    debugWorld,
                    biomeZoomSeed,
                    customSpawners,
                    tickTime,
                    randomSequences
                )
            } catch (t: Throwable) {
                INSTANCE_SEEDS.remove(levelKey)
                throw t
            }
        }

        fun forgetSeed(levelKey: ResourceKey<Level>) {
            INSTANCE_SEEDS.remove(levelKey)
        }

        fun clearSeeds() {
            INSTANCE_SEEDS.clear()
        }
    }
}
