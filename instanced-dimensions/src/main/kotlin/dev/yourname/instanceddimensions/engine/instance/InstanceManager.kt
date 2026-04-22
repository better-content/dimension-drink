package dev.yourname.instanceddimensions.engine.instance

import com.mojang.datafixers.util.Either
import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.MOD_ID
import dev.yourname.instanceddimensions.api.InstanceCreateResult
import dev.yourname.instanceddimensions.api.InstanceService
import dev.yourname.instanceddimensions.compat.C2meCompat
import dev.yourname.instanceddimensions.compat.DistantHorizonsCompat
import dev.yourname.instanceddimensions.engine.levelsync.RuntimeLevelKeySyncManager
import dev.yourname.instanceddimensions.engine.travel.RuntimePlayerChunkWindowProfile
import dev.yourname.instanceddimensions.events.RuntimeInstanceEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.level.chunk.storage.IOWorker
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.ArrayDeque
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Rewrite scaffold for runtime-created `ServerLevel` instances.
 *
 * This remains intentionally small until the engine patch layer is introduced.
 */
object InstanceManager : InstanceService {

    private val logger = LogUtils.getLogger()
    private val suppressedTraceActions = setOf(
        "hidden-level-progress",
        "arrival-preparation-waiting",
        "platform-bootstrap-waiting",
        "destroy-begin",
        "reconcile-enqueue-destroy"
    )
    private val runtimeArrivalTicketType = TicketType.create("instanceddimensions_runtime_arrival", Comparator.naturalOrder<UUID>())
    private val runtimePlatformBootstrapTicketType = TicketType.create("instanceddimensions_runtime_platform_bootstrap", Comparator.naturalOrder<UUID>())
    private const val ARRIVAL_PREPARATION_TIMEOUT_TICKS = 20L * 60L
    private const val PLATFORM_BOOTSTRAP_TIMEOUT_TICKS = 20L * 20L
    private const val PRECISE_CHUNK_TICKET_DISTANCE = 1
    private val templates = linkedMapOf<String, InstanceTemplate>()
    private val instances = linkedMapOf<UUID, InstanceRecord>()
    private val lifecycleRequests = ArrayDeque<InstanceLifecycleRequest>()
    private val loadedRuntimeLevels = linkedMapOf<ResourceKey<Level>, ServerLevel>()
    private val exposedRuntimeLevels = linkedSetOf<ResourceKey<Level>>()
    private val liveLevelData = linkedMapOf<UUID, InstanceLevelData>()
    private val exceptionalChunkFutureFailures = ConcurrentHashMap<CompletableFuture<*>, String>()
    private val arrivalPreparations = linkedMapOf<UUID, ArrivalPreparation>()
    private val arrivalStatuses = linkedMapOf<UUID, InstanceArrivalStatus>()
    private val platformBootstrapPreparations = linkedMapOf<UUID, PlatformBootstrapPreparation>()
    private val platformBootstrapStatuses = linkedMapOf<UUID, InstancePlatformBootstrapStatus>()
    private val deferredDefaultArrivalPreparation = linkedSetOf<UUID>()
    private val preserveAfterUnload = linkedSetOf<UUID>()
    private val closeGraceCounts = linkedMapOf<UUID, Int>()
    private val closeWaitLastLoggedAt = linkedMapOf<UUID, Long>()
    private val teardownRetryAfterGameTime = linkedMapOf<UUID, Long>()
    private val instanceTraceStartedAtNanos = linkedMapOf<UUID, Long>()
    private val serverLevelMapField: Field by lazy(LazyThreadSafetyMode.NONE) {
        findDeclaredField(
            MinecraftServer::class.java,
            listOf("levels", "f_129762_"),
            Map::class.java
        )
    }
    private val nonBlockingChunkFutureMethod: Method by lazy(LazyThreadSafetyMode.NONE) {
        ServerChunkCache::class.java.declaredMethods
            .firstOrNull { method ->
                Modifier.isPrivate(method.modifiers) &&
                method.returnType == CompletableFuture::class.java &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType!!,
                            Int::class.javaPrimitiveType!!,
                            ChunkStatus::class.java,
                            Boolean::class.javaPrimitiveType!!
                        )
                    )
            }
            ?.apply {
                isAccessible = true
                logger.info("Resolved non-blocking ServerChunkCache chunk future method {}", name)
            }
            ?: error("Could not resolve ServerChunkCache non-blocking chunk future method")
    }
    private var shuttingDown = false
    private var savedDataDirty = false

    private data class ArrivalPreparation(
        val center: BlockPos,
        val centerChunk: ChunkPos,
        val coveredChunks: Set<ChunkPos>,
        val requestedGameTime: Long,
        val chunkFutures: Map<ChunkPos, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>>,
        var lastProgressLogGameTime: Long = Long.MIN_VALUE
    )

    private class HiddenResolvableLevelMap(
        private val visibleLevels: MutableMap<ResourceKey<Level>, ServerLevel>
    ) : MutableMap<ResourceKey<Level>, ServerLevel> {

        private val hiddenLevels = linkedMapOf<ResourceKey<Level>, ServerLevel>()

        fun putHidden(levelKey: ResourceKey<Level>, level: ServerLevel): ServerLevel? {
            return hiddenLevels.put(levelKey, level)
        }

        fun removeHidden(levelKey: ResourceKey<Level>): ServerLevel? {
            return hiddenLevels.remove(levelKey)
        }

        fun expose(levelKey: ResourceKey<Level>, level: ServerLevel): ServerLevel? {
            hiddenLevels.remove(levelKey)
            return visibleLevels.put(levelKey, level)
        }

        fun hiddenSize(): Int = hiddenLevels.size

        fun isVisible(levelKey: ResourceKey<Level>): Boolean = visibleLevels.containsKey(levelKey)

        fun clearHidden() {
            hiddenLevels.clear()
        }

        override val size: Int
            get() = visibleLevels.size

        override fun containsKey(key: ResourceKey<Level>): Boolean {
            return hiddenLevels.containsKey(key) || visibleLevels.containsKey(key)
        }

        override fun containsValue(value: ServerLevel): Boolean {
            return hiddenLevels.containsValue(value) || visibleLevels.containsValue(value)
        }

        override fun get(key: ResourceKey<Level>): ServerLevel? {
            return hiddenLevels[key] ?: visibleLevels[key]
        }

        override fun isEmpty(): Boolean = visibleLevels.isEmpty()

        override val entries: MutableSet<MutableMap.MutableEntry<ResourceKey<Level>, ServerLevel>>
            get() = visibleLevels.entries

        override val keys: MutableSet<ResourceKey<Level>>
            get() = visibleLevels.keys

        override val values: MutableCollection<ServerLevel>
            get() = visibleLevels.values

        override fun clear() {
            visibleLevels.clear()
        }

        override fun put(key: ResourceKey<Level>, value: ServerLevel): ServerLevel? {
            hiddenLevels.remove(key)
            return visibleLevels.put(key, value)
        }

        override fun putAll(from: Map<out ResourceKey<Level>, ServerLevel>) {
            from.forEach { (key, value) -> put(key, value) }
        }

        override fun remove(key: ResourceKey<Level>): ServerLevel? {
            return visibleLevels.remove(key)
        }
    }

    private data class PlatformBootstrapPreparation(
        val center: BlockPos,
        val targetChunk: ChunkPos,
        val requestedGameTime: Long,
        val chunkFuture: CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>,
        var lastProgressLogGameTime: Long = Long.MIN_VALUE
    )

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

    fun assignOwner(server: MinecraftServer, id: UUID, ownerId: UUID?): Boolean {
        val record = instances[id] ?: return false
        if (record.ownerId == ownerId) {
            traceInstance(record, "owner-unchanged", "owner=${ownerId ?: "<none>"}")
            return true
        }

        val previousOwner = record.ownerId
        record.ownerId = ownerId
        record.updatedGameTime = currentGameTime(server)
        traceInstance(record, "owner-updated", "from=${previousOwner ?: "<none>"} to=${ownerId ?: "<none>"}")
        markSavedDataDirty()
        return true
    }

    fun preparedSpawnPos(id: UUID): BlockPos? = instances[id]?.preparedSpawnPos

    fun isLevelLoaded(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        return loadedLevel(server, record.levelKey) != null
    }

    fun loadedLevel(server: MinecraftServer, id: UUID): ServerLevel? {
        val record = instances[id] ?: return null
        return loadedLevel(server, record.levelKey)
    }

    fun loadedLevel(server: MinecraftServer, levelKey: ResourceKey<Level>): ServerLevel? {
        return loadedRuntimeLevels[levelKey] ?: server.getLevel(levelKey)
    }

    fun createPreparedInstance(server: MinecraftServer, templateId: String): InstanceCreateResult {
        ensureTemplatesLoaded()
        logger.info("Received createPreparedInstance request template={}", templateId)
        val validationError = validateTemplateForRuntime(server, templateId)
        if (validationError != null) {
            logger.warn("Rejecting createPreparedInstance request template={} reason={}", templateId, validationError)
            return InstanceCreateResult.Rejected(validationError)
        }
        val template = templates[templateId]
            ?: return InstanceCreateResult.Rejected("unknown runtime template '$templateId'")
        val instanceId = UUID.randomUUID()
        val levelLocation = nextLevelLocation(template.id)
        val record = InstanceRecord(
            id = instanceId,
            templateId = template.id,
            levelKey = ResourceKey.create(Registries.DIMENSION, levelLocation),
            state = InstanceState.PREPARING,
            ownerId = null,
            preparedSpawnPos = null,
            instanceSeed = nextInstanceSeed(instanceId),
            createdGameTime = currentGameTime(server),
            updatedGameTime = currentGameTime(server),
            levelState = InstanceLevelState.createDefault(server, template.id, levelLocation)
        )
        instanceTraceStartedAtNanos[instanceId] = System.nanoTime()
        register(record)
        enqueueLifecycleRequest(InstanceLifecycleRequest.Create(record.id))
        traceInstance(record, "prepared-allocated", "queueDepth=${lifecycleRequests.size} levelLocation=$levelLocation seed=${record.instanceSeed}")
        markSavedDataDirty()
        return InstanceCreateResult.Accepted(record.toHandle())
    }

    fun activatePreparedInstance(server: MinecraftServer, id: UUID, ownerId: UUID): String? {
        val record = instances[id] ?: return "unknown runtime instance"
        if (record.state != InstanceState.PREPARED) {
            return "runtime instance ${record.id} is not cold-ready: ${record.state}"
        }
        if (record.preparedSpawnPos == null) {
            return "runtime instance ${record.id} has no prepared spawn position"
        }
        record.ownerId = ownerId
        record.state = InstanceState.LOADING
        record.updatedGameTime = currentGameTime(server)
        enqueueLifecycleRequest(InstanceLifecycleRequest.Create(id))
        traceInstance(record, "prepared-activation-requested", "owner=$ownerId queueDepth=${lifecycleRequests.size}")
        markSavedDataDirty()
        return null
    }

    fun suspendPreparedInstance(server: MinecraftServer, id: UUID, spawnPos: BlockPos): String? {
        val record = instances[id] ?: return "unknown runtime instance"
        if (record.ownerId != null) {
            return "runtime instance ${record.id} is owned by ${record.ownerId}"
        }
        if (record.state != InstanceState.PREPARING && record.state != InstanceState.ACTIVE) {
            return "runtime instance ${record.id} is not suspendable from ${record.state}"
        }
        record.preparedSpawnPos = spawnPos.immutable()
        record.updatedGameTime = currentGameTime(server)
        preserveAfterUnload += id
        enqueueLifecycleRequest(InstanceLifecycleRequest.Suspend(id))
        traceInstance(record, "prepared-suspend-requested", "spawn=$spawnPos queueDepth=${lifecycleRequests.size}")
        markSavedDataDirty()
        return null
    }

    fun describeCloseState(server: MinecraftServer, id: UUID): String {
        val record = instances[id] ?: return "missing"
        val level = loadedLevel(server, record.levelKey) ?: return "unloaded"
        val snapshot = drainChunkWork(level, closing = record.state == InstanceState.CLOSING)
        return "state=${record.state} loadedChunks=${snapshot.loadedChunks} pendingTasks=${snapshot.pendingTasks} chunkMapHasWork=${snapshot.chunkMapHasWork} storagePendingWrites=${snapshot.storagePendingWrites}"
    }

    override fun isRuntimeLevel(levelKey: ResourceKey<Level>): Boolean = getInstance(levelKey) != null

    fun isTravelReady(id: UUID): Boolean {
        return arrivalStatuses[id]?.phase == InstanceArrivalPhase.READY
    }

    fun isTravelReadyForLevel(levelKey: ResourceKey<Level>): Boolean {
        val record = instances.values.firstOrNull { it.levelKey == levelKey } ?: return true
        return isTravelReady(record.id)
    }

    fun arrivalStatus(id: UUID): InstanceArrivalStatus {
        return arrivalStatuses[id] ?: InstanceArrivalStatus(phase = InstanceArrivalPhase.IDLE)
    }

    fun platformBootstrapStatus(id: UUID): InstancePlatformBootstrapStatus {
        return platformBootstrapStatuses[id] ?: InstancePlatformBootstrapStatus(phase = InstancePlatformBootstrapPhase.IDLE)
    }

    fun prepareArrivalRegion(instanceId: UUID, level: ServerLevel, center: BlockPos): String? {
        val record = instances[instanceId] ?: return "unknown runtime instance"
        if (record.levelKey != level.dimension()) {
            return "runtime level mismatch for instance ${record.id}"
        }
        deferredDefaultArrivalPreparation.remove(instanceId)

        val previousPreparation = arrivalPreparations.remove(instanceId)
        previousPreparation?.coveredChunks?.forEach { chunk ->
            level.chunkSource.removeRegionTicket(
                runtimeArrivalTicketType,
                chunk,
                PRECISE_CHUNK_TICKET_DISTANCE,
                instanceId
            )
        }

        val centerChunk = ChunkPos(center)
        val targetChunks = RuntimePlayerChunkWindowProfile.coveredChunks(centerChunk)
        val chunkFutures = targetChunks.associateWith { chunk ->
            stabilizeChunkFuture(
                record = record,
                level = level,
                chunk = chunk,
                status = ChunkStatus.FULL,
                purpose = "arrival-preparation",
                future = requestChunkFutureNonBlocking(level, chunk, ChunkStatus.FULL, load = true)
            )
        }
        val preparation = ArrivalPreparation(
            center = center,
            centerChunk = centerChunk,
            coveredChunks = targetChunks,
            requestedGameTime = currentGameTime(level.server),
            chunkFutures = chunkFutures
        )
        arrivalPreparations[instanceId] = preparation
        arrivalStatuses[instanceId] = InstanceArrivalStatus(
            phase = InstanceArrivalPhase.PREPARING,
            center = center,
            totalChunks = targetChunks.size,
            completedChunks = 0,
            requestedGameTime = preparation.requestedGameTime
        )
        targetChunks.forEach { chunk ->
            level.chunkSource.addRegionTicket(
                runtimeArrivalTicketType,
                chunk,
                PRECISE_CHUNK_TICKET_DISTANCE,
                instanceId
            )
        }
        traceInstance(
            record,
            "arrival-preparation-requested",
            "center=$center centerChunk=${preparation.centerChunk} previousChunks=${previousPreparation?.coveredChunks?.size ?: 0} targetChunks=${targetChunks.size} ticketMode=per-chunk ticketDistance=$PRECISE_CHUNK_TICKET_DISTANCE futures=${chunkFutures.size}"
        )
        return null
    }

    fun ensurePlatformBootstrap(instanceId: UUID, level: ServerLevel, center: BlockPos): String? {
        val record = instances[instanceId] ?: return "unknown runtime instance"
        if (record.levelKey != level.dimension()) {
            return "runtime level mismatch for instance ${record.id}"
        }

        val targetChunk = ChunkPos(center)
        val existing = platformBootstrapPreparations[instanceId]
        if (existing != null && existing.targetChunk == targetChunk) {
            return null
        }

        clearPlatformBootstrapState(instanceId, level)
        val future = stabilizeChunkFuture(
            record = record,
            level = level,
            chunk = targetChunk,
            status = ChunkStatus.FULL,
            purpose = "platform-bootstrap",
            future = requestChunkFutureNonBlocking(level, targetChunk, ChunkStatus.FULL, load = true)
        )
        platformBootstrapPreparations[instanceId] = PlatformBootstrapPreparation(
            center = center,
            targetChunk = targetChunk,
            requestedGameTime = currentGameTime(level.server),
            chunkFuture = future
        )
        platformBootstrapStatuses[instanceId] = InstancePlatformBootstrapStatus(
            phase = InstancePlatformBootstrapPhase.PREPARING,
            center = center,
            requestedGameTime = currentGameTime(level.server)
        )
        level.chunkSource.addRegionTicket(
            runtimePlatformBootstrapTicketType,
            targetChunk,
            PRECISE_CHUNK_TICKET_DISTANCE,
            instanceId
        )
        traceInstance(
            record,
            "platform-bootstrap-requested",
            "center=$center centerChunk=$targetChunk ticketDistance=$PRECISE_CHUNK_TICKET_DISTANCE"
        )
        return null
    }

    fun deferDefaultArrivalPreparation(instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        deferredDefaultArrivalPreparation += instanceId
        traceInstance(record, "arrival-preparation-deferred", "reason=explicit")
        return true
    }

    override fun createInstance(server: MinecraftServer, templateId: String, ownerId: UUID?): InstanceCreateResult {
        ensureTemplatesLoaded()
        logger.info("Received createInstance request template={} owner={}", templateId, ownerId ?: "<none>")
        val validationError = validateTemplateForRuntime(server, templateId)
        if (validationError != null) {
            logger.warn("Rejecting createInstance request template={} owner={} reason={}", templateId, ownerId ?: "<none>", validationError)
            return InstanceCreateResult.Rejected(validationError)
        }
        val template = templates[templateId]
            ?: return InstanceCreateResult.Rejected("unknown runtime template '$templateId'")
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
        enqueueLifecycleRequest(InstanceLifecycleRequest.Create(record.id))
        traceInstance(record, "allocated", "queueDepth=${lifecycleRequests.size} levelLocation=$levelLocation seed=${record.instanceSeed}")
        markSavedDataDirty()
        return InstanceCreateResult.Accepted(record.toHandle())
    }

    override fun scheduleDestroy(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        if (record.state == InstanceState.DESTROYED) {
            traceInstance(record, "destroy-ignored", "reason=already-destroyed")
            return false
        }

        preserveAfterUnload.remove(id)
        val previousState = record.state
        record.state = when (record.state) {
            InstanceState.ACTIVE,
            InstanceState.LOADING,
            InstanceState.PREPARING,
            InstanceState.PREPARED,
            InstanceState.ALLOCATED -> InstanceState.DRAINING
            InstanceState.DRAINING,
            InstanceState.UNLOADING,
            InstanceState.CLOSING,
            InstanceState.DESTROYED -> record.state
        }
        record.updatedGameTime = currentGameTime(server)
        teardownRetryAfterGameTime[id] = currentGameTime(server)
        enqueueLifecycleRequest(InstanceLifecycleRequest.Destroy(id))
        traceInstance(record, "destroy-requested", "fromState=$previousState toState=${record.state} queueDepth=${lifecycleRequests.size}")
        markSavedDataDirty()
        return true
    }

    fun snapshotInstance(server: MinecraftServer, id: UUID): Boolean {
        val record = instances[id] ?: return false
        val loadedLevel = loadedLevel(server, record.levelKey) ?: return false
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
            "Clearing runtime instance manager instances={} lifecycleRequests={} loadedRuntimeLevels={} exposedRuntimeLevels={} liveLevelData={} arrivals={} preserveAfterUnload={} closeGraceCounts={}",
            instances.size,
            lifecycleRequests.size,
            loadedRuntimeLevels.size,
            exposedRuntimeLevels.size,
            liveLevelData.size,
            arrivalPreparations.size,
            preserveAfterUnload.size,
            closeGraceCounts.size
        )
        instances.clear()
        lifecycleRequests.clear()
        loadedRuntimeLevels.clear()
        exposedRuntimeLevels.clear()
        liveLevelData.clear()
        arrivalPreparations.clear()
        arrivalStatuses.clear()
        platformBootstrapPreparations.clear()
        platformBootstrapStatuses.clear()
        deferredDefaultArrivalPreparation.clear()
        preserveAfterUnload.clear()
        closeGraceCounts.clear()
        instanceTraceStartedAtNanos.clear()
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
            if (record.ownerId == null && record.state == InstanceState.ACTIVE) {
                // Migrate legacy hot prepared instances into the background preparation pipeline.
                record.state = InstanceState.PREPARING
                record.updatedGameTime = currentGameTime(server)
                traceInstance(record, "restored-legacy-ownerless-active", "migratedState=${record.state}")
            }
            register(record)
            if (record.state != InstanceState.PREPARED) {
                enqueueLifecycleRequest(InstanceLifecycleRequest.Create(record.id))
                traceInstance(record, "restored", "queueDepth=${lifecycleRequests.size}")
            } else {
                arrivalStatuses[record.id] = record.preparedSpawnPos?.let { spawn ->
                    InstanceArrivalStatus(
                        phase = InstanceArrivalPhase.READY,
                        center = spawn,
                        completedChunks = RuntimePlayerChunkWindowProfile.coveredChunks(ChunkPos(spawn)).size,
                        totalChunks = RuntimePlayerChunkWindowProfile.coveredChunks(ChunkPos(spawn)).size,
                        requestedGameTime = record.updatedGameTime,
                        readyGameTime = record.updatedGameTime
                    )
                } ?: InstanceArrivalStatus(phase = InstanceArrivalPhase.IDLE)
                traceInstance(record, "restored-cold-ready", "spawn=${record.preparedSpawnPos}")
            }
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
        shuttingDown = false
        DistantHorizonsCompat.reset()
        clearInstances()
        ensureServerLevelMapWrapper(event.server)
        reloadTemplates()
        restoreFromSavedData(event.server)
        logger.info("Runtime instance manager initialized")
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        if (shuttingDown) {
            return
        }
        flushLifecycle(event.server)
        DistantHorizonsCompat.onServerTick(event.server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        shuttingDown = true
        shutdownRuntimeLevels(event.server)
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        logger.info("Server stopped; resetting runtime instance manager")
        shuttingDown = false
        DistantHorizonsCompat.reset()
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
        if (initialQueueDepth > 1 || dirtyBefore) {
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
                is InstanceLifecycleRequest.Suspend -> {
                    logger.info(
                        "Lifecycle dequeue suspend instance={} remainingQueueDepth={}",
                        request.instanceId,
                        lifecycleRequests.size
                    )
                    dirty = applySuspend(server, request.instanceId) || dirty
                }
                is InstanceLifecycleRequest.Destroy -> {
                    val record = instances[request.instanceId]
                    if (
                        record == null ||
                        (
                            record.state != InstanceState.DRAINING &&
                                record.state != InstanceState.UNLOADING &&
                                record.state != InstanceState.CLOSING
                            )
                    ) {
                        logger.info(
                            "Lifecycle dequeue destroy instance={} remainingQueueDepth={}",
                            request.instanceId,
                            lifecycleRequests.size
                        )
                    }
                    dirty = applyDestroy(server, request.instanceId) || dirty
                }
            }
        }

        dirty = reconcileLoadedLevels(server) || dirty

        if (dirty || savedDataDirty) {
            sync(server)
        }
        if (initialQueueDepth > 1 || dirty || dirtyBefore || savedDataDirty) {
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

    private fun enqueueLifecycleRequest(request: InstanceLifecycleRequest): Boolean {
        val alreadyQueued = lifecycleRequests.any { queued ->
            queued.instanceId == request.instanceId && queued::class == request::class
        }
        if (!alreadyQueued) {
            lifecycleRequests.addLast(request)
        }
        return !alreadyQueued
    }

    private fun ensureTemplatesLoaded() {
        if (templates.isEmpty()) {
            reloadTemplates()
        }
    }

    private fun applyCreate(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        traceInstance(record, "create-begin", "worldPresent=${loadedLevel(server, record.levelKey) != null}")
        val existingLevel = loadedLevel(server, record.levelKey)
        if (existingLevel != null) {
            return when (record.state) {
                InstanceState.ACTIVE -> {
                    exposeRuntimeLevel(server, record, existingLevel, "create-existing-active")
                    if (shouldPrepareDefaultArrival(instanceId) && !isTravelReady(instanceId)) {
                        prepareArrivalRegion(instanceId, existingLevel, existingLevel.sharedSpawnPos)
                    }
                    traceInstance(record, "create-skip", "reason=already-active")
                    false
                }

                InstanceState.PREPARING -> {
                    traceInstance(record, "create-resume-preparing", "level=${existingLevel.dimension().location()}")
                    false
                }

                InstanceState.LOADING,
                InstanceState.PREPARED -> {
                    promoteLoadedPreparedInstance(server, record, existingLevel, reason = "create-promote-prepared-level")
                    true
                }

                else -> {
                    record.state = InstanceState.ACTIVE
                    record.updatedGameTime = currentGameTime(server)
                    exposeRuntimeLevel(server, record, existingLevel, "create-promote-existing-level")
                    if (shouldPrepareDefaultArrival(instanceId) && !isTravelReady(instanceId)) {
                        prepareArrivalRegion(instanceId, existingLevel, existingLevel.sharedSpawnPos)
                    }
                    MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Activated(server, record.toHandle(), existingLevel))
                    traceInstance(record, "create-promote-existing-level", "newState=${record.state}")
                    true
                }
            }
        }

        val validationError = validateTemplateForRuntime(server, record.templateId)
        if (validationError != null) {
            logger.warn("Discarding runtime instance {} for template {}: {}", record.id, record.templateId, validationError)
            arrivalStatuses.remove(instanceId)
            clearPlatformBootstrapState(instanceId)
            platformBootstrapStatuses.remove(instanceId)
            deferredDefaultArrivalPreparation.remove(instanceId)
            instances.remove(instanceId)
            markSavedDataDirty()
            return true
        }

        val template = templates[record.templateId]
        if (template == null) {
            logger.warn("Discarding runtime instance {} because template {} disappeared before construction", record.id, record.templateId)
            clearArrivalState(instanceId)
            arrivalStatuses.remove(instanceId)
            clearPlatformBootstrapState(instanceId)
            platformBootstrapStatuses.remove(instanceId)
            deferredDefaultArrivalPreparation.remove(instanceId)
            instances.remove(instanceId)
            markSavedDataDirty()
            return true
        }
        traceInstance(record, "create-construct-level", "templateStem=${template.stem}")
        val created = runCatching { InstanceLevelFactory.create(server, template, record) }
            .getOrElse { throwable ->
                logger.warn("Failed to construct runtime instance {} for template {}", record.id, record.templateId, throwable)
                RuntimeServerLevel.forgetSeed(record.levelKey)
                liveLevelData.remove(instanceId)
                clearArrivalState(instanceId)
                arrivalStatuses.remove(instanceId)
                clearPlatformBootstrapState(instanceId)
                platformBootstrapStatuses.remove(instanceId)
                deferredDefaultArrivalPreparation.remove(instanceId)
                closeGraceCounts.remove(instanceId)
                closeWaitLastLoggedAt.remove(instanceId)
                instances.remove(instanceId)
                markSavedDataDirty()
                return true
        }
        (created.level.levelData as? InstanceLevelData)?.let { liveLevelData[record.id] = it }
        traceInstance(record, "create-level-constructed", "spawn=${created.level.sharedSpawnPos}")
        putRuntimeLevel(server, created.level)
        traceInstance(
            record,
            "create-level-registered",
            "players=${server.playerList.players.size} exposed=${isExposedRuntimeLevel(created.level.dimension())}"
        )
        when (record.state) {
            InstanceState.PREPARING -> {
                traceInstance(record, "create-complete-preparing", "state=${record.state}")
            }

            InstanceState.LOADING,
            InstanceState.PREPARED -> {
                promoteLoadedPreparedInstance(server, record, created.level, reason = "create-complete-prepared-activation")
            }

            else -> {
                exposeRuntimeLevel(server, record, created.level, "create-complete-active")
                if (shouldPrepareDefaultArrival(record.id)) {
                    prepareArrivalRegion(record.id, created.level, created.level.sharedSpawnPos)?.let { reason ->
                        logger.warn("Runtime instance {} default arrival preparation failed: {}", record.id, reason)
                        arrivalStatuses[record.id] = InstanceArrivalStatus(
                            phase = InstanceArrivalPhase.FAILED,
                            center = created.level.sharedSpawnPos,
                            failureReason = reason
                        )
                    }
                } else {
                    traceInstance(record, "arrival-preparation-skipped", "reason=deferred-until-explicit-request")
                }
                record.preparedSpawnPos = null
                record.state = InstanceState.ACTIVE
                record.updatedGameTime = currentGameTime(server)
                MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Activated(server, record.toHandle(), created.level))
                traceInstance(record, "create-complete", "state=${record.state}")
            }
        }
        return true
    }

    private fun promoteLoadedPreparedInstance(
        server: MinecraftServer,
        record: InstanceRecord,
        level: ServerLevel,
        reason: String
    ) {
        val spawnPos = record.preparedSpawnPos
        if (spawnPos == null) {
            traceInstance(record, "prepared-activation-missing-spawn", "reason=$reason")
            return
        }
        exposeRuntimeLevel(server, record, level, reason)
        val coveredChunkCount = RuntimePlayerChunkWindowProfile.coveredChunks(ChunkPos(spawnPos)).size
        arrivalStatuses[record.id] = InstanceArrivalStatus(
            phase = InstanceArrivalPhase.IDLE,
            center = spawnPos,
            completedChunks = 0,
            totalChunks = coveredChunkCount,
            requestedGameTime = currentGameTime(server)
        )
        record.state = InstanceState.ACTIVE
        record.updatedGameTime = currentGameTime(server)
        MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Activated(server, record.toHandle(), level))
        traceInstance(
            record,
            reason,
            "spawn=$spawnPos state=${record.state} arrivalPhase=${arrivalStatuses[record.id]?.phase} requiresArrivalRehydrate=true"
        )
    }

    private fun restoreColdReadyArrivalStatus(record: InstanceRecord, readyGameTime: Long = record.updatedGameTime) {
        val spawnPos = record.preparedSpawnPos ?: run {
            arrivalStatuses.remove(record.id)
            return
        }
        val coveredChunkCount = RuntimePlayerChunkWindowProfile.coveredChunks(ChunkPos(spawnPos)).size
        arrivalStatuses[record.id] = InstanceArrivalStatus(
            phase = InstanceArrivalPhase.READY,
            center = spawnPos,
            completedChunks = coveredChunkCount,
            totalChunks = coveredChunkCount,
            requestedGameTime = record.updatedGameTime,
            readyGameTime = readyGameTime
        )
    }

    private fun applySuspend(server: MinecraftServer, instanceId: UUID): Boolean {
        if (instanceId !in preserveAfterUnload) {
            return false
        }
        return applyDestroy(server, instanceId)
    }

    private fun applyDestroy(server: MinecraftServer, instanceId: UUID): Boolean {
        val record = instances[instanceId] ?: return false
        val loadedLevel = loadedLevel(server, record.levelKey)
        val now = currentGameTime(server)
        val preserving = instanceId in preserveAfterUnload
        traceInstance(record, "destroy-begin", "loadedLevel=${loadedLevel != null} state=${record.state} preserving=$preserving")

        if (loadedLevel == null) {
            RuntimeServerLevel.forgetSeed(record.levelKey)
            liveLevelData.remove(instanceId)
            clearArrivalState(instanceId)
            clearPlatformBootstrapState(instanceId)
            platformBootstrapStatuses.remove(instanceId)
            deferredDefaultArrivalPreparation.remove(instanceId)
            closeGraceCounts.remove(instanceId)
            closeWaitLastLoggedAt.remove(instanceId)
            teardownRetryAfterGameTime.remove(instanceId)
            if (preserving) {
                preserveAfterUnload.remove(instanceId)
                record.state = InstanceState.PREPARED
                record.updatedGameTime = now
                restoreColdReadyArrivalStatus(record)
                traceInstance(record, "prepared-cold-ready", "reason=level-missing spawn=${record.preparedSpawnPos}")
            } else {
                arrivalStatuses.remove(instanceId)
                instances.remove(instanceId)
                traceInstance(record, "destroy-pruned-unloaded", "reason=level-missing")
                instanceTraceStartedAtNanos.remove(instanceId)
            }
            markSavedDataDirty()
            return true
        }

        val residentPlayers = loadedLevel.players().filter { it.serverLevel().dimension() == loadedLevel.dimension() }
        if (residentPlayers.isNotEmpty()) {
            if (record.state != InstanceState.DRAINING) {
                record.state = InstanceState.DRAINING
                record.updatedGameTime = now
                teardownRetryAfterGameTime[instanceId] = now + C2meCompat.unloadDrainTicks()
                traceInstance(record, "destroy-waiting-for-players", "residentPlayers=${residentPlayers.map { it.scoreboardName }}")
                markSavedDataDirty()
                return true
            }
            scheduleTeardownRetry(instanceId, now, destroyRetryDelayTicks(waitingForPlayers = true))
            maybeLogDestroyWait(server, record, "players-still-present", "residentPlayers=${residentPlayers.map { it.scoreboardName }}")
            return false
        }

        if (record.state != InstanceState.UNLOADING && record.state != InstanceState.CLOSING) {
            record.state = InstanceState.UNLOADING
            record.updatedGameTime = now
            teardownRetryAfterGameTime[instanceId] = now + C2meCompat.unloadDrainTicks()
            loadedLevel.noSave = false
            captureRuntimeState(record, loadedLevel)
            clearArrivalState(instanceId, loadedLevel)
            clearPlatformBootstrapState(instanceId, loadedLevel)
            if (isExposedRuntimeLevel(loadedLevel.dimension())) {
                DistantHorizonsCompat.unloadRuntimeLevel(loadedLevel, "destroy-transition-unloading")
            }
            loadedLevel.chunkSource.removeTicketsOnClosing()
            closeGraceCounts.remove(instanceId)
            closeWaitLastLoggedAt.remove(instanceId)
            MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Unloading(server, record.toHandle(), loadedLevel))
            traceInstance(record, "destroy-transition-unloading", "level=${loadedLevel.dimension().location()} preserving=$preserving")
            markSavedDataDirty()
            return true
        }

        loadedLevel.noSave = false

        if (record.state == InstanceState.UNLOADING) {
            val drainSnapshot = drainChunkWork(loadedLevel, closing = false)
            if (!isReadyForClose(instanceId, drainSnapshot)) {
                scheduleTeardownRetry(instanceId, now, destroyRetryDelayTicks(snapshot = drainSnapshot, closing = false))
                maybeLogDestroyWait(server, record, "unloading-drain", formatDrainSnapshot(drainSnapshot))
                return false
            }

            // Persist any chunk mutations once before the manual close path takes over.
            loadedLevel.save(null, true, false)
            record.state = InstanceState.CLOSING
            record.updatedGameTime = now
            teardownRetryAfterGameTime[instanceId] = now + C2meCompat.unloadDrainTicks()
            closeGraceCounts.remove(instanceId)
            closeWaitLastLoggedAt.remove(instanceId)
            traceInstance(record, "destroy-transition-closing", formatDrainSnapshot(drainSnapshot))
            markSavedDataDirty()
            return true
        }

        val preCloseSnapshot = drainChunkWork(loadedLevel, closing = true)
        if (!isReadyForClose(instanceId, preCloseSnapshot)) {
            scheduleTeardownRetry(instanceId, now, destroyRetryDelayTicks(snapshot = preCloseSnapshot, closing = true))
            maybeLogDestroyWait(server, record, "closing-drain", formatDrainSnapshot(preCloseSnapshot))
            return false
        }

        traceInstance(record, "destroy-close-level", formatDrainSnapshot(preCloseSnapshot))
        if (isExposedRuntimeLevel(loadedLevel.dimension())) {
            MinecraftForge.EVENT_BUS.post(LevelEvent.Unload(loadedLevel))
            DistantHorizonsCompat.unregisterRuntimeLevel(loadedLevel, "destroy-close-level")
        }
        closeLevelWithoutSave(loadedLevel)
        removeRuntimeLevel(server, loadedLevel)
        RuntimeServerLevel.forgetSeed(record.levelKey)
        liveLevelData.remove(instanceId)
        clearArrivalState(instanceId)
        clearPlatformBootstrapState(instanceId)
        platformBootstrapStatuses.remove(instanceId)
        deferredDefaultArrivalPreparation.remove(instanceId)
        closeGraceCounts.remove(instanceId)
        closeWaitLastLoggedAt.remove(instanceId)
        teardownRetryAfterGameTime.remove(instanceId)
        if (preserving) {
            preserveAfterUnload.remove(instanceId)
            record.state = InstanceState.PREPARED
            record.updatedGameTime = now
            restoreColdReadyArrivalStatus(record)
            traceInstance(record, "prepared-cold-ready", "spawn=${record.preparedSpawnPos} teardownProfile=${C2meCompat.profileName()}")
        } else {
            arrivalStatuses.remove(instanceId)
            instances.remove(instanceId)
            MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Destroyed(server, record.toHandle()))
            logger.info("Destroyed instance {} using {} teardown profile", instanceId, C2meCompat.profileName())
            instanceTraceStartedAtNanos.remove(instanceId)
        }
        markSavedDataDirty()
        return true
    }

    private fun reconcileLoadedLevels(server: MinecraftServer): Boolean {
        val now = currentGameTime(server)
        var dirty = false
        var teardownBudget = C2meCompat.teardownProgressBudgetPerTick()

        instances.values.forEach { record ->
            val loadedLevel = loadedLevel(server, record.levelKey)
            if (loadedLevel != null) {
                if (!isExposedRuntimeLevel(record.levelKey) && shouldAdvanceHiddenRuntimeLevel(record)) {
                    tickHiddenRuntimeLevel(record, loadedLevel)
                }
                advancePlatformBootstrap(instanceId = record.id, level = loadedLevel)
                advanceArrivalPreparation(record.id, loadedLevel)
            }
            if (
                loadedLevel != null &&
                teardownBudget > 0 &&
                now >= (teardownRetryAfterGameTime[record.id] ?: Long.MIN_VALUE) &&
                (
                    record.state == InstanceState.DRAINING ||
                        record.state == InstanceState.UNLOADING ||
                        record.state == InstanceState.CLOSING
                    ) &&
                now - record.updatedGameTime >= C2meCompat.unloadDrainTicks()
            ) {
                scheduleTeardownRetry(record.id, now, C2meCompat.unloadDrainTicks())
                if (enqueueLifecycleRequest(InstanceLifecycleRequest.Destroy(record.id))) {
                    traceInstance(record, "reconcile-enqueue-destroy", "queueDepth=${lifecycleRequests.size} teardownBudgetBefore=$teardownBudget")
                    teardownBudget--
                }
            }
            if (
                loadedLevel != null &&
                record.state != InstanceState.LOADING &&
                record.state != InstanceState.PREPARING &&
                record.state != InstanceState.PREPARED &&
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

    private fun putRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        val wrappedLevelMap = ensureServerLevelMapWrapper(server)
        loadedRuntimeLevels[level.dimension()] = level
        wrappedLevelMap.putHidden(level.dimension(), level)
        DistantHorizonsCompat.unloadRuntimeLevel(level, "runtime-level-hidden")
        DistantHorizonsCompat.unregisterRuntimeLevel(level, "runtime-level-hidden")
        logger.info(
            "Tracked runtime level {} loadedRuntimeLevels={} exposedRuntimeLevels={} hiddenLevelMapSize={} worldMapVisibleContains={}",
            level.dimension().location(),
            loadedRuntimeLevels.size,
            exposedRuntimeLevels.size,
            wrappedLevelMap.hiddenSize(),
            wrappedLevelMap.isVisible(level.dimension())
        )
    }

    @Suppress("DEPRECATION")
    private fun removeRuntimeLevel(server: MinecraftServer, level: ServerLevel) {
        val levelKey = level.dimension()
        val wrappedLevelMap = ensureServerLevelMapWrapper(server)
        DistantHorizonsCompat.unregisterRuntimeLevel(level, "runtime-level-removed")
        loadedRuntimeLevels.remove(levelKey)
        wrappedLevelMap.removeHidden(levelKey)
        if (exposedRuntimeLevels.remove(levelKey)) {
            wrappedLevelMap.remove(levelKey)
            server.markWorldsDirty()
            RuntimeLevelKeySyncManager.revokeRuntimeLevel(server, levelKey)
        }
        logger.info(
            "Removed runtime level {} loadedRuntimeLevels={} exposedRuntimeLevels={} hiddenLevelMapSize={} worldMapVisibleContains={}",
            levelKey.location(),
            loadedRuntimeLevels.size,
            exposedRuntimeLevels.size,
            wrappedLevelMap.hiddenSize(),
            wrappedLevelMap.isVisible(levelKey)
        )
    }

    @Suppress("DEPRECATION")
    private fun exposeRuntimeLevel(server: MinecraftServer, record: InstanceRecord, level: ServerLevel, reason: String) {
        val levelKey = level.dimension()
        val wrappedLevelMap = ensureServerLevelMapWrapper(server)
        loadedRuntimeLevels[levelKey] = level
        if (!exposedRuntimeLevels.add(levelKey)) {
            return
        }
        wrappedLevelMap.expose(levelKey, level)
        server.markWorldsDirty()
        RuntimeLevelKeySyncManager.announceRuntimeLevel(server, levelKey)
        MinecraftForge.EVENT_BUS.post(LevelEvent.Load(level))
        DistantHorizonsCompat.ensureRuntimeWorldgenOverride(server, level, "runtime-level-exposed:$reason")
        traceInstance(record, "level-exposed", "reason=$reason worldMapSize=${server.forgeGetWorldMap().size}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun ensureServerLevelMapWrapper(server: MinecraftServer): HiddenResolvableLevelMap {
        val current = serverLevelMapField.get(server)
        if (current is HiddenResolvableLevelMap) {
            return current
        }

        val visibleLevels = current as? MutableMap<ResourceKey<Level>, ServerLevel>
            ?: error("MinecraftServer level map is not mutable: ${current?.javaClass?.name ?: "<null>"}")
        val wrapped = HiddenResolvableLevelMap(visibleLevels)
        serverLevelMapField.set(server, wrapped)
        logger.info(
            "Installed hidden-resolvable runtime level map visibleLevels={} hiddenLevels={}",
            visibleLevels.size,
            wrapped.hiddenSize()
        )
        return wrapped
    }

    fun isRuntimeLevelExposed(levelKey: ResourceKey<Level>): Boolean = levelKey in exposedRuntimeLevels

    private fun isExposedRuntimeLevel(levelKey: ResourceKey<Level>): Boolean = isRuntimeLevelExposed(levelKey)

    private fun shouldAdvanceHiddenRuntimeLevel(record: InstanceRecord): Boolean {
        return record.state != InstanceState.DRAINING &&
            record.state != InstanceState.UNLOADING &&
            record.state != InstanceState.CLOSING &&
            record.state != InstanceState.DESTROYED
    }

    private fun tickHiddenRuntimeLevel(record: InstanceRecord, level: ServerLevel) {
        repeat(C2meCompat.chunkDrainIterations(closing = false)) {
            level.chunkSource.tick({ true }, false)
        }
        for (attempt in 0 until minOf(64, C2meCompat.chunkTaskPollLimit(closing = false))) {
            if (!level.chunkSource.pollTask()) {
                break
            }
        }
        val now = currentGameTime(level.server)
        val lastLoggedAt = closeWaitLastLoggedAt[record.id]
        if (lastLoggedAt == null || now - lastLoggedAt >= 20L) {
            traceInstance(
                record,
                "hidden-level-progress",
                "loadedChunks=${level.chunkSource.gatherStats()} pendingTasks=${level.chunkSource.getPendingTasksCount()}"
            )
            closeWaitLastLoggedAt[record.id] = now
        }
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

    private fun advanceArrivalPreparation(instanceId: UUID, level: ServerLevel) {
        val preparation = arrivalPreparations[instanceId] ?: return
        val now = currentGameTime(level.server)
        val completedChunks = preparation.chunkFutures.count { it.value.isDone }
        val failureReason = preparation.chunkFutures.entries.firstNotNullOfOrNull { (chunk, future) ->
            describeArrivalFutureFailure(chunk, future)
        }

        if (failureReason != null) {
            arrivalStatuses[instanceId] = InstanceArrivalStatus(
                phase = InstanceArrivalPhase.FAILED,
                center = preparation.center,
                totalChunks = preparation.coveredChunks.size,
                completedChunks = completedChunks,
                requestedGameTime = preparation.requestedGameTime,
                failureReason = failureReason
            )
            clearArrivalState(instanceId, level)
            traceInstance(
                instances[instanceId],
                "arrival-preparation-failed",
                "center=${preparation.center} centerChunk=${preparation.centerChunk} completedChunks=$completedChunks totalChunks=${preparation.coveredChunks.size} reason=$failureReason"
            )
            return
        }

        if (completedChunks < preparation.coveredChunks.size) {
            arrivalStatuses[instanceId] = InstanceArrivalStatus(
                phase = InstanceArrivalPhase.PREPARING,
                center = preparation.center,
                totalChunks = preparation.coveredChunks.size,
                completedChunks = completedChunks,
                requestedGameTime = preparation.requestedGameTime
            )
            if (now - preparation.requestedGameTime >= ARRIVAL_PREPARATION_TIMEOUT_TICKS) {
                val timeoutReason = "arrival region generation timed out after ${now - preparation.requestedGameTime} ticks"
                arrivalStatuses[instanceId] = InstanceArrivalStatus(
                    phase = InstanceArrivalPhase.FAILED,
                    center = preparation.center,
                    totalChunks = preparation.coveredChunks.size,
                    completedChunks = completedChunks,
                    requestedGameTime = preparation.requestedGameTime,
                    failureReason = timeoutReason
                )
                clearArrivalState(instanceId, level)
                traceInstance(
                    instances[instanceId],
                    "arrival-preparation-failed",
                    "center=${preparation.center} centerChunk=${preparation.centerChunk} completedChunks=$completedChunks totalChunks=${preparation.coveredChunks.size} reason=$timeoutReason"
                )
                return
            }
            if (preparation.lastProgressLogGameTime == Long.MIN_VALUE || now - preparation.lastProgressLogGameTime >= 20L) {
                traceInstance(
                    instances[instanceId],
                    "arrival-preparation-waiting",
                    "center=${preparation.center} centerChunk=${preparation.centerChunk} completedChunks=$completedChunks totalChunks=${preparation.coveredChunks.size} requestedAt=${preparation.requestedGameTime}"
                )
                preparation.lastProgressLogGameTime = now
            }
            return
        }

        arrivalStatuses[instanceId] = InstanceArrivalStatus(
            phase = InstanceArrivalPhase.READY,
            center = preparation.center,
            totalChunks = preparation.coveredChunks.size,
            completedChunks = preparation.coveredChunks.size,
            requestedGameTime = preparation.requestedGameTime,
            readyGameTime = now
        )
        clearArrivalState(instanceId, level)
        traceInstance(
            instances[instanceId],
            "arrival-preparation-complete",
            "center=${preparation.center} centerChunk=${preparation.centerChunk} totalChunks=${preparation.coveredChunks.size} readyAt=$now loadedChunks=${level.chunkSource.gatherStats()}"
        )
    }

    private fun advancePlatformBootstrap(instanceId: UUID, level: ServerLevel) {
        val preparation = platformBootstrapPreparations[instanceId] ?: return
        val now = currentGameTime(level.server)
        val failureReason = describeArrivalFutureFailure(preparation.targetChunk, preparation.chunkFuture)
        if (failureReason != null) {
            platformBootstrapStatuses[instanceId] = InstancePlatformBootstrapStatus(
                phase = InstancePlatformBootstrapPhase.FAILED,
                center = preparation.center,
                requestedGameTime = preparation.requestedGameTime,
                failureReason = failureReason
            )
            clearPlatformBootstrapState(instanceId, level)
            traceInstance(
                instances[instanceId],
                "platform-bootstrap-failed",
                "center=${preparation.center} centerChunk=${preparation.targetChunk} reason=$failureReason"
            )
            return
        }

        if (!preparation.chunkFuture.isDone) {
            platformBootstrapStatuses[instanceId] = InstancePlatformBootstrapStatus(
                phase = InstancePlatformBootstrapPhase.PREPARING,
                center = preparation.center,
                requestedGameTime = preparation.requestedGameTime
            )
            if (now - preparation.requestedGameTime >= PLATFORM_BOOTSTRAP_TIMEOUT_TICKS) {
                val timeoutReason = "platform bootstrap timed out after ${now - preparation.requestedGameTime} ticks"
                platformBootstrapStatuses[instanceId] = InstancePlatformBootstrapStatus(
                    phase = InstancePlatformBootstrapPhase.FAILED,
                    center = preparation.center,
                    requestedGameTime = preparation.requestedGameTime,
                    failureReason = timeoutReason
                )
                clearPlatformBootstrapState(instanceId, level)
                traceInstance(
                    instances[instanceId],
                    "platform-bootstrap-failed",
                    "center=${preparation.center} centerChunk=${preparation.targetChunk} reason=$timeoutReason"
                )
                return
            }
            if (preparation.lastProgressLogGameTime == Long.MIN_VALUE || now - preparation.lastProgressLogGameTime >= 20L) {
                traceInstance(
                    instances[instanceId],
                    "platform-bootstrap-waiting",
                    "center=${preparation.center} centerChunk=${preparation.targetChunk} requestedAt=${preparation.requestedGameTime}"
                )
                preparation.lastProgressLogGameTime = now
            }
            return
        }

        platformBootstrapStatuses[instanceId] = InstancePlatformBootstrapStatus(
            phase = InstancePlatformBootstrapPhase.READY,
            center = preparation.center,
            requestedGameTime = preparation.requestedGameTime,
            readyGameTime = now
        )
        clearPlatformBootstrapState(instanceId, level)
        traceInstance(
            instances[instanceId],
            "platform-bootstrap-complete",
            "center=${preparation.center} centerChunk=${preparation.targetChunk} readyAt=$now"
        )
    }

    private fun clearArrivalState(instanceId: UUID, level: ServerLevel? = null) {
        val preparation = arrivalPreparations.remove(instanceId) ?: return
        preparation.chunkFutures.values.forEach { exceptionalChunkFutureFailures.remove(it) }
        level ?: return
        preparation.coveredChunks.forEach { chunk ->
            level.chunkSource.removeRegionTicket(
                runtimeArrivalTicketType,
                chunk,
                PRECISE_CHUNK_TICKET_DISTANCE,
                instanceId
            )
        }
    }

    fun clearPlatformBootstrap(instanceId: UUID, level: ServerLevel? = null) {
        clearPlatformBootstrapState(instanceId, level)
        platformBootstrapStatuses.remove(instanceId)
    }

    private fun clearPlatformBootstrapState(instanceId: UUID, level: ServerLevel? = null) {
        val preparation = platformBootstrapPreparations.remove(instanceId) ?: return
        exceptionalChunkFutureFailures.remove(preparation.chunkFuture)
        level ?: return
        level.chunkSource.removeRegionTicket(
            runtimePlatformBootstrapTicketType,
            preparation.targetChunk,
            PRECISE_CHUNK_TICKET_DISTANCE,
            instanceId
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun requestChunkFutureNonBlocking(
        level: ServerLevel,
        chunk: ChunkPos,
        status: ChunkStatus,
        load: Boolean
    ): CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> {
        return try {
            nonBlockingChunkFutureMethod.invoke(level.chunkSource, chunk.x, chunk.z, status, load)
                as CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
        } catch (throwable: Throwable) {
            val cause = throwable.cause ?: throwable
            throw IllegalStateException(
                "Failed to request non-blocking chunk future for $chunk in ${level.dimension().location()}",
                cause
            )
        }
    }

    private fun describeArrivalFutureFailure(
        chunk: ChunkPos,
        future: CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
    ): String? {
        exceptionalChunkFutureFailures[future]?.let { return "chunk=$chunk failure=$it" }
        if (!future.isDone) {
            return null
        }
        val result = try {
            future.getNow(null)
        } catch (throwable: Throwable) {
            val cause = unwrapFutureFailure(throwable)
            return "chunk=$chunk failure=exception ${cause.javaClass.name}: ${cause.message ?: "<no message>"}"
        }
        return when {
            result == null -> null
            result.map({ null }, { it.toString() }) != null -> "chunk=$chunk failure=${result.map({ null }, { it.toString() })}"
            else -> null
        }
    }

    private fun stabilizeChunkFuture(
        record: InstanceRecord,
        level: ServerLevel,
        chunk: ChunkPos,
        status: ChunkStatus,
        purpose: String,
        future: CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>
    ): CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> {
        future.whenComplete { _, throwable ->
            if (throwable == null) {
                exceptionalChunkFutureFailures.remove(future)
                return@whenComplete
            }

            val cause = unwrapFutureFailure(throwable)
            val failure = "exception purpose=$purpose level=${level.dimension().location()} status=$status error=${cause.javaClass.name}: ${cause.message ?: "<no message>"}"
            exceptionalChunkFutureFailures[future] = failure
            logger.error(
                "Runtime chunk future failed instance={} template={} purpose={} level={} chunk={} status={}",
                record.id,
                record.templateId,
                purpose,
                level.dimension().location(),
                chunk,
                status,
                cause
            )
            runCatching {
                future.obtrudeValue(Either.right(ChunkHolder.ChunkLoadingFailure.UNLOADED))
            }.onFailure { obtrudeFailure ->
                logger.error(
                    "Failed to stabilize exceptional runtime chunk future instance={} level={} chunk={} purpose={}",
                    record.id,
                    level.dimension().location(),
                    chunk,
                    purpose,
                    obtrudeFailure
                )
            }
        }
        return future
    }

    private fun unwrapFutureFailure(throwable: Throwable): Throwable {
        var current = throwable
        while (
            (current is CompletionException || current is ExecutionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun drainChunkWork(level: ServerLevel, closing: Boolean): ChunkDrainSnapshot {
        repeat(C2meCompat.chunkDrainIterations(closing)) {
            level.chunkSource.tick({ true }, false)
        }

        for (attempt in 0 until C2meCompat.chunkTaskPollLimit(closing)) {
            if (!level.chunkSource.pollTask()) {
                break
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

    private fun shutdownRuntimeLevels(server: MinecraftServer) {
        if (instances.isEmpty() && lifecycleRequests.isEmpty()) {
            logger.info("Server stopping; no runtime instances to force-close")
            return
        }
        logger.info(
            "Server stopping; force-closing runtime instances count={} queueDepth={} liveLevelData={} arrivals={}",
            instances.size,
            lifecycleRequests.size,
            liveLevelData.size,
            arrivalPreparations.size
        )

        instances.values.toList().forEach { record ->
            forceCloseRuntimeLevel(server, record)
        }

        instances.clear()
        lifecycleRequests.clear()
        loadedRuntimeLevels.clear()
        exposedRuntimeLevels.clear()
        runCatching { ensureServerLevelMapWrapper(server).clearHidden() }
            .onFailure { throwable -> logger.warn("Failed to clear hidden runtime levels from server level map during shutdown", throwable) }
        liveLevelData.clear()
        arrivalPreparations.clear()
        arrivalStatuses.clear()
        platformBootstrapPreparations.clear()
        platformBootstrapStatuses.clear()
        deferredDefaultArrivalPreparation.clear()
        preserveAfterUnload.clear()
        closeGraceCounts.clear()
        closeWaitLastLoggedAt.clear()
        teardownRetryAfterGameTime.clear()
        instanceTraceStartedAtNanos.clear()
        RuntimeServerLevel.clearSeeds()
        RuntimeLevelKeySyncManager.reset()
        InstanceSavedData.get(server).replaceAll(emptyList())
        savedDataDirty = false
    }

    private fun forceCloseRuntimeLevel(server: MinecraftServer, record: InstanceRecord) {
        val loadedLevel = loadedLevel(server, record.levelKey)
        if (loadedLevel == null) {
            RuntimeServerLevel.forgetSeed(record.levelKey)
            traceInstance(record, "shutdown-pruned-unloaded", "reason=level-missing")
            return
        }

        traceInstance(record, "shutdown-force-close", "state=${record.state} level=${loadedLevel.dimension().location()}")
        loadedLevel.noSave = true
        clearArrivalState(record.id, loadedLevel)
        clearPlatformBootstrapState(record.id, loadedLevel)
        runCatching { loadedLevel.chunkSource.removeTicketsOnClosing() }
            .onFailure { throwable -> logger.warn("Failed to remove runtime tickets during shutdown for {}", record.levelKey.location(), throwable) }
        runCatching { MinecraftForge.EVENT_BUS.post(RuntimeInstanceEvent.Unloading(server, record.toHandle(), loadedLevel)) }
            .onFailure { throwable -> logger.warn("Failed to post runtime unloading event during shutdown for {}", record.levelKey.location(), throwable) }
        if (isExposedRuntimeLevel(loadedLevel.dimension())) {
            runCatching { MinecraftForge.EVENT_BUS.post(LevelEvent.Unload(loadedLevel)) }
                .onFailure { throwable -> logger.warn("Failed to post level unload during shutdown for {}", record.levelKey.location(), throwable) }
            DistantHorizonsCompat.unregisterRuntimeLevel(loadedLevel, "shutdown-force-close")
        }
        runCatching { closeLevelWithoutSave(loadedLevel) }
            .onFailure { throwable -> logger.warn("Failed to close runtime level during shutdown for {}", record.levelKey.location(), throwable) }
        runCatching { removeRuntimeLevel(server, loadedLevel) }
            .onFailure { throwable -> logger.warn("Failed to remove runtime level from server map during shutdown for {}", record.levelKey.location(), throwable) }
        RuntimeServerLevel.forgetSeed(record.levelKey)
        preserveAfterUnload.remove(record.id)
        teardownRetryAfterGameTime.remove(record.id)
    }

    private fun shouldPrepareDefaultArrival(instanceId: UUID): Boolean {
        return instanceId !in deferredDefaultArrivalPreparation
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
        if (action in suppressedTraceActions) {
            return
        }
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

    private fun scheduleTeardownRetry(instanceId: UUID, now: Long, delayTicks: Long) {
        teardownRetryAfterGameTime[instanceId] = now + delayTicks.coerceAtLeast(1L)
    }

    private fun destroyRetryDelayTicks(
        snapshot: ChunkDrainSnapshot? = null,
        closing: Boolean = false,
        waitingForPlayers: Boolean = false
    ): Long {
        if (waitingForPlayers) {
            return 20L
        }
        if (snapshot == null) {
            return C2meCompat.unloadDrainTicks()
        }
        if (snapshot.loadedChunks >= 1024) {
            return 20L
        }
        if (snapshot.loadedChunks >= 256) {
            return 10L
        }
        if (snapshot.loadedChunks >= 64) {
            return 5L
        }
        if (snapshot.pendingTasks > 0) {
            return C2meCompat.unloadDrainTicks()
        }
        if (snapshot.chunkMapHasWork || closing) {
            return C2meCompat.unloadDrainTicks()
        }
        return 1L
    }

    private fun formatDrainSnapshot(snapshot: ChunkDrainSnapshot): String {
        return "loadedChunks=${snapshot.loadedChunks} pendingTasks=${snapshot.pendingTasks} chunkMapHasWork=${snapshot.chunkMapHasWork} storagePendingWrites=${snapshot.storagePendingWrites}"
    }
}
