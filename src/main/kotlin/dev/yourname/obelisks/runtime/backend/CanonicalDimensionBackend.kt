package dev.yourname.obelisks.runtime.backend

import com.mojang.logging.LogUtils
import dev.yourname.obelisks.data.CanonicalTargetResolver
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object CanonicalDimensionBackend : RunWorldBackend {
    private const val SPAWN_CLEARANCE = 3
    private const val SITE_SAVE_INTERVAL_TICKS = 100L
    private const val SUPPORT_BLOCK_ID = "minecraft:stone"

    private val logger = LogUtils.getLogger()
    private val playerBindings = linkedMapOf<UUID, UUID>()
    private val configCache = linkedMapOf<String, BackendConfig>()
    private var siteStateDirty = false

    override fun validateTemplate(server: MinecraftServer, templateId: String): String? {
        val target = CanonicalTargetResolver.targetLevelKey(templateId) ?: return "target dimension for '$templateId' is unknown"
        if (server.getLevel(target) == null) {
            return "target dimension ${target.location()} is not loaded"
        }
        return null
    }

    override fun requestPreparedSite(
        server: MinecraftServer,
        templateId: String,
        originLevelKey: ResourceKey<Level>?,
        originObeliskPos: BlockPos?
    ): PreparedSiteResult {
        validateTemplate(server, templateId)?.let { return PreparedSiteResult.Rejected(it) }
        val targetKey = CanonicalTargetResolver.targetLevelKey(templateId)
            ?: return PreparedSiteResult.Rejected("target dimension for '$templateId' is unknown")
        val level = server.getLevel(targetKey)
            ?: return PreparedSiteResult.Rejected("target dimension ${targetKey.location()} is not loaded")
        val data = RunSiteSavedData.get(server)
        val config = configFor(templateId)
        val mapped = mapOriginToTarget(config, templateId, originObeliskPos ?: BlockPos.ZERO)
        val provisionalCenter = BlockPos(mapped.x, emergencySpawnY(level), mapped.z)
        val now = gameTime(server)
        val existing = reusableSite(data, templateId, originLevelKey, originObeliskPos)
        val record = existing ?: RunSiteRecord(
            siteId = UUID.randomUUID(),
            templateId = templateId,
            backendLevelKey = targetKey,
            siteCenter = provisionalCenter,
            siteBounds = boundsFor(provisionalCenter, level, config),
            siteIndex = 0L,
            originLevelKey = originLevelKey,
            originObeliskPos = originObeliskPos,
            state = SiteState.PREPARED,
            createdGameTime = now,
            updatedGameTime = now
        )

        record.originLevelKey = originLevelKey
        record.originObeliskPos = originObeliskPos
        record.runId = null
        record.ownerId = null
        record.state = SiteState.PREPARED
        record.updatedGameTime = now
        if (record.spawnPos == null) {
            record.siteCenter = provisionalCenter
            record.siteBounds = boundsFor(provisionalCenter, level, config)
        }

        if (existing == null) {
            data.upsert(record)
        } else {
            markSiteDirty(server, immediate = true)
        }
        return PreparedSiteResult.Accepted(record.preparedHandle())
    }

    override fun pollPreparedSite(server: MinecraftServer, handle: PreparedSiteHandle): PreparedSiteStatus {
        val record = site(server, handle.siteId) ?: return PreparedSiteStatus.Failed("site record is missing")
        return PreparedSiteStatus.Ready(record.spawnPos ?: BlockPos.ZERO)
    }

    override fun activateRun(server: MinecraftServer, handle: PreparedSiteHandle, runId: UUID, ownerId: UUID?): ActiveSiteResult {
        val record = site(server, handle.siteId) ?: return ActiveSiteResult.Rejected("site record is missing")
        record.state = SiteState.ACTIVE
        record.runId = runId
        record.ownerId = ownerId
        record.updatedGameTime = gameTime(server)
        markSiteDirty(server, immediate = true)
        logger.info(
            "Activated canonical font run site={} run={} target={} center={} origin={} {}",
            record.siteId,
            runId,
            record.backendLevelKey.location(),
            record.siteCenter,
            record.originLevelKey?.location(),
            record.originObeliskPos
        )
        return ActiveSiteResult.Accepted(record.activeHandle() ?: return ActiveSiteResult.Rejected("site did not activate"), record.spawnPos ?: BlockPos.ZERO)
    }

    override fun enterPlayer(player: ServerPlayer, handle: ActiveSiteHandle): EnterRunResult {
        val record = site(player.server, handle.siteId) ?: return EnterRunResult.Rejected("site record is missing")
        val level = player.server.getLevel(record.backendLevelKey)
            ?: return EnterRunResult.Rejected("target dimension ${record.backendLevelKey.location()} is not loaded")
        val spawn = resolveArrival(level, record, configFor(record.templateId))
        if (record.touchedChunks.add(ChunkPos(spawn))) {
            markSiteDirty(player.server)
        }
        record.updatedGameTime = gameTime(player.server)
        player.teleportTo(level, spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5, player.yRot, player.xRot)
        playEntrySounds(level, spawn)
        playerBindings[player.uuid] = record.siteId
        return EnterRunResult.Entered
    }

    override fun returnPlayer(player: ServerPlayer): ReturnRunResult {
        val siteId = playerBindings[player.uuid]
            ?: siteForPlayer(player)?.siteId
            ?: return ReturnRunResult.NotBound
        val record = site(player.server, siteId) ?: return ReturnRunResult.Rejected("site record is missing")
        val originLevelKey = record.originLevelKey ?: return ReturnRunResult.Rejected("origin level is missing")
        val originPos = record.originObeliskPos ?: return ReturnRunResult.Rejected("origin anchor is missing")
        val originLevel = player.server.getLevel(originLevelKey)
            ?: return ReturnRunResult.Rejected("origin level ${originLevelKey.location()} is not loaded")
        val x = originPos.x + 0.5
        val y = originPos.y + 1.0
        val z = originPos.z + 0.5
        player.fallDistance = 0.0f
        player.setDeltaMovement(Vec3.ZERO)
        player.teleportTo(originLevel, x, y, z, player.yRot, player.xRot)
        player.moveTo(x, y, z, player.yRot, player.xRot)
        player.connection.resetPosition()
        player.fallDistance = 0.0f
        playReturnSounds(originLevel, BlockPos.containing(x, y, z))
        playerBindings.remove(player.uuid)
        return ReturnRunResult.Returned
    }

    override fun destroyRun(server: MinecraftServer, handle: ActiveSiteHandle, reason: String) {
        val record = site(server, handle.siteId) ?: return
        server.getLevel(record.backendLevelKey)?.let { level ->
            despawnRunMobs(level, record)
        }
        playerBindings.entries.removeIf { (_, siteId) -> siteId == record.siteId }
        record.state = SiteState.SCARRED
        record.runId = null
        record.ownerId = null
        record.updatedGameTime = gameTime(server)
        markSiteDirty(server, immediate = true)
        logger.info(
            "Closed canonical font run site={} target={} center={} reason={}",
            record.siteId,
            record.backendLevelKey.location(),
            record.siteCenter,
            reason
        )
    }

    override fun tick(server: MinecraftServer) {
        val data = RunSiteSavedData.get(server)
        val activeSites = data.values().filter { it.state == SiteState.ACTIVE }
        activeSites.forEach { record -> record.updatedGameTime = gameTime(server) }
        if (activeSites.isNotEmpty()) {
            val activeByDimension = activeSites.groupBy { it.backendLevelKey }
            server.playerList.players.forEach { player ->
                activeByDimension[player.serverLevel().dimension()]?.forEach { record ->
                    if (record.siteBounds.contains(player.blockPosition())) {
                        if (record.touchedChunks.add(ChunkPos(player.blockPosition()))) {
                            markSiteDirty(server)
                        }
                        playerBindings[player.uuid] = record.siteId
                    }
                }
            }
        }

        flushDirtySites(server, force = false)
    }

    override fun clearPlayer(playerId: UUID) {
        playerBindings.remove(playerId)
    }

    override fun isPlayerInRun(player: ServerPlayer, handle: ActiveSiteHandle): Boolean {
        return player.serverLevel().dimension() == handle.backendLevelKey && handle.siteBounds.contains(player.blockPosition())
    }

    override fun describeProgress(server: MinecraftServer, handle: PreparedSiteHandle): String {
        val record = site(server, handle.siteId) ?: return "missing"
        return "ready target=${record.backendLevelKey.location()} arrival=${record.spawnPos ?: record.siteCenter}"
    }

    override fun findActiveHandle(server: MinecraftServer, siteId: UUID): ActiveSiteHandle? = site(server, siteId)?.activeHandle()

    internal fun isChunkLoadedForTests(level: ServerLevel, blockX: Int, blockZ: Int): Boolean {
        return level.chunkSource.getChunkNow(blockX shr 4, blockZ shr 4) != null
    }

    private fun reusableSite(
        data: RunSiteSavedData,
        templateId: String,
        originLevelKey: ResourceKey<Level>?,
        originObeliskPos: BlockPos?
    ): RunSiteRecord? {
        return data.find {
            it.templateId == templateId &&
                it.originLevelKey == originLevelKey &&
                it.originObeliskPos == originObeliskPos &&
                it.state != SiteState.ACTIVE
        }
    }

    private fun resolveArrival(level: ServerLevel, record: RunSiteRecord, config: BackendConfig): BlockPos {
        val resolvedFloor = if (record.touchedChunks.isNotEmpty() && record.spawnPos != null) {
            record.spawnPos!!.below()
        } else {
            val desired = record.siteCenter
            findSafeFloor(level, desired.x, desired.z) ?: BlockPos(desired.x, emergencySpawnY(level) - 1, desired.z)
        }
        ensureArrivalAnchor(level, record, resolvedFloor)
        val spawn = resolvedFloor.above().immutable()
        record.spawnPos = spawn
        record.siteCenter = spawn
        record.siteBounds = boundsFor(spawn, level, config)
        markSiteDirty(level.server, immediate = true)
        return spawn
    }

    private fun ensureArrivalAnchor(level: ServerLevel, record: RunSiteRecord, floor: BlockPos) {
        val supportBlock = resolveSupportBlock()
        for (x in -1..1) {
            for (z in -1..1) {
                level.setBlock(floor.offset(x, 0, z), supportBlock.defaultBlockState(), 3)
                for (dy in 1..SPAWN_CLEARANCE) {
                    level.setBlock(floor.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
        level.setBlock(floor, ModBlocks.RETURN_PAD.get().defaultBlockState(), 3)
    }

    private fun resolveSupportBlock(): net.minecraft.world.level.block.Block {
        return blockOrNull(SUPPORT_BLOCK_ID) ?: Blocks.STONE
    }

    private fun blockOrNull(id: String): net.minecraft.world.level.block.Block? {
        val location = ResourceLocation.tryParse(id) ?: return null
        val block = BuiltInRegistries.BLOCK.get(location)
        return block.takeUnless { it == Blocks.AIR }
    }

    private fun playEntrySounds(level: ServerLevel, at: BlockPos) {
        level.playSound(null, at, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.55f, 1.25f)
        level.playSound(null, at, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.45f, 1.65f)
    }

    private fun playReturnSounds(level: ServerLevel, at: BlockPos) {
        level.playSound(null, at, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.65f, 0.85f)
        level.playSound(null, at, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.45f, 1.3f)
    }

    private fun mapOriginToTarget(config: BackendConfig, templateId: String, origin: BlockPos): BlockPos {
        val scale = config.coordinateScale ?: CanonicalTargetResolver.coordinateScale(templateId)
        return BlockPos((origin.x * scale).roundToInt(), origin.y, (origin.z * scale).roundToInt())
    }

    private fun findSafeFloor(level: ServerLevel, x: Int, z: Int): BlockPos? {
        level.getChunk(BlockPos(x, level.minBuildHeight, z))
        val highestFeetY = level.maxBuildHeight - SPAWN_CLEARANCE - 1
        for (y in highestFeetY downTo level.minBuildHeight + 1) {
            val floor = BlockPos(x, y - 1, z)
            val feet = BlockPos(x, y, z)
            val head = BlockPos(x, y + 1, z)
            val floorState = level.getBlockState(floor)
            val floorFluid = level.getFluidState(floor)
            if (
                floorState.isSolid &&
                !floorState.`is`(Blocks.BEDROCK) &&
                floorFluid.isEmpty &&
                level.getBlockState(feet).isAir &&
                level.getBlockState(head).isAir &&
                level.getFluidState(feet).isEmpty &&
                level.getFluidState(head).isEmpty
            ) {
                return floor
            }
        }
        return null
    }

    private fun emergencySpawnY(level: ServerLevel): Int {
        return max(level.minBuildHeight + 80, 72).coerceAtMost(level.maxBuildHeight - 4)
    }

    private fun despawnRunMobs(level: ServerLevel, record: RunSiteRecord) {
        level.getEntitiesOfClass(
            Mob::class.java,
            AABB(
                record.siteBounds.minX.toDouble(),
                record.siteBounds.minY.toDouble(),
                record.siteBounds.minZ.toDouble(),
                record.siteBounds.maxX.toDouble(),
                record.siteBounds.maxY.toDouble(),
                record.siteBounds.maxZ.toDouble()
            )
        ).forEach(Mob::discard)
    }

    private fun boundsFor(center: BlockPos, level: ServerLevel, config: BackendConfig): SiteBounds {
        return SiteBounds(
            minX = center.x - config.runRadius,
            minY = level.minBuildHeight,
            minZ = center.z - config.runRadius,
            maxX = center.x + config.runRadius,
            maxY = level.maxBuildHeight - 1,
            maxZ = center.z + config.runRadius
        )
    }

    private fun site(server: MinecraftServer, siteId: UUID): RunSiteRecord? {
        return RunSiteSavedData.get(server).get(siteId)
    }

    private fun siteForPlayer(player: ServerPlayer): RunSiteRecord? {
        return RunSiteSavedData.get(player.server).find {
            it.state == SiteState.ACTIVE &&
                it.backendLevelKey == player.serverLevel().dimension() &&
                it.siteBounds.contains(player.blockPosition())
        }
    }

    private fun markSiteDirty(server: MinecraftServer, immediate: Boolean = false) {
        if (immediate) {
            RunSiteSavedData.get(server).setDirty()
            siteStateDirty = false
            return
        }
        siteStateDirty = true
    }

    private fun flushDirtySites(server: MinecraftServer, force: Boolean) {
        if (!siteStateDirty) {
            return
        }
        if (!force && gameTime(server) % SITE_SAVE_INTERVAL_TICKS != 0L) {
            return
        }
        RunSiteSavedData.get(server).setDirty()
        siteStateDirty = false
    }

    private fun gameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun configFor(templateId: String): BackendConfig {
        return configCache.getOrPut(templateId) {
            val definition = ObeliskDataManager.getObelisk(templateId)
                ?: ObeliskDataManager.allObelisks().firstOrNull {
                    it.instanceTemplateId == templateId || it.targetDimension == templateId
                }
            BackendConfig.from(definition, templateId)
        }
    }

    private data class BackendConfig(
        val coordinateScale: Double?,
        val runRadius: Int
    ) {
        companion object {
            fun from(definition: ObeliskDefinition?, templateId: String): BackendConfig {
                return BackendConfig(
                    coordinateScale = definition?.coordinateScale ?: CanonicalTargetResolver.coordinateScale(templateId),
                    runRadius = definition?.runRadius ?: 96
                )
            }
        }
    }
}
