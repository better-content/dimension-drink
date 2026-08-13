package com.bettercontent.dimensiondrink

import com.mojang.logging.LogUtils
import com.bettercontent.dimensiondrink.data.ObeliskDataManager
import com.bettercontent.dimensiondrink.commands.ObeliskCommands
import com.bettercontent.dimensiondrink.gametest.ObeliskGameTestRegistrar
import com.bettercontent.dimensiondrink.registry.ModRegistries
import com.bettercontent.dimensiondrink.runtime.combat.RunCombatTracker
import com.bettercontent.dimensiondrink.runtime.energy.FERegenerationHandler
import com.bettercontent.dimensiondrink.runtime.player.PlayerReturnHandler
import com.bettercontent.dimensiondrink.runtime.player.VanillaPortalBlocker
import com.bettercontent.dimensiondrink.runtime.reward.RewardSystem
import com.bettercontent.dimensiondrink.runtime.run.RunRegistry
import com.bettercontent.dimensiondrink.runtime.ui.RunBossBarManager
import com.bettercontent.dimensiondrink.worldgen.village.VillageShrinePools
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

@Mod(MOD_ID)
class DimensionDrinkMod {

    init {
        LOGGER.info("Starting {}", MOD_NAME)
        ObeliskDataManager.ensureLoaded()
        val modBus = FMLJavaModLoadingContext.get().modEventBus
        ModRegistries.registerAll(modBus)
        modBus.register(ObeliskGameTestRegistrar)
        MinecraftForge.EVENT_BUS.register(FERegenerationHandler)
        MinecraftForge.EVENT_BUS.register(RunCombatTracker)
        MinecraftForge.EVENT_BUS.register(PlayerReturnHandler)
        MinecraftForge.EVENT_BUS.register(VanillaPortalBlocker)
        MinecraftForge.EVENT_BUS.register(RewardSystem)
        MinecraftForge.EVENT_BUS.register(RunRegistry)
        MinecraftForge.EVENT_BUS.register(RunBossBarManager)
        MinecraftForge.EVENT_BUS.register(ObeliskCommands)
        MinecraftForge.EVENT_BUS.register(VillageShrinePools)
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()
    }
}
