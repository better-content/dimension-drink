package dev.yourname.dimensiondrink

import com.mojang.logging.LogUtils
import dev.yourname.dimensiondrink.data.ObeliskDataManager
import dev.yourname.dimensiondrink.commands.ObeliskCommands
import dev.yourname.dimensiondrink.gametest.ObeliskGameTestRegistrar
import dev.yourname.dimensiondrink.integration.tcon.TConAffixRewards
import dev.yourname.dimensiondrink.registry.ModRegistries
import dev.yourname.dimensiondrink.runtime.combat.RunCombatTracker
import dev.yourname.dimensiondrink.runtime.energy.FERegenerationHandler
import dev.yourname.dimensiondrink.runtime.player.PlayerReturnHandler
import dev.yourname.dimensiondrink.runtime.player.VanillaPortalBlocker
import dev.yourname.dimensiondrink.runtime.reward.RewardSystem
import dev.yourname.dimensiondrink.runtime.run.RunRegistry
import dev.yourname.dimensiondrink.runtime.ui.RunBossBarManager
import dev.yourname.dimensiondrink.worldgen.village.VillageShrinePools
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
        MinecraftForge.EVENT_BUS.register(TConAffixRewards)
        MinecraftForge.EVENT_BUS.register(RunRegistry)
        MinecraftForge.EVENT_BUS.register(RunBossBarManager)
        MinecraftForge.EVENT_BUS.register(ObeliskCommands)
        MinecraftForge.EVENT_BUS.register(VillageShrinePools)
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()
    }
}
