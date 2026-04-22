package dev.yourname.instanceddimensions.compat

import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraftforge.fml.ModList
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime instance levels are short-lived transport worlds. DH still needs to know
 * they exist so player-level transitions remain coherent, but DH must not spin up
 * its own distant worldgen pipeline for them.
 *
 * This compat layer registers a no-op world generator override per runtime level,
 * keeps retrying registration until DH is ready to accept it, and removes its own
 * injector entries when the runtime level is closed so stale wrappers do not leak.
 */
object DistantHorizonsCompat {

    private const val MOD_ID = "distanthorizons"
    private const val RUNTIME_LEVEL_PREFIX = "instanceddimensions:instance/"
    private const val GENERATOR_LOG_INTERVAL_MILLIS = 5_000L
    private const val SUPPRESSION_LOG_INTERVAL_TICKS = 20L * 5L

    private val logger = LogUtils.getLogger()

    @Volatile
    private var reflection: ReflectionHandles? = null

    private val registeredRuntimeLevels = linkedMapOf<ResourceKey<Level>, RegisteredRuntimeLevel>()
    private val pendingRegistrations = linkedMapOf<ResourceKey<Level>, PendingRegistration>()
    private val suppressionLoggedAt = linkedMapOf<ResourceKey<Level>, Long>()

    fun ensureRuntimeWorldgenOverride(@Suppress("UNUSED_PARAMETER") server: MinecraftServer, level: ServerLevel, reason: String) {
        if (!ModList.get().isLoaded(MOD_ID) || !isRuntimeLevel(level.dimension())) {
            return
        }

        val handles = runCatching { reflection ?: ReflectionHandles.resolve().also { reflection = it } }
            .getOrElse { throwable ->
                logger.warn("Failed to resolve Distant Horizons runtime compat hooks", throwable)
                markPending(level.dimension(), reason, "reflection-unavailable")
                return
            }

        val wrapper = runCatching { handles.serverLevelWrapperGetWrapper.invoke(null, level) }
            .getOrElse { throwable ->
                logger.warn(
                    "Failed to resolve Distant Horizons level wrapper reason={} level={}",
                    reason,
                    level.dimension().location(),
                    throwable
                )
                markPending(level.dimension(), reason, "wrapper-resolution-failed")
                return
            } ?: run {
            logger.warn(
                "Distant Horizons returned no level wrapper for runtime level reason={} level={}",
                reason,
                level.dimension().location()
            )
            markPending(level.dimension(), reason, "wrapper-unavailable")
            return
        }

        val dhIdentifier = runCatching { handles.getDhIdentifier.invoke(wrapper) as? String }.getOrNull() ?: "<unknown>"
        val dimensionName = runCatching { handles.getDimensionName.invoke(wrapper) as? String }.getOrNull() ?: "<unknown>"
        val existing = registeredRuntimeLevels[level.dimension()]
        if (existing != null && existing.wrapper == wrapper) {
            pendingRegistrations.remove(level.dimension())
            return
        }
        if (existing != null) {
            removeRegisteredOverride(handles, level.dimension(), existing, "wrapper-replaced:$reason")
        }

        val generatorStats = GeneratorStats()
        val generator = createNoOpWorldGenerator(handles, level.dimension(), generatorStats)
        val result = runCatching {
            handles.registerWorldGeneratorOverride.invoke(handles.worldGenOverrides, wrapper, generator)
        }.getOrElse { throwable ->
            logger.warn(
                "Failed to register Distant Horizons runtime worldgen override reason={} level={} dhIdentifier={} dimensionName={}",
                reason,
                level.dimension().location(),
                dhIdentifier,
                dimensionName,
                throwable
            )
            markPending(level.dimension(), reason, "register-threw")
            return
        }

        val success = runCatching { handles.resultSuccessField.getBoolean(result) }.getOrDefault(false)
        val message = runCatching { handles.resultMessageField.get(result) as? String }.getOrNull()
        if (!success) {
            logger.warn(
                "Distant Horizons rejected runtime worldgen override reason={} level={} dhIdentifier={} dimensionName={} message={}",
                reason,
                level.dimension().location(),
                dhIdentifier,
                dimensionName,
                message ?: "<none>"
            )
            markPending(level.dimension(), reason, message ?: "register-failed")
            return
        }

        registeredRuntimeLevels[level.dimension()] = RegisteredRuntimeLevel(
            wrapper = wrapper,
            generator = generator,
            dhIdentifier = dhIdentifier,
            dimensionName = dimensionName,
            generatorStats = generatorStats
        )
        pendingRegistrations.remove(level.dimension())
        logger.info(
            "Registered Distant Horizons runtime worldgen override reason={} level={} dhIdentifier={} dimensionName={} registeredRuntimeLevels={}",
            reason,
            level.dimension().location(),
            dhIdentifier,
            dimensionName,
            registeredRuntimeLevels.size
        )
        suppressRuntimeWorldgen(level, handles, registeredRuntimeLevels[level.dimension()]!!, "post-register:$reason")
    }

    fun onServerTick(server: MinecraftServer) {
        if (!ModList.get().isLoaded(MOD_ID) || (registeredRuntimeLevels.isEmpty() && pendingRegistrations.isEmpty())) {
            return
        }

        registeredRuntimeLevels.keys
            .filter { server.getLevel(it) == null }
            .toList()
            .forEach { levelKey ->
                unregisterRuntimeLevel(levelKey, "server-tick-stale-level")
            }

        val handles = runCatching { reflection ?: ReflectionHandles.resolve().also { reflection = it } }.getOrNull()
        if (handles != null) {
            registeredRuntimeLevels.forEach { (levelKey, registered) ->
                val level = server.getLevel(levelKey) ?: return@forEach
                suppressRuntimeWorldgen(level, handles, registered, "server-tick")
            }
        }

        if (pendingRegistrations.isEmpty()) {
            return
        }

        pendingRegistrations.keys.toList().forEach { levelKey ->
            val level = server.getLevel(levelKey)
            if (level == null) {
                logger.info(
                    "Dropping pending Distant Horizons runtime override registration level={} reason={} detail={} attempts={} because the level is no longer loaded",
                    levelKey.location(),
                    pendingRegistrations[levelKey]?.reason ?: "<unknown>",
                    pendingRegistrations[levelKey]?.detail ?: "<unknown>",
                    pendingRegistrations[levelKey]?.attempts ?: 0
                )
                pendingRegistrations.remove(levelKey)
                unregisterRuntimeLevel(levelKey, "pending-level-missing")
                return@forEach
            }

            val pending = pendingRegistrations[levelKey] ?: return@forEach
            logger.info(
                "Retrying Distant Horizons runtime override registration level={} reason={} detail={} attempts={}",
                levelKey.location(),
                pending.reason,
                pending.detail,
                pending.attempts
            )
            ensureRuntimeWorldgenOverride(server, level, "retry:${pending.reason}")
        }
    }

    fun unregisterRuntimeLevel(level: ServerLevel, reason: String) {
        unregisterRuntimeLevel(level.dimension(), reason)
    }

    fun unregisterRuntimeLevel(levelKey: ResourceKey<Level>, reason: String) {
        pendingRegistrations.remove(levelKey)
        val registered = registeredRuntimeLevels.remove(levelKey) ?: return
        val handles = runCatching { reflection ?: ReflectionHandles.resolve().also { reflection = it } }
            .getOrElse { throwable ->
                logger.warn(
                    "Failed to resolve Distant Horizons runtime compat hooks while unregistering level={} reason={}",
                    levelKey.location(),
                    reason,
                    throwable
                )
                return
            }
        removeRegisteredOverride(handles, levelKey, registered, reason)
    }

    fun unloadRuntimeLevel(level: ServerLevel, reason: String) {
        if (!ModList.get().isLoaded(MOD_ID) || !isRuntimeLevel(level.dimension())) {
            return
        }

        val handles = runCatching { reflection ?: ReflectionHandles.resolve().also { reflection = it } }
            .getOrElse { throwable ->
                logger.warn(
                    "Failed to resolve Distant Horizons runtime compat hooks while unloading level={} reason={}",
                    level.dimension().location(),
                    reason,
                    throwable
                )
                return
            }

        val wrapper = registeredRuntimeLevels[level.dimension()]?.wrapper ?: runCatching {
            handles.serverLevelWrapperGetWrapper.invoke(null, level)
        }.getOrNull() ?: return

        val dhWorld = runCatching { handles.sharedApiGetAbstractDhWorld.invoke(null) }.getOrNull() ?: return
        if (!handles.abstractDhServerWorldClass.isInstance(dhWorld)) {
            return
        }

        val dhLevelBeforeUnload = runCatching {
            handles.abstractDhServerWorldGetLevel.invoke(dhWorld, wrapper)
        }.getOrNull()
        if (dhLevelBeforeUnload == null) {
            return
        }

        val queuedChunkUpdates = runCatching {
            val sharedApi = handles.sharedApiStaticInstance.get(null)
            handles.sharedApiGetQueuedChunkUpdateCount.invoke(sharedApi) as? Int ?: -1
        }.getOrDefault(-1)

        runCatching {
            handles.serverApiServerLevelUnloadEvent.invoke(handles.serverApiInstance, wrapper)
        }.onFailure { throwable ->
            logger.warn(
                "Failed to unload Distant Horizons runtime level level={} reason={}",
                level.dimension().location(),
                reason,
                throwable
            )
            return
        }

        logger.info(
            "Unloaded Distant Horizons runtime level level={} reason={} queuedChunkUpdatesBefore={} registeredRuntimeLevels={}",
            level.dimension().location(),
            reason,
            queuedChunkUpdates,
            registeredRuntimeLevels.size
        )
    }

    fun reset() {
        registeredRuntimeLevels.clear()
        pendingRegistrations.clear()
        suppressionLoggedAt.clear()
    }

    private fun removeRegisteredOverride(
        handles: ReflectionHandles,
        levelKey: ResourceKey<Level>,
        registered: RegisteredRuntimeLevel,
        reason: String
    ) {
        val removed = runCatching {
            @Suppress("UNCHECKED_CAST")
            val overrides = handles.worldGeneratorByLevelWrapperField.get(handles.worldGeneratorInjectorInstance) as MutableMap<Any, Any>
            overrides.remove(registered.wrapper) != null
        }.getOrElse { throwable ->
            logger.warn(
                "Failed to remove Distant Horizons runtime worldgen override level={} reason={} dhIdentifier={} dimensionName={}",
                levelKey.location(),
                reason,
                registered.dhIdentifier,
                registered.dimensionName,
                throwable
            )
            return
        }

        logger.info(
            "Removed Distant Horizons runtime worldgen override level={} reason={} dhIdentifier={} dimensionName={} removed={}",
            levelKey.location(),
            reason,
            registered.dhIdentifier,
            registered.dimensionName,
            removed
        )
    }

    private fun markPending(levelKey: ResourceKey<Level>, reason: String, detail: String) {
        val current = pendingRegistrations[levelKey]
        pendingRegistrations[levelKey] = PendingRegistration(
            reason = reason,
            detail = detail,
            attempts = (current?.attempts ?: 0) + 1
        )
    }

    private fun suppressRuntimeWorldgen(
        level: ServerLevel,
        handles: ReflectionHandles,
        registered: RegisteredRuntimeLevel,
        reason: String
    ) {
        val dhWorld = runCatching { handles.sharedApiGetAbstractDhWorld.invoke(null) }.getOrNull() ?: return
        if (!handles.abstractDhServerWorldClass.isInstance(dhWorld)) {
            return
        }

        val dhLevel = runCatching { handles.abstractDhServerWorldGetLevel.invoke(dhWorld, registered.wrapper) }.getOrNull() ?: return

        @Suppress("UNCHECKED_CAST")
        val playerQueue = runCatching {
            handles.worldGenPlayerCenteringQueueField.get(dhLevel) as MutableCollection<Any>
        }.getOrElse { throwable ->
            logger.warn(
                "Failed to inspect Distant Horizons runtime player queue level={} reason={}",
                level.dimension().location(),
                reason,
                throwable
            )
            return
        }

        val queuedPlayers = playerQueue.size
        if (queuedPlayers > 0) {
            playerQueue.clear()
        }

        val worldGenRunning = runCatching {
            val serverside = handles.dhServerLevelServersideField.get(dhLevel)
            val lodRequestModule = handles.serverLevelModuleLodRequestModuleField.get(serverside)
            handles.lodRequestModuleIsWorldGenRunning.invoke(lodRequestModule) as? Boolean ?: false
        }.getOrDefault(false)

        if (queuedPlayers <= 0 && !worldGenRunning) {
            return
        }

        val now = level.server.overworld().gameTime
        val previousLoggedAt = suppressionLoggedAt[level.dimension()]
        if (previousLoggedAt == null || now - previousLoggedAt >= SUPPRESSION_LOG_INTERVAL_TICKS) {
            logger.info(
                "Suppressed Distant Horizons runtime worldgen level={} reason={} queuedPlayersCleared={} worldGenRunning={} interceptedCalls={} lifecycleCalls={}",
                level.dimension().location(),
                reason,
                queuedPlayers,
                worldGenRunning,
                registered.generatorStats.interceptedCalls.get(),
                registered.generatorStats.lifecycleCalls.get()
            )
            suppressionLoggedAt[level.dimension()] = now
        }
    }

    private fun createNoOpWorldGenerator(handles: ReflectionHandles, levelKey: ResourceKey<Level>, stats: GeneratorStats): Any {
        val levelId = levelKey.location().toString()
        return Proxy.newProxyInstance(
            handles.worldGeneratorInterface.classLoader,
            arrayOf(handles.worldGeneratorInterface)
        ) { proxy, method, args ->
            when (method.name) {
                "generateChunks", "generateApiChunks", "generateLod" -> {
                    val callCount = stats.interceptedCalls.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val previousLoggedAt = stats.lastGeneratorLogAtMillis.get()
                    if (callCount <= 2 || now - previousLoggedAt >= GENERATOR_LOG_INTERVAL_MILLIS) {
                        stats.lastGeneratorLogAtMillis.set(now)
                        logger.info(
                            "Intercepted Distant Horizons runtime worldgen level={} method={} call={} args={}",
                            levelId,
                            method.name,
                            callCount,
                            describeGeneratorArgs(args)
                        )
                    }
                    CompletableFuture.completedFuture<Void>(null)
                }
                "getReturnType" -> handles.apiDataSourcesReturnType
                "getSmallestDataDetailLevel", "getLargestDataDetailLevel" -> 0.toByte()
                "runApiValidation", "getDelayedSetupComplete" -> true
                "getPriority" -> Int.MAX_VALUE
                "preGeneratorTaskStart", "close", "finishDelayedSetup" -> {
                    val lifecycleCount = stats.lifecycleCalls.incrementAndGet()
                    val now = System.currentTimeMillis()
                    val previousLoggedAt = stats.lastLifecycleLogAtMillis.get()
                    if (lifecycleCount <= 2 || now - previousLoggedAt >= GENERATOR_LOG_INTERVAL_MILLIS) {
                        stats.lastLifecycleLogAtMillis.set(now)
                        logger.info(
                            "Distant Horizons runtime worldgen lifecycle level={} method={} call={}",
                            levelId,
                            method.name,
                            lifecycleCount
                        )
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "InstancedDimensionsDhNoOpWorldGenerator(level=$levelId)"
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun describeGeneratorArgs(args: Array<Any?>?): String {
        if (args == null) {
            return "[]"
        }
        return args.joinToString(prefix = "[", postfix = "]") { value ->
            when (value) {
                null -> "null"
                is Byte -> value.toString()
                is Number -> value.toString()
                else -> value.toString()
            }
        }
    }

    private fun defaultValue(returnType: Class<*>): Any? {
        return when {
            returnType == java.lang.Boolean.TYPE -> false
            returnType == java.lang.Byte.TYPE -> 0.toByte()
            returnType == java.lang.Short.TYPE -> 0.toShort()
            returnType == java.lang.Integer.TYPE -> 0
            returnType == java.lang.Long.TYPE -> 0L
            returnType == java.lang.Float.TYPE -> 0.0F
            returnType == java.lang.Double.TYPE -> 0.0
            returnType == java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }

    private fun isRuntimeLevel(levelKey: ResourceKey<Level>): Boolean {
        return levelKey.location().toString().startsWith(RUNTIME_LEVEL_PREFIX)
    }

    private data class RegisteredRuntimeLevel(
        val wrapper: Any,
        val generator: Any,
        val dhIdentifier: String,
        val dimensionName: String,
        val generatorStats: GeneratorStats
    )

    private class GeneratorStats {
        val interceptedCalls = AtomicInteger()
        val lifecycleCalls = AtomicInteger()
        val lastGeneratorLogAtMillis = AtomicLong(Long.MIN_VALUE)
        val lastLifecycleLogAtMillis = AtomicLong(Long.MIN_VALUE)
    }

    private data class PendingRegistration(
        val reason: String,
        val detail: String,
        val attempts: Int
    )

    private data class ReflectionHandles(
        val worldGenOverrides: Any,
        val registerWorldGeneratorOverride: Method,
        val serverLevelWrapperGetWrapper: Method,
        val getDhIdentifier: Method,
        val getDimensionName: Method,
        val worldGeneratorInterface: Class<*>,
        val apiDataSourcesReturnType: Any,
        val resultSuccessField: Field,
        val resultMessageField: Field,
        val worldGeneratorInjectorInstance: Any,
        val worldGeneratorByLevelWrapperField: Field,
        val sharedApiGetAbstractDhWorld: Method,
        val abstractDhServerWorldClass: Class<*>,
        val abstractDhServerWorldGetLevel: Method,
        val sharedApiStaticInstance: Field,
        val sharedApiGetQueuedChunkUpdateCount: Method,
        val serverApiInstance: Any,
        val serverApiServerLevelUnloadEvent: Method,
        val worldGenPlayerCenteringQueueField: Field,
        val dhServerLevelServersideField: Field,
        val serverLevelModuleLodRequestModuleField: Field,
        val lodRequestModuleIsWorldGenRunning: Method
    ) {
        companion object {
            fun resolve(): ReflectionHandles {
                val dhApiClass = Class.forName("com.seibel.distanthorizons.api.DhApi")
                val levelWrapperInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper")
                val worldGeneratorInterface = Class.forName("com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator")
                val worldGenOverrides = dhApiClass.getField("worldGenOverrides").get(null)
                val registerWorldGeneratorOverride = worldGenOverrides.javaClass.getMethod(
                    "registerWorldGeneratorOverride",
                    levelWrapperInterface,
                    worldGeneratorInterface
                )

                val serverLevelWrapperClass = sequenceOf(
                    "loaderCommon.forge.com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper",
                    "loaderCommon.fabric.com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper"
                ).mapNotNull { className ->
                    runCatching { Class.forName(className) }.getOrNull()
                }.firstOrNull() ?: error("Could not resolve Distant Horizons ServerLevelWrapper class")

                val serverLevelWrapperGetWrapper = serverLevelWrapperClass.getMethod("getWrapper", ServerLevel::class.java)
                val coreLevelWrapperClass = Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper")
                val getDhIdentifier = coreLevelWrapperClass.getMethod("getDhIdentifier")
                val getDimensionName = coreLevelWrapperClass.getMethod("getDimensionName")
                val apiDataSourcesReturnType = Class
                    .forName("com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType")
                    .getField("API_DATA_SOURCES")
                    .get(null)

                val dhApiResultClass = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult")
                val resultSuccessField = dhApiResultClass.getField("success")
                val resultMessageField = dhApiResultClass.getField("message")

                val worldGeneratorInjectorClass = Class.forName("com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector")
                val worldGeneratorInjectorInstance = worldGeneratorInjectorClass.getField("INSTANCE").get(null)
                val worldGeneratorByLevelWrapperField = worldGeneratorInjectorClass.getDeclaredField("worldGeneratorByLevelWrapper").apply {
                    isAccessible = true
                }

                val sharedApiGetAbstractDhWorld = Class
                    .forName("com.seibel.distanthorizons.core.api.internal.SharedApi")
                    .getMethod("getAbstractDhWorld")
                val sharedApiClass = Class.forName("com.seibel.distanthorizons.core.api.internal.SharedApi")
                val sharedApiStaticInstance = sharedApiClass.getField("INSTANCE")
                val sharedApiGetQueuedChunkUpdateCount = sharedApiClass.getMethod("getQueuedChunkUpdateCount")
                val serverApiClass = Class.forName("com.seibel.distanthorizons.core.api.internal.ServerApi")
                val serverApiInstance = serverApiClass.getField("INSTANCE").get(null)
                val abstractDhServerWorldClass = Class.forName("com.seibel.distanthorizons.core.world.AbstractDhServerWorld")
                val abstractDhServerWorldGetLevel = abstractDhServerWorldClass.getMethod("getLevel", coreLevelWrapperClass)
                val serverApiServerLevelUnloadEvent = serverApiClass.getMethod(
                    "serverLevelUnloadEvent",
                    Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper")
                )
                val abstractDhServerLevelClass = Class.forName("com.seibel.distanthorizons.core.level.AbstractDhServerLevel")
                val worldGenPlayerCenteringQueueField = abstractDhServerLevelClass.getDeclaredField("worldGenPlayerCenteringQueue").apply {
                    isAccessible = true
                }
                val dhServerLevelServersideField = abstractDhServerLevelClass.getField("serverside")
                val serverLevelModuleClass = Class.forName("com.seibel.distanthorizons.core.level.ServerLevelModule")
                val serverLevelModuleLodRequestModuleField = serverLevelModuleClass.getField("lodRequestModule")
                val lodRequestModuleIsWorldGenRunning = Class
                    .forName("com.seibel.distanthorizons.core.level.LodRequestModule")
                    .getMethod("isWorldGenRunning")

                return ReflectionHandles(
                    worldGenOverrides = worldGenOverrides,
                    registerWorldGeneratorOverride = registerWorldGeneratorOverride,
                    serverLevelWrapperGetWrapper = serverLevelWrapperGetWrapper,
                    getDhIdentifier = getDhIdentifier,
                    getDimensionName = getDimensionName,
                    worldGeneratorInterface = worldGeneratorInterface,
                    apiDataSourcesReturnType = apiDataSourcesReturnType,
                    resultSuccessField = resultSuccessField,
                    resultMessageField = resultMessageField,
                    worldGeneratorInjectorInstance = worldGeneratorInjectorInstance,
                    worldGeneratorByLevelWrapperField = worldGeneratorByLevelWrapperField,
                    sharedApiGetAbstractDhWorld = sharedApiGetAbstractDhWorld,
                    abstractDhServerWorldClass = abstractDhServerWorldClass,
                    abstractDhServerWorldGetLevel = abstractDhServerWorldGetLevel,
                    sharedApiStaticInstance = sharedApiStaticInstance,
                    sharedApiGetQueuedChunkUpdateCount = sharedApiGetQueuedChunkUpdateCount,
                    serverApiInstance = serverApiInstance,
                    serverApiServerLevelUnloadEvent = serverApiServerLevelUnloadEvent,
                    worldGenPlayerCenteringQueueField = worldGenPlayerCenteringQueueField,
                    dhServerLevelServersideField = dhServerLevelServersideField,
                    serverLevelModuleLodRequestModuleField = serverLevelModuleLodRequestModuleField,
                    lodRequestModuleIsWorldGenRunning = lodRequestModuleIsWorldGenRunning
                )
            }
        }
    }
}
