package dev.yourname.instanceddimensions.engine.instance

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.MOD_ID
import dev.yourname.instanceddimensions.api.InstanceService
import dev.yourname.instanceddimensions.compat.C2meCompat
import dev.yourname.instanceddimensions.engine.levelsync.RuntimeLevelKeySyncManager
import dev.yourname.instanceddimensions.events.RuntimeInstanceEvent
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.util.Unit
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.chunk.storage.IOWorker
import net.minecraft.server.MinecraftServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.ArrayDeque
import java.util.UUID

/**
 * Rewrite scaffold for runtime-created `ServerLevel` instances.
 *
 * This remains intentionally small until the engine patch layer is introduced.
 */
object InstanceManager : InstanceService {

    private val logger = LogUtils.getLogger()
    private val templates = linkedMapOf<String, InstanceTemplate>()
    private val instances = linkedMapOf<UUID, InstanceRecord>()
    private val lifecycleRequests = ArrayDeque<InstanceLifecycleRequest>()
    private val liveLevelData = linkedMapOf<UUID, InstanceLevelData>()
    private val warmupChunks = linkedMapOf<UUID, ChunkPos>()
    private val warmupReadyGameTimes = linkedMapOf<UUID, Long>()
    private val closeGraceCounts = linkedMapOf<UUID, Int>()
    private var savedDataDirty = false

    init {
        bootstrapBuiltinTemplates()
    }

    override fun templates(): Collection<InstanceTemplate> = templates.values

    override fun getTemplate(templateId: String): InstanceTemplate? = templates[templateId]

    override fun registerTemplate(template: InstanceTemplate) {
        templates[template.id] = template
    }

    override fun allInstances(): Collection<InstanceHandle> = instances.values.map { it.toHandle() }

    override fun getInstance(id: UUID): InstanceHandle? = instances[id]?.toHandle()

    override fun getInstance(levelKey: ResourceKey<Level>): InstanceHandle? {
        return instances.values.firstOrNull { it.levelKey == levelKey }?.toHandle()
    }

    fun describeCloseState(server: MinecraftServer, id: UUID): String {
        val record = instances[id] ?: return "missing"
        val level = server.getLevel(record.levelKey) ?: return "unloaded"
        val snapshot = drainChunkWork(level, closing = record.state == InstanceState.CLOSING)
        return "state=${record.state} loadedChunks=${snapshot.loadedChunks} pendingTasks=${snapshot.pendingTasks} chunkMapHasWork=${snapshot.chunkMapHasWork} storagePendingWrites=${snapshot.storagePendingWrites}"
    }

    override fun isRuntimeLevel(levelKey: ResourceKey<Level>): Boolean = getInstance(levelKey) != null

    fun isTravelReady(id: UUID): Boolean {
        return !warmupChunks.containsKey(id)
    }

    fun isTravelReadyForLevel(levelKey: ResourceKey<Level>): Boolean {
        val record = instances.values.firstOrNull { it.levelKey == levelKey } ?: return true
        return isTravelReady(record.id)
    }

    override fun createInstance(server: MinecraftServer, templateId: String, ownerId: UUID?): InstanceHandle {
        val template = templates[templateId] ?: error("Unknown instance template: $templateId")
        val levelLocation = nextLevelLocation(template.id)
        val record = InstanceRecord(
            id = UUID.randomUUID(),
            templateId = template.id,
            levelKey = ResourceKey.create(Registries.DIMENSION, levelLocation),
            state = InstanceState.ALLOCATED,
            ownerId = ownerId,
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server),
            levelState = InstanceLevelState.createDefault(server, template.id, levelLocation)
        )
        register(record)
        lifecycleRequests.addLast(InstanceLifecycleRequest.Create(record.id))
        markSavedDataDirty()
        return record.toHandle()
    }

    override fun scheduleDestroy(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        if (record.state == InstanceState.DESTROYED) {
            return false
        }

        record.state = when (record.state) {
            InstanceState.ACTIVE,
            InstanceState.LOADING,
            InstanceState.ALLOCATED -> InstanceState.DRAINING
            InstanceState.DRAINING,
            InstanceState.UNLOADING,
            InstanceState.CLOSING,
            InstanceState.DESTROYED -> record.state
        }
        record.updatedGameTime = currentGameTime(server)
        lifecycleRequests.addLast(InstanceLifecycleRequest.Destroy(id))
        markSavedDataDirty()
        return true
    }

    fun snapshotInstance(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        val loadedLevel = server.getLevel(record.levelKey) ?: return false
        captureRuntimeState(record, loadedLevel)
        markSavedDataDirty()
        return true
    }

    fun register(record: InstanceRecord) {
        instances[record.id] = record
    }

    fun clearInstances() {
        instances.clear()
        lifecycleRequests.clear()
        liveLevelData.clear()
        warmupChunks.clear()
        warmupReadyGameTimes.clear()
        closeGraceCounts.clear()
        savedDataDirty = false
    }

    @Suppress("DEPRECATION")
    fun hasRuntimeWorldSupport(server: MinecraftServer): Boolean {
        return server.forgeGetWorldMap().isNotEmpty()
    }

    fun bootstrapBuiltinTemplates() {
        if (templates.isNotEmpty()) {
            return
        }

        registerTemplate(
            InstanceTemplate(
                id = "overworld",
                stem = ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, LevelStem.OVERWORLD.location()),
                description = "Rewrite bootstrap template for overworld-like instances"
            )
        )
        registerTemplate(
            InstanceTemplate(
                id = "nether",
                stem = ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, LevelStem.NETHER.location()),
                description = "Rewrite bootstrap template for nether-like instances"
            )
        )
        registerTemplate(
            InstanceTemplate(
                id = "end",
                stem = ResourceKey.create(net.minecraft.core.registries.Registries.LEVEL_STEM, LevelStem.END.location()),
                description = "Rewrite bootstrap template for end-like instances"
            )
        )
    }

    fun allocatePlaceholder(templateId: String, levelKey: ResourceKey<Level>, ownerId: UUID? = null): InstanceHandle {
        val record = InstanceRecord(
            id = UUID.randomUUID(),
            templateId = templateId,
            levelKey = levelKey,
            state = InstanceState.ALLOCATED,
            ownerId = ownerId,
            levelState = InstanceLevelState.createDefaultPlaceholder(levelKey.location())
        )
        register(record)
        return record.toHandle()
    }

    fun records(): Collection<InstanceRecord> = instances.values.map { it.deepCopy() }

    fun restoreFromSavedData(server: MinecraftServer) {
        val restored = mutableListOf<InstanceRecord>()
        var pruned = false
        InstanceSavedData.get(server).snapshot().forEach { saved ->
            if (saved.state == InstanceState.DESTROYED) {
                pruned = true
                return@forEach
            }
            if (templates[saved.templateId] == null) {
                logger.warn("Pruning saved runtime instance {} with unknown template {}", saved.id, saved.templateId)
                pruned = true
                return@forEach
            }
            val record = saved.deepCopy()
            register(record)
            lifecycleRequests.addLast(InstanceLifecycleRequest.Create(record.id))
            restored += record
        }
        if (pruned) {
            InstanceSavedData.get(server).replaceAll(restored)
            savedDataDirty = false
        }
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        clearInstances()
        bootstrapBuiltinTemplates()
        restoreFromSavedData(event.server)
        logger.info("Runtime instance manager initialized")
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        flushLifecycle(event.server)
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        clearInstances()
    }

    @SubscribeEvent
    fun onLevelSave(event: LevelEvent.Save) {
        val serverLevel = event.level as? ServerLevel ?: return
        val record = instances.values.firstOrNull { it.levelKey == serverLevel.dimension() } ?: return
        captureRuntimeState(record, serverLevel)
        markSavedDataDirty()
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        val activeLevels = instances.values
            .filter { it.state == InstanceState.ACTIVE }
            .map { it.levelKey }
        RuntimeLevelKeySyncManager.syncRuntimeLevels(player, activeLevels)
    }

    private fun flushLifecycle(server: MinecraftServer) {
        var dirty = false

        while (lifecycleRequests.isNotEmpty()) {
            when (val request = lifecycleRequests.removeFirst()) {
                is InstanceLifecycleRequest.Create -> {
                    dirty = applyCreate(server, request.instanceId) || dirty
                }
                is InstanceLifecycleRequest.Destroy -> {
                    dirty = applyDestroy(server, request.instanceId) || dirty
                }
            }
        }

        dirty = reconcileLoadedLevels(server) || dirty

        if (dirty || savedDataDirty) {
            sync(server)
        }
    }

    private fun markSavedDataDirty() {
        savedDataDirty = true
    }

    private fun applyCreate(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        if (server.getLevel(record.levelKey) != null) {
            if (record.state == InstanceState.ACTIVE) {
                return false
            }

            record.state = InstanceState.ACTIVE
            record.updatedGameTime = currentGameTime(server)
            return true
        }

        val template = templates[record.templateId] ?: error("Unknown instance template: ${record.templateId}")
        val created = InstanceLevelFactory.create(server, template, record)
        (created.level.levelData as? InstanceLevelData)?.let { liveLevelData[record.id] = it }
        putRuntimeLevel(server, created.level)
        scheduleWarmup(record.id, created.level)
        RuntimeLevelKeySyncManager.announceRuntimeLevel(server, created.level.dimension())
        MinecraftForge.EVENT_BUS.post(LevelEvent.Load(created.level))
        record.state = InstanceState.ACTIVE
        record.updatedGameTime = currentGameTime(server)
        MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Activated(server, record.toHandle(), created.level))
        return true
    }

    private fun applyDestroy(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        val loadedLevel = server.getLevel(record.levelKey)
        val now = currentGameTime(server)

        if (loadedLevel == null) {
            liveLevelData.remove(instanceId)
            warmupChunks.remove(instanceId)
            warmupReadyGameTimes.remove(instanceId)
            closeGraceCounts.remove(instanceId)
            instances.remove(instanceId)
            markSavedDataDirty()
            return true
        }

        val residentPlayers = loadedLevel.players().filter { it.serverLevel().dimension() == loadedLevel.dimension() }
        if (residentPlayers.isNotEmpty()) {
            if (record.state != InstanceState.DRAINING) {
                record.state = InstanceState.DRAINING
                record.updatedGameTime = now
                markSavedDataDirty()
                return true
            }
            return false
        }

        if (record.state != InstanceState.UNLOADING && record.state != InstanceState.CLOSING) {
            record.state = InstanceState.UNLOADING
            record.updatedGameTime = now
            loadedLevel.noSave = false
            captureRuntimeState(record, loadedLevel)
            removeWarmupTicket(instanceId, loadedLevel)
            loadedLevel.chunkSource.removeTicketsOnClosing()
            closeGraceCounts.remove(instanceId)
            MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Unloading(server, record.toHandle(), loadedLevel))
            markSavedDataDirty()
            return true
        }

        loadedLevel.noSave = false

        if (record.state == InstanceState.UNLOADING) {
            val drainSnapshot = drainChunkWork(loadedLevel, closing = false)
            if (!isReadyForClose(instanceId, drainSnapshot)) {
                record.updatedGameTime = now
                return true
            }

            // Persist any chunk mutations once before the manual close path takes over.
            loadedLevel.save(null, true, false)
            record.state = InstanceState.CLOSING
            record.updatedGameTime = now
            closeGraceCounts.remove(instanceId)
            markSavedDataDirty()
            return true
        }

        val preCloseSnapshot = drainChunkWork(loadedLevel, closing = true)
        if (!isReadyForClose(instanceId, preCloseSnapshot)) {
            record.updatedGameTime = now
            return true
        }

        MinecraftForge.EVENT_BUS.post(LevelEvent.Unload(loadedLevel))
        closeLevelWithoutSave(loadedLevel)
        removeRuntimeLevel(server, loadedLevel)
        RuntimeLevelKeySyncManager.revokeRuntimeLevel(server, loadedLevel.dimension())
        liveLevelData.remove(instanceId)
        warmupChunks.remove(instanceId)
        warmupReadyGameTimes.remove(instanceId)
        closeGraceCounts.remove(instanceId)
        instances.remove(instanceId)
        MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Destroyed(server, record.toHandle()))
        logger.info("Destroyed instance {} using {} teardown profile", instanceId, C2meCompat.profileName())
        markSavedDataDirty()
        return true
    }

    private fun reconcileLoadedLevels(server: MinecraftServer): Boolean {
        val now = currentGameTime(server)
        var dirty = false
        var teardownBudget = C2meCompat.teardownProgressBudgetPerTick()

        instances.values.forEach { record ->
            val loadedLevel = server.getLevel(record.levelKey)
            if (loadedLevel != null) {
                pruneCompletedWarmup(record.id, loadedLevel)
            }
            if (
                loadedLevel != null &&
                teardownBudget > 0 &&
                (
                    record.state == InstanceState.DRAINING ||
                        record.state == InstanceState.UNLOADING ||
                        record.state == InstanceState.CLOSING
                    ) &&
                now - record.updatedGameTime >= C2meCompat.unloadDrainTicks()
            ) {
                lifecycleRequests.addLast(InstanceLifecycleRequest.Destroy(record.id))
                teardownBudget--
            }
            if (
                loadedLevel != null &&
                record.state != InstanceState.LOADING &&
                record.state != InstanceState.DRAINING &&
                record.state != InstanceState.UNLOADING &&
                record.state != InstanceState.CLOSING &&
                record.state != InstanceState.ACTIVE
            ) {
                record.state = InstanceState.ACTIVE
                record.updatedGameTime = now
                dirty = true
            }
        }

        return dirty
    }

    private fun sync(server: MinecraftServer) {
        InstanceSavedData.get(server).replaceAll(instances.values)
        savedDataDirty = false
    }

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun captureRuntimeState(record: InstanceRecord, level: ServerLevel) {
        val levelData = liveLevelData[record.id] ?: (level.levelData as? InstanceLevelData) ?: return
        record.levelState = levelData.snapshot(level.worldBorder.createSettings())
        record.updatedGameTime = currentGameTime(level.server)
    }

    @Suppress("DEPRECATION")
    private fun putRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        server.forgeGetWorldMap()[level.dimension()] = level
        server.markWorldsDirty()
    }

    @Suppress("DEPRECATION")
    private fun removeRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        server.forgeGetWorldMap().remove(level.dimension())
        server.markWorldsDirty()
    }

    private fun nextLevelKey(templateId: String): ResourceKey<Level> {
        return ResourceKey.create(Registries.DIMENSION, nextLevelLocation(templateId))
    }

    private fun nextLevelLocation(templateId: String): ResourceLocation {
        return ResourceLocation.fromNamespaceAndPath(
            MOD_ID,
            "instance/$templateId/${UUID.randomUUID().toString().replace("-", "")}"
        )
    }

    private fun scheduleWarmup(instanceId: UUID, level: ServerLevel) {
        val spawnChunk = ChunkPos(level.sharedSpawnPos)
        warmupChunks[instanceId] = spawnChunk
        warmupReadyGameTimes[instanceId] = currentGameTime(level.server) + C2meCompat.warmupTicketTicks()
        level.chunkSource.addRegionTicket(TicketType.START, spawnChunk, 1, Unit.INSTANCE)
    }

    private fun pruneCompletedWarmup(instanceId: UUID, level: ServerLevel) {
        val readyGameTime = warmupReadyGameTimes[instanceId] ?: return
        if (currentGameTime(level.server) < readyGameTime) {
            return
        }
        removeWarmupTicket(instanceId, level)
    }

    private fun removeWarmupTicket(instanceId: UUID, level: ServerLevel) {
        val spawnChunk = warmupChunks.remove(instanceId) ?: return
        warmupReadyGameTimes.remove(instanceId)
        level.chunkSource.removeRegionTicket(TicketType.START, spawnChunk, 1, Unit.INSTANCE)
    }

    private fun drainChunkWork(level: ServerLevel, closing: Boolean): ChunkDrainSnapshot {
        repeat(C2meCompat.chunkDrainIterations(closing)) {
            level.chunkSource.tick({ true }, false)
        }

        repeat(C2meCompat.chunkTaskPollLimit(closing)) {
            if (!level.chunkSource.pollTask()) {
                return@repeat
            }
        }

        val loadedChunks = level.chunkSource.gatherStats().toIntOrNull() ?: Int.MAX_VALUE
        val pendingTasks = level.chunkSource.getPendingTasksCount()
        val storagePendingWrites = if (loadedChunks == 0 && pendingTasks == 0) {
            accessPendingStorageWrites(accessChunkMap(level.chunkSource))
        } else {
            Int.MAX_VALUE
        }
        val chunkMapHasWork = if (loadedChunks == 0 && pendingTasks == 0) {
            accessChunkMap(level.chunkSource).hasWork()
        } else {
            true
        }
        return ChunkDrainSnapshot(
            loadedChunks = loadedChunks,
            pendingTasks = pendingTasks,
            chunkMapHasWork = chunkMapHasWork,
            storagePendingWrites = storagePendingWrites
        )
    }

    private fun closeLevelWithoutSave(level: ServerLevel) {
        level.chunkSource.lightEngine.close()
        accessChunkMap(level.chunkSource).close()
        accessEntityManager(level).close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun accessEntityManager(level: ServerLevel): net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity> {
        val field = findDeclaredField(
            ServerLevel::class.java,
            listOf("entityManager", "f_143244_"),
            net.minecraft.world.level.entity.PersistentEntitySectionManager::class.java
        )
        return field.get(level) as net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>
    }

    private fun accessChunkMap(chunkSource: ServerChunkCache): ChunkMap {
        val field = findDeclaredField(
            ServerChunkCache::class.java,
            listOf("chunkMap", "f_8325_"),
            ChunkMap::class.java
        )
        return field.get(chunkSource) as ChunkMap
    }

    private fun accessPendingStorageWrites(chunkMap: ChunkMap): Int {
        return runCatching {
            val workerField = findDeclaredField(
                chunkMap.javaClass.superclass,
                listOf("worker", "f_63498_"),
                IOWorker::class.java
            )
            val worker = workerField.get(chunkMap) as? IOWorker ?: return@runCatching 0
            val pendingWritesField = findDeclaredField(
                IOWorker::class.java,
                listOf("pendingWrites", "f_63531_"),
                Map::class.java
            )
            @Suppress("UNCHECKED_CAST")
            val pendingWrites = pendingWritesField.get(worker) as? Map<*, *> ?: return@runCatching 0
            pendingWrites.size
        }.getOrElse {
            if (C2meCompat.isLoaded()) 0 else Int.MAX_VALUE
        }
    }

    private fun findDeclaredField(owner: Class<*>, preferredNames: List<String>, expectedType: Class<*>): java.lang.reflect.Field {
        preferredNames.forEach { name ->
            runCatching {
                return owner.getDeclaredField(name).apply { isAccessible = true }
            }
        }

        owner.declaredFields.firstOrNull { expectedType.isAssignableFrom(it.type) }?.let { field ->
            field.isAccessible = true
            return field
        }

        error("Could not resolve field on ${owner.name} assignable to ${expectedType.name}")
    }

    private data class ChunkDrainSnapshot(
        val loadedChunks: Int,
        val pendingTasks: Int,
        val chunkMapHasWork: Boolean,
        val storagePendingWrites: Int
    ) {
        val hasHardPendingCloseWork: Boolean
            get() = loadedChunks > 0 || pendingTasks > 0 || storagePendingWrites > 0
    }

    private fun isReadyForClose(instanceId: UUID, snapshot: ChunkDrainSnapshot): Boolean {
        if (snapshot.hasHardPendingCloseWork) {
            closeGraceCounts.remove(instanceId)
            return false
        }
        if (!snapshot.chunkMapHasWork) {
            closeGraceCounts.remove(instanceId)
            return true
        }

        val graceCount = (closeGraceCounts[instanceId] ?: 0) + 1
        closeGraceCounts[instanceId] = graceCount
        return graceCount > C2meCompat.closeGracePasses()
    }
}
