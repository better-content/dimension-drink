package dev.yourname.instanceddimensions.engine.instance

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.progress.ChunkProgressListener
import net.minecraft.world.RandomSequences
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.storage.LevelStorageSource
import net.minecraft.world.level.storage.ServerLevelData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.BooleanSupplier

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

    override fun tick(hasTimeLeft: BooleanSupplier) {
        if (isHiddenRuntimeLevel()) {
            val profiler = profiler
            profiler.push("instanceddimensionsHiddenTick")
            profiler.pop()
            return
        }
        super.tick(hasTimeLeft)
    }

    override fun getHeight(heightmapType: Heightmap.Types, x: Int, z: Int): Int {
        val chunk = loadedChunkOrNull(x, z)
        if (chunk != null) {
            return chunk.getHeight(heightmapType, x and 15, z and 15) + 1
        }
        if (shouldShortCircuitMissingChunkReads()) {
            logShortCircuit("height", "x=$x z=$z heightmap=$heightmapType fallback=minBuildHeight")
            return minBuildHeight
        }
        return super.getHeight(heightmapType, x, z)
    }

    override fun getBlockState(pos: BlockPos): BlockState {
        if (isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState()
        }
        val chunk = loadedChunkOrNull(pos.x, pos.z)
        if (chunk != null) {
            return chunk.getBlockState(pos)
        }
        if (shouldShortCircuitMissingChunkReads()) {
            logShortCircuit("block-state", "pos=$pos fallback=void_air")
            return Blocks.VOID_AIR.defaultBlockState()
        }
        return super.getBlockState(pos)
    }

    override fun getFluidState(pos: BlockPos): FluidState {
        if (isOutsideBuildHeight(pos)) {
            return Fluids.EMPTY.defaultFluidState()
        }
        val chunk = loadedChunkOrNull(pos.x, pos.z)
        if (chunk != null) {
            return chunk.getFluidState(pos)
        }
        if (shouldShortCircuitMissingChunkReads()) {
            logShortCircuit("fluid-state", "pos=$pos fallback=empty")
            return Fluids.EMPTY.defaultFluidState()
        }
        return super.getFluidState(pos)
    }

    private fun loadedChunkOrNull(x: Int, z: Int): LevelChunk? {
        return chunkSource.getChunkNow(
            SectionPos.blockToSectionCoord(x),
            SectionPos.blockToSectionCoord(z)
        )
    }

    private fun shouldShortCircuitMissingChunkReads(): Boolean {
        // Runtime levels are intentionally bounded to the chunks we explicitly keep loaded.
        // If a caller reaches beyond that window, demanding the chunk here risks a sync load
        // or wait path inside modded tick logic. Returning a fallback is the runtime-level
        // contract for any missing chunk read, regardless of calling thread.
        return true
    }

    private fun isHiddenRuntimeLevel(): Boolean {
        return !InstanceManager.isRuntimeLevelExposed(dimension())
    }

    private fun logShortCircuit(query: String, details: String) {
        val key = "${dimension().location()}|$query"
        val now = gameTime
        val previous = SHORT_CIRCUIT_LOGGED_AT.put(key, now)
        if (previous == null || now - previous >= 20L) {
            logger.info(
                "RuntimeServerLevel short-circuited missing {} read level={} details={} players={} gameTime={} thread={}",
                query,
                dimension().location(),
                details,
                players().size,
                now,
                Thread.currentThread().name
            )
        }
    }

    companion object {
        private val logger = LogUtils.getLogger()
        private val INSTANCE_SEEDS = ConcurrentHashMap<ResourceKey<Level>, Long>()
        private val SHORT_CIRCUIT_LOGGED_AT = ConcurrentHashMap<String, Long>()

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
            val startedAt = System.nanoTime()
            logger.info(
                "Creating RuntimeServerLevel level={} stem={} instanceSeed={} biomeZoomSeed={} debugWorld={} tickTime={}",
                levelKey.location(),
                stem,
                instanceSeed,
                biomeZoomSeed,
                debugWorld,
                tickTime
            )
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
                ).also {
                    logger.info(
                        "Created RuntimeServerLevel level={} in {}ms",
                        levelKey.location(),
                        (System.nanoTime() - startedAt) / 1_000_000L
                    )
                }
            } catch (t: Throwable) {
                INSTANCE_SEEDS.remove(levelKey)
                logger.warn(
                    "Failed to create RuntimeServerLevel level={} after {}ms",
                    levelKey.location(),
                    (System.nanoTime() - startedAt) / 1_000_000L,
                    t
                )
                throw t
            }
        }

        fun forgetSeed(levelKey: ResourceKey<Level>) {
            logger.info("Forgetting runtime seed for {}", levelKey.location())
            INSTANCE_SEEDS.remove(levelKey)
        }

        fun clearSeeds() {
            logger.info("Clearing {} remembered runtime seeds", INSTANCE_SEEDS.size)
            INSTANCE_SEEDS.clear()
            SHORT_CIRCUIT_LOGGED_AT.clear()
        }
    }
}
