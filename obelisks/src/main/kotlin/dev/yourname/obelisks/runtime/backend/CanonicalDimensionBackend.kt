package dev.yourname.obelisks.runtime.backend

import com.mojang.logging.LogUtils
import dev.yourname.obelisks.data.CanonicalTargetResolver
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.data.ObeliskDefinition
import dev.yourname.obelisks.registry.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Mob
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object CanonicalDimensionBackend : RunWorldBackend {
    private const val SPAWN_CLEARANCE = 3
    private const val SCAR_EDIT_BUDGET_PER_TICK = 384
    private const val SITE_SAVE_INTERVAL_TICKS = 100L

    private val logger = LogUtils.getLogger()
    private val playerBindings = linkedMapOf<UUID, UUID>()
    private val configCache = linkedMapOf<String, BackendConfig>()
    private val scarQueues = linkedMapOf<UUID, ScarQueue>()
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
            "Activated canonical obelisk run site={} run={} target={} center={} origin={} {}",
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
        playerBindings.remove(player.uuid)
        return ReturnRunResult.Returned
    }

    override fun destroyRun(server: MinecraftServer, handle: ActiveSiteHandle, reason: String) {
        val record = site(server, handle.siteId) ?: return
        server.getLevel(record.backendLevelKey)?.let { level ->
            despawnRunMobs(level, record)
            enqueueScar(level, record, configFor(record.templateId), force = true)
        }
        playerBindings.entries.removeIf { (_, siteId) -> siteId == record.siteId }
        record.state = SiteState.SCARRED
        record.runId = null
        record.ownerId = null
        record.updatedGameTime = gameTime(server)
        markSiteDirty(server, immediate = true)
        logger.info(
            "Closed canonical obelisk run site={} target={} center={} scarredColumns={} reason={}",
            record.siteId,
            record.backendLevelKey.location(),
            record.siteCenter,
            record.scarredColumns.size,
            reason
        )
    }

    override fun tick(server: MinecraftServer) {
        processScarQueues(server)

        val data = RunSiteSavedData.get(server)
        val activeSites = data.values().filter { it.state == SiteState.ACTIVE }
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
            val now = gameTime(server)
            activeSites.forEach { record ->
                val config = configFor(record.templateId)
                if (record.runId != null && now - record.lastScarGameTime >= config.scarIntervalTicks) {
                    server.getLevel(record.backendLevelKey)?.let { level ->
                        enqueueScar(level, record, config, force = false)
                    }
                    record.lastScarGameTime = now
                    markSiteDirty(server)
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
        return "ready target=${record.backendLevelKey.location()} arrival=${record.spawnPos ?: record.siteCenter} scars=${record.scarredColumns.size}"
    }

    override fun findActiveHandle(server: MinecraftServer, siteId: UUID): ActiveSiteHandle? = site(server, siteId)?.activeHandle()

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
        for (x in -1..1) {
            for (z in -1..1) {
                val column = ColumnPos(floor.x + x, floor.z + z)
                record.protectedColumns += column
                record.scarredColumns.remove(column)
                level.setBlock(floor.offset(x, 0, z), Blocks.OBSIDIAN.defaultBlockState(), 3)
                for (dy in 1..SPAWN_CLEARANCE) {
                    level.setBlock(floor.offset(x, dy, z), Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }
        level.setBlock(floor, ModBlocks.RETURN_PAD.get().defaultBlockState(), 3)
    }

    private fun enqueueScar(level: ServerLevel, record: RunSiteRecord, config: BackendConfig, force: Boolean): Boolean {
        val players = level.players().filter { player -> record.siteBounds.contains(player.blockPosition()) }
        val centers = if (players.isEmpty()) listOf(record.spawnPos ?: record.siteCenter) else players.map { it.blockPosition() }
        val queue = scarQueues.getOrPut(record.siteId) { ScarQueue(record.siteId, record.backendLevelKey) }
        queue.levelKey = record.backendLevelKey
        var queued = 0
        centers.forEach { center ->
            for (radius in 1..config.scarRadius) {
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        if (abs(dx) != radius && abs(dz) != radius) continue
                        val column = ColumnPos(center.x + dx, center.z + dz)
                        if (column in record.protectedColumns || column in record.scarredColumns || column in queue.queuedColumns) {
                            continue
                        }
                        queue.queuedColumns += column
                        queue.tasks += VerticalClearTask(record.siteId, column, level.minBuildHeight, level.maxBuildHeight)
                        queued++
                        if (!force && queued >= config.scarColumnsPerInterval) {
                            markSiteDirty(level.server)
                            return queued > 0
                        }
                    }
                }
            }
        }
        if (queued > 0) {
            markSiteDirty(level.server)
        }
        return queued > 0
    }

    private fun processScarQueues(server: MinecraftServer) {
        if (scarQueues.isEmpty()) {
            return
        }
        var remainingBudget = SCAR_EDIT_BUDGET_PER_TICK
        val stalled = linkedSetOf<UUID>()
        while (remainingBudget > 0 && stalled.size < scarQueues.size) {
            val siteId = scarQueues.keys.firstOrNull { it !in stalled } ?: break
            val queue = scarQueues.remove(siteId) ?: break
            val level = server.getLevel(queue.levelKey)
            if (level == null) {
                continue
            }
            val progress = drainScarQueue(level, queue, remainingBudget)
            remainingBudget -= progress
            if (queue.tasks.isNotEmpty()) {
                scarQueues[siteId] = queue
                if (progress <= 0) {
                    stalled += siteId
                }
            }
        }
    }

    private fun drainScarQueue(level: ServerLevel, queue: ScarQueue, budget: Int): Int {
        var remaining = budget
        var spent = 0
        while (remaining > 0 && queue.tasks.isNotEmpty()) {
            val task = queue.tasks.first()
            val used = task.apply(level, remaining)
            if (used <= 0) {
                break
            }
            spent += used
            remaining -= used
            if (task.isComplete()) {
                queue.tasks.removeFirst()
                val record = site(level.server, task.siteId)
                if (record != null) {
                    task.onCompleted(record)
                    queue.queuedColumns.remove(task.column)
                    markSiteDirty(level.server)
                }
            }
        }
        return spent
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

    private data class ScarQueue(
        val siteId: UUID,
        var levelKey: ResourceKey<Level>,
        val tasks: ArrayDeque<VerticalClearTask> = ArrayDeque(),
        val queuedColumns: MutableSet<ColumnPos> = linkedSetOf()
    )

    private class VerticalClearTask(
        val siteId: UUID,
        val column: ColumnPos,
        private val fromY: Int,
        private val untilYExclusive: Int
    ) {
        private var nextY: Int = fromY
        private val cursor = BlockPos.MutableBlockPos()

        fun apply(level: ServerLevel, budget: Int): Int {
            var spent = 0
            while (spent < budget && nextY < untilYExclusive) {
                cursor.set(column.x, nextY, column.z)
                val state = level.getBlockState(cursor)
                if (!state.isAir || !level.getFluidState(cursor).isEmpty) {
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3)
                }
                nextY++
                spent++
            }
            return spent
        }

        fun isComplete(): Boolean = nextY >= untilYExclusive

        fun onCompleted(record: RunSiteRecord) {
            record.scarredColumns += column
        }
    }

    private data class BackendConfig(
        val coordinateScale: Double?,
        val runRadius: Int,
        val scarRadius: Int,
        val scarIntervalTicks: Long,
        val scarColumnsPerInterval: Int
    ) {
        companion object {
            fun from(definition: ObeliskDefinition?, templateId: String): BackendConfig {
                return BackendConfig(
                    coordinateScale = definition?.coordinateScale ?: CanonicalTargetResolver.coordinateScale(templateId),
                    runRadius = definition?.runRadius ?: 96,
                    scarRadius = definition?.scarRadius ?: 10,
                    scarIntervalTicks = definition?.scarIntervalTicks ?: 20L,
                    scarColumnsPerInterval = definition?.scarColumnsPerInterval ?: 3
                )
            }
        }
    }
}
