package dev.yourname.obelisks

import dev.yourname.obelisks.dimension.DimensionTeardownHandler
import dev.yourname.obelisks.player.PlayerReturnHandler
import dev.yourname.obelisks.player.PlayerRunCapabilityProvider
import dev.yourname.obelisks.jaunt.RunEventHandlers
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext

/**
 * Mod entrypoint for the Obelisks mod.
 *
 * Responsibilities:
 * - Wire all DeferredRegister registries via [dev.yourname.obelisks.registry.ModRegistries]
 * - Register Forge event handlers required by the mod
 *
 * Command registration is handled separately in [dev.yourname.obelisks.commands.ObeliskCommands].
 */
@Suppress("DEPRECATION")
@Mod(MOD_ID)
class ObelisksMod {
    init {
        // Load configuration files FIRST
        dev.yourname.obelisks.config.ConfigManager.load()

        val modBus = FMLJavaModLoadingContext.get().modEventBus
        // Centralized registry wiring
        dev.yourname.obelisks.registry.ModRegistries.registerAll(modBus)

        // Register network packets
        dev.yourname.obelisks.network.ModNetwork.register()

        // Client-side setup is handled in dev.yourname.obelisks.client.ClientSetup

        // Register event handlers
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(PlayerRunCapabilityProvider.Companion)
        MinecraftForge.EVENT_BUS.register(PlayerReturnHandler)
        MinecraftForge.EVENT_BUS.register(DimensionTeardownHandler)
        MinecraftForge.EVENT_BUS.register(RunEventHandlers)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.dimension.MobDifficultyHandler)

        // Phase 3: FE System handlers
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.jaunt.InstanceTickHandler)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.jaunt.RunBossBarManager)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.dimension.DimensionCollapseHandler)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.jaunt.FERegenerationHandler)

        // Monster kill tracking for emerald rewards
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.jaunt.MonsterKillHandler)

        // Server lifecycle handler
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.server.ServerLifecycleHandler)

        // Effect limiter for VFX/SFX
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.util.EffectLimiterHandler)

        // Block vanilla portals (Nether/End) to enforce obelisk-only travel
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.portal.PortalBlocker)
    }

    // Command registration moved to ObeliskCommands
}
