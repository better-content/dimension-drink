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
    private val warmupChunks = linkedMapOf<UUID, Set<ChunkPos>>()
    private val warmupReadyGameTimes = linkedMapOf<UUID, Long>()
    private val closeGraceCounts = linkedMapOf<UUID, Int>()
    private val instanceTraceStartedAtNanos = linkedMapOf<UUID, Long>()
    private val warmupWaitLastLoggedAt = linkedMapOf<UUID, Long>()
    private val closeWaitLastLoggedAt = linkedMapOf<UUID, Long>()
    private var savedDataDirty = false

    override fun templates(): Collection<InstanceTemplate> {
        ensureTemplatesLoaded()
        return templates.values
    }

    override fun getTemplate(templateId: String): InstanceTemplate? {
        ensureTemplatesLoaded()
        return templates[templateId]
    }

    fun validateTemplateForRuntime(server: MinecraftServer, templateId: String): String? {
        ensureTemplatesLoaded()
        logger.info("Validating runtime template {}", templateId)
        val template = templates[templateId] ?: return "unknown runtime template '$templateId'"

        val stemLocation = ResourceLocation.tryParse(template.stem)
            ?: return "template '$templateId' has an invalid level stem '${template.stem}'"
        val stemKey = ResourceKey.create(Registries.LEVEL_STEM, stemLocation)
        val stemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
        if (!stemRegistry.containsKey(stemKey)) {
            return "level stem '$stemLocation' is unavailable"
        }
        logger.info("Validated runtime template {} stem={} requiredNamespace={}", templateId, stemLocation, template.requiredNamespace ?: "<none>")
        return null
    }

    override fun registerTemplate(template: InstanceTemplate) {
        templates[template.id] = template
        logger.info(
            "Registered runtime template {} stem={} requiredNamespace={} ephemeral={}",
            template.id,
            template.stem,
            template.requiredNamespace ?: "<none>",
            template.ephemeral
        )
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

    fun retargetTravelWarmup(instanceId: UUID, level: ServerLevel, center: net.minecraft.core.BlockPos) {
        val previousChunks = warmupChunks.remove(instanceId)
        warmupReadyGameTimes.remove(instanceId)
        warmupWaitLastLoggedAt.remove(instanceId)
        previousChunks?.forEach { chunk ->
            level.chunkSource.removeRegionTicket(TicketType.START, chunk, 1, Unit.INSTANCE)
        }

        val targetChunks = warmupChunksFor(center)
        warmupChunks[instanceId] = targetChunks
        warmupReadyGameTimes[instanceId] = currentGameTime(level.server) + C2meCompat.warmupTicketTicks()
        targetChunks.forEach { chunk ->
            level.chunkSource.addRegionTicket(TicketType.START, chunk, 1, Unit.INSTANCE)
        }
        traceInstance(
            instances[instanceId],
            "warmup-retargeted",
            "center=$center previousChunks=${previousChunks?.size ?: 0} targetChunks=${targetChunks.size} readyAt=${warmupReadyGameTimes[instanceId]}"
        )
    }

    override fun createInstance(server: MinecraftServer, templateId: String, ownerId: UUID?): InstanceHandle {
        ensureTemplatesLoaded()
        logger.info("Received createInstance request template={} owner={}", templateId, ownerId ?: "<none>")
        val validationError = validateTemplateForRuntime(server, templateId)
        check(validationError == null) { validationError ?: "runtime template validation failed for '$templateId'" }
        val template = templates[templateId] ?: error("Unknown instance template: $templateId")
        val instanceId = UUID.randomUUID()
        val levelLocation = nextLevelLocation(template.id)
        val record = InstanceRecord(
            id = instanceId,
            templateId = template.id,
            levelKey = ResourceKey.create(Registries.DIMENSION, levelLocation),
            state = InstanceState.ALLOCATED,
            ownerId = ownerId,
            instanceSeed = nextInstanceSeed(instanceId),
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server),
            levelState = InstanceLevelState.createDefault(server, template.id, levelLocation)
        )
        instanceTraceStartedAtNanos[instanceId] = System.nanoTime()
        register(record)
        lifecycleRequests.addLast(InstanceLifecycleRequest.Create(record.id))
        traceInstance(record, "allocated", "queueDepth=${lifecycleRequests.size} levelLocation=$levelLocation seed=${record.instanceSeed}")
        markSavedDataDirty()
        return record.toHandle()
    }

    override fun scheduleDestroy(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        if (record.state == InstanceState.DESTROYED) {
            traceInstance(record, "destroy-ignored", "reason=already-destroyed")
            return false
        }

        val previousState = record.state
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
        traceInstance(record, "destroy-requested", "fromState=$previousState toState=${record.state} queueDepth=${lifecycleRequests.size}")
        markSavedDataDirty()
        return true
    }

    fun snapshotInstance(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        val loadedLevel = server.getLevel(record.levelKey) ?: return false
        captureRuntimeState(record, loadedLevel)
        traceInstance(record, "snapshot-captured", "borderSize=${record.levelState.worldBorderSettings().size}")
        sync(server)
        return true
    }

    fun register(record: InstanceRecord) {
        instances[record.id] = record
        instanceTraceStartedAtNanos.putIfAbsent(record.id, System.nanoTime())
        traceInstance(record, "registered", "instanceCount=${instances.size}")
    }

    fun clearInstances() {
        logger.info(
            "Clearing runtime instance manager instances={} lifecycleRequests={} liveLevelData={} warmups={} closeGraceCounts={}",
            instances.size,
            lifecycleRequests.size,
            liveLevelData.size,
            warmupChunks.size,
            closeGraceCounts.size
        )
        instances.clear()
        lifecycleRequests.clear()
        liveLevelData.clear()
        warmupChunks.clear()
        warmupReadyGameTimes.clear()
        closeGraceCounts.clear()
        instanceTraceStartedAtNanos.clear()
        warmupWaitLastLoggedAt.clear()
        closeWaitLastLoggedAt.clear()
        RuntimeServerLevel.clearSeeds()
        savedDataDirty = false
    }

    @Suppress("DEPRECATION")
    fun hasRuntimeWorldSupport(server: MinecraftServer): Boolean {
        return server.forgeGetWorldMap().isNotEmpty()
    }

    fun reloadTemplates() {
        InstanceTemplateDataManager.reload()
        templates.clear()
        InstanceTemplateDataManager.allTemplates().forEach(::registerTemplate)
        logger.info("Registered {} runtime instance templates after reload", templates.size)
    }

    fun allocatePlaceholder(templateId: String, levelKey: ResourceKey<Level>, ownerId: UUID? = null): InstanceHandle {
        ensureTemplatesLoaded()
        val instanceId = UUID.randomUUID()
        val record = InstanceRecord(
            id = instanceId,
            templateId = templateId,
            levelKey = levelKey,
            state = InstanceState.ALLOCATED,
            ownerId = ownerId,
            instanceSeed = nextInstanceSeed(instanceId),
            levelState = InstanceLevelState.createDefaultPlaceholder(levelKey.location())
        )
        register(record)
        return record.toHandle()
    }

    fun records(): Collection<InstanceRecord> = instances.values.map { it.deepCopy() }

    fun restoreFromSavedData(server: MinecraftServer) {
        ensureTemplatesLoaded()
        logger.info("Restoring runtime instances from saved data")
        val restored = mutableListOf<InstanceRecord>()
        var pruned = false
        InstanceSavedData.get(server).snapshot().forEach { saved ->
            if (saved.state == InstanceState.DESTROYED) {
                logger.info("Skipping destroyed saved runtime instance {}", saved.id)
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
            traceInstance(record, "restored", "queueDepth=${lifecycleRequests.size}")
            restored += record
        }
        if (pruned) {
            InstanceSavedData.get(server).replaceAll(restored)
            savedDataDirty = false
        }
        logger.info("Restore complete restored={} pruned={} queueDepth={}", restored.size, pruned, lifecycleRequests.size)
    }

    @SubscribeEvent
    fun onServerStarted(event: ServerStartedEvent) {
        logger.info("Server started; initializing runtime instance manager")
        clearInstances()
        reloadTemplates()
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
        logger.info("Server stopped; resetting runtime instance manager")
        clearInstances()
        RuntimeLevelKeySyncManager.reset()
    }

    @SubscribeEvent
    fun onLevelSave(event: LevelEvent.Save) {
        val serverLevel = event.level as? ServerLevel ?: return
        val record = instances.values.firstOrNull { it.levelKey == serverLevel.dimension() } ?: return
        captureRuntimeState(record, serverLevel)
        traceInstance(record, "level-saved", "loadedChunks=${serverLevel.chunkSource.gatherStats()}")
        markSavedDataDirty()
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        val activeLevels = instances.values
            .filter { it.state == InstanceState.ACTIVE }
            .map { it.levelKey }
        logger.info(
            "Player {} logged in; syncing {} active runtime levels {}",
            player.scoreboardName,
            activeLevels.size,
            activeLevels.map { it.location().toString() }
        )
        RuntimeLevelKeySyncManager.syncRuntimeLevels(player, activeLevels)
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return
        logger.info("Player {} logged out; forgetting runtime level sync state", player.scoreboardName)
        RuntimeLevelKeySyncManager.forgetPlayer(player.uuid)
    }

    private fun flushLifecycle(server: MinecraftServer) {
        val initialQueueDepth = lifecycleRequests.size
        val dirtyBefore = savedDataDirty
        if (initialQueueDepth > 0 || dirtyBefore) {
            logger.info(
                "Lifecycle flush start queueDepth={} instanceCount={} savedDataDirty={}",
                initialQueueDepth,
                instances.size,
                dirtyBefore
            )
        }
        var dirty = false

        while (lifecycleRequests.isNotEmpty()) {
            when (val request = lifecycleRequests.removeFirst()) {
                is InstanceLifecycleRequest.Create -> {
                    logger.info(
                        "Lifecycle dequeue create instance={} remainingQueueDepth={}",
                        request.instanceId,
                        lifecycleRequests.size
                    )
                    dirty = applyCreate(server, request.instanceId) || dirty
                }
                is InstanceLifecycleRequest.Destroy -> {
                    logger.info(
                        "Lifecycle dequeue destroy instance={} remainingQueueDepth={}",
                        request.instanceId,
                        lifecycleRequests.size
                    )
                    dirty = applyDestroy(server, request.instanceId) || dirty
                }
            }
        }

        dirty = reconcileLoadedLevels(server) || dirty

        if (dirty || savedDataDirty) {
            sync(server)
        }
        if (initialQueueDepth > 0 || dirty || dirtyBefore || savedDataDirty) {
            logger.info(
                "Lifecycle flush end dirty={} remainingQueueDepth={} instanceCount={} savedDataDirty={}",
                dirty,
                lifecycleRequests.size,
                instances.size,
                savedDataDirty
            )
        }
    }

    private fun markSavedDataDirty() {
        logger.info("Marked runtime instance saved data dirty")
        savedDataDirty = true
    }

    private fun ensureTemplatesLoaded() {
        if (templates.isEmpty()) {
            reloadTemplates()
        }
    }

    private fun applyCreate(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        traceInstance(record, "create-begin", "worldPresent=${server.getLevel(record.levelKey) != null}")
        if (server.getLevel(record.levelKey) != null) {
            if (record.state == InstanceState.ACTIVE) {
                traceInstance(record, "create-skip", "reason=already-active")
                return false
            }

            record.state = InstanceState.ACTIVE
            record.updatedGameTime = currentGameTime(server)
            traceInstance(record, "create-promote-existing-level", "newState=${record.state}")
            return true
        }

        val validationError = validateTemplateForRuntime(server, record.templateId)
        if (validationError != null) {
            logger.warn("Discarding runtime instance {} for template {}: {}", record.id, record.templateId, validationError)
            instances.remove(instanceId)
            markSavedDataDirty()
            return true
        }

        val template = templates[record.templateId] ?: error("Unknown instance template: ${record.templateId}")
        traceInstance(record, "create-construct-level", "templateStem=${template.stem}")
        val created = runCatching { InstanceLevelFactory.create(server, template, record) }
            .getOrElse { throwable ->
                logger.warn("Failed to construct runtime instance {} for template {}", record.id, record.templateId, throwable)
                RuntimeServerLevel.forgetSeed(record.levelKey)
                liveLevelData.remove(instanceId)
                warmupChunks.remove(instanceId)
                warmupReadyGameTimes.remove(instanceId)
                closeGraceCounts.remove(instanceId)
                warmupWaitLastLoggedAt.remove(instanceId)
                closeWaitLastLoggedAt.remove(instanceId)
                instances.remove(instanceId)
                markSavedDataDirty()
                return true
            }
        (created.level.levelData as? InstanceLevelData)?.let { liveLevelData[record.id] = it }
        traceInstance(record, "create-level-constructed", "spawn=${created.level.sharedSpawnPos}")
        putRuntimeLevel(server, created.level)
        scheduleWarmup(record.id, created.level)
        RuntimeLevelKeySyncManager.announceRuntimeLevel(server, created.level.dimension())
        traceInstance(record, "create-level-announced", "players=${server.playerList.players.size}")
        MinecraftForge.EVENT_BUS.post(LevelEvent.Load(created.level))
        record.state = InstanceState.ACTIVE
        record.updatedGameTime = currentGameTime(server)
        MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Activated(server, record.toHandle(), created.level))
        traceInstance(record, "create-complete", "state=${record.state}")
        return true
    }

    private fun applyDestroy(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        val loadedLevel = server.getLevel(record.levelKey)
        val now = currentGameTime(server)
        traceInstance(record, "destroy-begin", "loadedLevel=${loadedLevel != null} state=${record.state}")

        if (loadedLevel == null) {
            RuntimeServerLevel.forgetSeed(record.levelKey)
            liveLevelData.remove(instanceId)
            warmupChunks.remove(instanceId)
            warmupReadyGameTimes.remove(instanceId)
            closeGraceCounts.remove(instanceId)
            warmupWaitLastLoggedAt.remove(instanceId)
            closeWaitLastLoggedAt.remove(instanceId)
            instances.remove(instanceId)
            traceInstance(record, "destroy-pruned-unloaded", "reason=level-missing")
            instanceTraceStartedAtNanos.remove(instanceId)
            markSavedDataDirty()
            return true
        }

        val residentPlayers = loadedLevel.players().filter { it.serverLevel().dimension() == loadedLevel.dimension() }
        if (residentPlayers.isNotEmpty()) {
            if (record.state != InstanceState.DRAINING) {
                record.state = InstanceState.DRAINING
                record.updatedGameTime = now
                traceInstance(record, "destroy-waiting-for-players", "residentPlayers=${residentPlayers.map { it.scoreboardName }}")
                markSavedDataDirty()
                return true
            }
            maybeLogDestroyWait(server, record, "players-still-present", "residentPlayers=${residentPlayers.map { it.scoreboardName }}")
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
            closeWaitLastLoggedAt.remove(instanceId)
            MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Unloading(server, record.toHandle(), loadedLevel))
            traceInstance(record, "destroy-transition-unloading", "level=${loadedLevel.dimension().location()}")
            markSavedDataDirty()
            return true
        }

        loadedLevel.noSave = false

        if (record.state == InstanceState.UNLOADING) {
            val drainSnapshot = drainChunkWork(loadedLevel, closing = false)
            if (!isReadyForClose(instanceId, drainSnapshot)) {
                record.updatedGameTime = now
                maybeLogDestroyWait(server, record, "unloading-drain", formatDrainSnapshot(drainSnapshot))
                return true
            }

            // Persist any chunk mutations once before the manual close path takes over.
            loadedLevel.save(null, true, false)
            record.state = InstanceState.CLOSING
            record.updatedGameTime = now
            closeGraceCounts.remove(instanceId)
            closeWaitLastLoggedAt.remove(instanceId)
            traceInstance(record, "destroy-transition-closing", formatDrainSnapshot(drainSnapshot))
            markSavedDataDirty()
            return true
        }

        val preCloseSnapshot = drainChunkWork(loadedLevel, closing = true)
        if (!isReadyForClose(instanceId, preCloseSnapshot)) {
            record.updatedGameTime = now
            maybeLogDestroyWait(server, record, "closing-drain", formatDrainSnapshot(preCloseSnapshot))
            return true
        }

        traceInstance(record, "destroy-close-level", formatDrainSnapshot(preCloseSnapshot))
        MinecraftForge.EVENT_BUS.post(LevelEvent.Unload(loadedLevel))
        closeLevelWithoutSave(loadedLevel)
        removeRuntimeLevel(server, loadedLevel)
        RuntimeLevelKeySyncManager.revokeRuntimeLevel(server, loadedLevel.dimension())
        RuntimeServerLevel.forgetSeed(record.levelKey)
        liveLevelData.remove(instanceId)
        warmupChunks.remove(instanceId)
        warmupReadyGameTimes.remove(instanceId)
        closeGraceCounts.remove(instanceId)
        warmupWaitLastLoggedAt.remove(instanceId)
        closeWaitLastLoggedAt.remove(instanceId)
        instances.remove(instanceId)
        MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Destroyed(server, record.toHandle()))
        logger.info("Destroyed instance {} using {} teardown profile", instanceId, C2meCompat.profileName())
        instanceTraceStartedAtNanos.remove(instanceId)
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
                traceInstance(record, "reconcile-enqueue-destroy", "queueDepth=${lifecycleRequests.size} teardownBudgetBefore=$teardownBudget")
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
                traceInstance(record, "reconcile-promote-active", "loadedLevelPresent=true")
                dirty = true
            }
        }

        return dirty
    }

    private fun sync(server: MinecraftServer) {
        logger.info("Persisting {} runtime instances to saved data", instances.size)
        InstanceSavedData.get(server).replaceAll(instances.values)
        savedDataDirty = false
    }

    private fun currentGameTime(server: MinecraftServer): Long = server.overworld().gameTime

    private fun nextInstanceSeed(instanceId: UUID): Long {
        return instanceId.mostSignificantBits xor java.lang.Long.rotateLeft(instanceId.leastSignificantBits, 1)
    }

    private fun captureRuntimeState(record: InstanceRecord, level: ServerLevel) {
        val levelData = liveLevelData[record.id] ?: (level.levelData as? InstanceLevelData) ?: return
        record.levelState = levelData.snapshot(level.worldBorder.createSettings())
        record.updatedGameTime = currentGameTime(level.server)
        traceInstance(record, "state-captured", "borderSize=${record.levelState.worldBorderSettings().size}")
    }

    @Suppress("DEPRECATION")
    private fun putRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        server.forgeGetWorldMap()[level.dimension()] = level
        server.markWorldsDirty()
        logger.info(
            "Inserted runtime level {} into forge world map size={}",
            level.dimension().location(),
            server.forgeGetWorldMap().size
        )
    }

    @Suppress("DEPRECATION")
    private fun removeRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        server.forgeGetWorldMap().remove(level.dimension())
        server.markWorldsDirty()
        logger.info(
            "Removed runtime level {} from forge world map size={}",
            level.dimension().location(),
            server.forgeGetWorldMap().size
        )
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
        traceInstance(instances[instanceId], "warmup-schedule", "spawn=${level.sharedSpawnPos}")
        retargetTravelWarmup(instanceId, level, level.sharedSpawnPos)
    }

    private fun pruneCompletedWarmup(instanceId: UUID, level: ServerLevel) {
        val readyGameTime = warmupReadyGameTimes[instanceId] ?: return
        val now = currentGameTime(level.server)
        if (now < readyGameTime) {
            return
        }
        val chunks = warmupChunks[instanceId] ?: return
        val missingChunks = chunks.count { level.chunkSource.getChunkNow(it.x, it.z) == null }
        if (missingChunks > 0) {
            val lastLoggedAt = warmupWaitLastLoggedAt[instanceId]
            if (lastLoggedAt == null || now - lastLoggedAt >= 20L) {
                traceInstance(
                    instances[instanceId],
                    "warmup-waiting",
                    "gameTime=$now readyAt=$readyGameTime missingChunks=$missingChunks totalChunks=${chunks.size}"
                )
                warmupWaitLastLoggedAt[instanceId] = now
            }
            return
        }
        removeWarmupTicket(instanceId, level)
    }

    private fun removeWarmupTicket(instanceId: UUID, level: ServerLevel) {
        val chunks = warmupChunks.remove(instanceId) ?: return
        warmupReadyGameTimes.remove(instanceId)
        warmupWaitLastLoggedAt.remove(instanceId)
        chunks.forEach { chunk ->
            level.chunkSource.removeRegionTicket(TicketType.START, chunk, 1, Unit.INSTANCE)
        }
        traceInstance(instances[instanceId], "warmup-complete", "releasedChunks=${chunks.size}")
    }

    private fun warmupChunksFor(center: net.minecraft.core.BlockPos): Set<ChunkPos> {
        val chunks = linkedSetOf<ChunkPos>()
        val platformRadius = 3
        for (radius in 0..32 step 8) {
            for (angle in 0 until 360 step 45) {
                val radians = Math.toRadians(angle.toDouble())
                val x = center.x + (radius * kotlin.math.cos(radians)).toInt()
                val z = center.z + (radius * kotlin.math.sin(radians)).toInt()
                val minChunkX = (x - platformRadius) shr 4
                val maxChunkX = (x + platformRadius) shr 4
                val minChunkZ = (z - platformRadius) shr 4
                val maxChunkZ = (z + platformRadius) shr 4
                for (chunkX in minChunkX..maxChunkX) {
                    for (chunkZ in minChunkZ..maxChunkZ) {
                        chunks += ChunkPos(chunkX, chunkZ)
                    }
                }
            }
        }
        return chunks
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
        logger.info("Closing runtime level without save level={}", level.dimension().location())
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
            get() = loadedChunks > 0 || pendingTasks > 0
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

    private fun traceInstance(record: InstanceRecord?, action: String, details: String) {
        if (record == null) {
            logger.info("Runtime instance trace action={} details={}", action, details)
            return
        }
        val startedAt = instanceTraceStartedAtNanos.getOrPut(record.id) { System.nanoTime() }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        logger.info(
            "Runtime instance trace id={} template={} state={} level={} owner={} elapsed={}ms action={} details={}",
            record.id,
            record.templateId,
            record.state,
            record.levelKey.location(),
            record.ownerId ?: "<none>",
            elapsedMs,
            action,
            details
        )
    }

    private fun maybeLogDestroyWait(server: MinecraftServer, record: InstanceRecord, phase: String, details: String) {
        val now = currentGameTime(server)
        val lastLoggedAt = closeWaitLastLoggedAt[record.id]
        if (lastLoggedAt == null || now - lastLoggedAt >= 20L) {
            traceInstance(record, "destroy-wait-$phase", "gameTime=$now $details closeGrace=${closeGraceCounts[record.id] ?: 0}")
            closeWaitLastLoggedAt[record.id] = now
        }
    }

    private fun formatDrainSnapshot(snapshot: ChunkDrainSnapshot): String {
        return "loadedChunks=${snapshot.loadedChunks} pendingTasks=${snapshot.pendingTasks} chunkMapHasWork=${snapshot.chunkMapHasWork} storagePendingWrites=${snapshot.storagePendingWrites}"
    }
}
