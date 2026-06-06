package dev.yourname.obelisks

import com.mojang.logging.LogUtils
import dev.yourname.obelisks.data.ObeliskDataManager
import dev.yourname.obelisks.commands.ObeliskCommands
import dev.yourname.obelisks.gametest.ObeliskGameTestRegistrar
import dev.yourname.obelisks.integration.tcon.TConAffixRewards
import dev.yourname.obelisks.registry.ModRegistries
import dev.yourname.obelisks.runtime.combat.RunCombatTracker
import dev.yourname.obelisks.runtime.energy.FERegenerationHandler
import dev.yourname.obelisks.runtime.player.PlayerReturnHandler
import dev.yourname.obelisks.runtime.player.VanillaPortalBlocker
import dev.yourname.obelisks.runtime.reward.RewardSystem
import dev.yourname.obelisks.runtime.run.RunRegistry
import dev.yourname.obelisks.runtime.ui.RunBossBarManager
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

@Mod(MOD_ID)
class ObelisksMod {

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
    }

    companion object {
        private val LOGGER = LogUtils.getLogger()
    }
}
