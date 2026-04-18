package dev.yourname.instanceddimensions

import com.mojang.logging.LogUtils
import dev.yourname.instanceddimensions.compat.C2meCompat
import dev.yourname.instanceddimensions.compat.RuntimeTravelOrchestrator
import dev.yourname.instanceddimensions.debug.InstanceBenchmarkRunner
import dev.yourname.instanceddimensions.engine.instance.InstanceManager
import dev.yourname.instanceddimensions.engine.levelsync.RuntimeLevelKeySyncManager
import dev.yourname.instanceddimensions.engine.travel.TravelManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod

@Mod(MOD_ID)
class InstancedDimensionsMod {

    init {
        LOGGER.info("Starting {}", MOD_NAME)
        LOGGER.info("Runtime instance lifecycle profile: {}", C2meCompat.profileName())
        RuntimeLevelKeySyncManager.init()
        MinecraftForge.EVENT_BUS.register(InstanceManager)
        MinecraftForge.EVENT_BUS.register(TravelManager)
        MinecraftForge.EVENT_BUS.register(RuntimeTravelOrchestrator)
        MinecraftForge.EVENT_BUS.register(InstanceBenchmarkRunner)
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()
    }
}
