package dev.yourname.instanceddimensions.compat

import com.mojang.logging.LogUtils
import net.minecraft.server.level.ServerLevel
import net.minecraftforge.fml.ModList
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Better Caves injects carvers by biome tag, so runtime copies of overworld-like
 * biomes can inherit carvers whose Y ranges are invalid for the copied dimension.
 *
 * We avoid pack-wide biome edits by pre-seeding runtime levels with an empty
 * Better Caves controller. Better Caves then treats the level as already
 * initialized and skips its own unsafe controller creation path for that level.
 */
object BetterCavesCompat {

    private const val MOD_ID = "bettercaves"

    private val logger = LogUtils.getLogger()

    @Volatile
    private var reflection: ReflectionHandles? = null

    fun installRuntimeNoOpController(level: ServerLevel) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return
        }

        val handles = runCatching { reflection ?: ReflectionHandles.resolve().also { reflection = it } }
            .getOrElse { throwable ->
                logger.warn(
                    "Failed to resolve Better Caves runtime compat hooks for level={} minY={} height={}",
                    level.dimension().location(),
                    level.minBuildHeight,
                    level.height,
                    throwable
                )
                return
            }

        runCatching {
            val existing = handles.getMasterController.invoke(level)
            if (existing != null) {
                logger.info(
                    "Better Caves controller already present for runtime level={} minY={} height={}",
                    level.dimension().location(),
                    level.minBuildHeight,
                    level.height
                )
                return
            }

            val misc = handles.miscSettingsCtor.newInstance(handles.emptyHolderSet(), false)
            val debug = handles.defaultDebugSettings.get(null)
            val config = handles.configCtor.newInstance(
                emptyList<Any>(),
                emptyList<Any>(),
                misc,
                debug
            )
            val controller = handles.masterControllerCtor.newInstance(level, config)
            handles.setMasterController.invoke(level, controller)
            logger.info(
                "Installed no-op Better Caves controller for runtime level={} minY={} height={} logicalHeight={}",
                level.dimension().location(),
                level.minBuildHeight,
                level.height,
                level.logicalHeight
            )
        }.onFailure { throwable ->
            logger.warn(
                "Failed to install Better Caves runtime compat controller for level={} minY={} height={}",
                level.dimension().location(),
                level.minBuildHeight,
                level.height,
                throwable
            )
        }
    }

    private data class ReflectionHandles(
        val getMasterController: Method,
        val setMasterController: Method,
        val masterControllerCtor: Constructor<*>,
        val configCtor: Constructor<*>,
        val miscSettingsCtor: Constructor<*>,
        val defaultDebugSettings: Field,
        val holderSetFactory: Method
    ) {
        fun emptyHolderSet(): Any = holderSetFactory.invoke(null, Collections.emptyList<Any>())

        companion object {
            fun resolve(): ReflectionHandles {
                val providerClass = Class.forName("com.yungnickyoung.minecraft.bettercaves.duck.IMasterControllerProvider")
                val masterControllerClass =
                    Class.forName("com.yungnickyoung.minecraft.bettercaves.worldgen.controller.MasterController")
                val configClass =
                    Class.forName("com.yungnickyoung.minecraft.bettercaves.worldgen.BetterCavesWorldCarverConfig")
                val miscSettingsClass =
                    Class.forName("com.yungnickyoung.minecraft.bettercaves.worldgen.BetterCavesWorldCarverConfig\$MiscSettings")
                val debugSettingsClass =
                    Class.forName("com.yungnickyoung.minecraft.bettercaves.worldgen.BetterCavesWorldCarverConfig\$DebugSettings")
                val holderSetClass = Class.forName("net.minecraft.core.HolderSet")

                val getMasterController = providerClass.getMethod("getMasterController")
                val setMasterController = providerClass.getMethod("setMasterController", masterControllerClass)
                val masterControllerCtor = masterControllerClass.getConstructor(ServerLevel::class.java, configClass)
                val configCtor = configClass.getConstructor(
                    List::class.java,
                    List::class.java,
                    miscSettingsClass,
                    debugSettingsClass
                )
                val miscSettingsCtor = miscSettingsClass.getConstructor(holderSetClass, java.lang.Boolean.TYPE)
                val defaultDebugSettings = debugSettingsClass.getDeclaredField("DEFAULT").apply { isAccessible = true }
                val holderSetFactory = holderSetClass.declaredMethods.firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.size == 1 &&
                        List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        holderSetClass.isAssignableFrom(method.returnType)
                }?.apply {
                    isAccessible = true
                } ?: error("Could not resolve HolderSet list factory for Better Caves compat")

                return ReflectionHandles(
                    getMasterController = getMasterController,
                    setMasterController = setMasterController,
                    masterControllerCtor = masterControllerCtor,
                    configCtor = configCtor,
                    miscSettingsCtor = miscSettingsCtor,
                    defaultDebugSettings = defaultDebugSettings,
                    holderSetFactory = holderSetFactory
                )
            }
        }
    }
}
