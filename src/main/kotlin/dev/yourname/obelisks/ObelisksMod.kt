package dev.yourname.obelisks

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.yourname.obelisks.dimension.DimensionTeardownHandler
import dev.yourname.obelisks.player.PlayerReturnHandler
import dev.yourname.obelisks.player.PlayerRunCapabilityProvider
import dev.yourname.obelisks.player.getRunInfo
import dev.yourname.obelisks.registry.ModBlockEntities
import dev.yourname.obelisks.registry.ModBlocks
import dev.yourname.obelisks.registry.ModFeatures
import dev.yourname.obelisks.registry.ModItems
import dev.yourname.obelisks.run.RunEventHandlers
import dev.yourname.obelisks.run.RunManager
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.RegisterCommandsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
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
        val modBus = FMLJavaModLoadingContext.get().modEventBus
        // Centralized registry wiring
        dev.yourname.obelisks.registry.ModRegistries.registerAll(modBus)

        // Register event handlers
        MinecraftForge.EVENT_BUS.register(this)
        MinecraftForge.EVENT_BUS.register(PlayerRunCapabilityProvider.Companion)
        MinecraftForge.EVENT_BUS.register(PlayerReturnHandler)
        MinecraftForge.EVENT_BUS.register(DimensionTeardownHandler)
        MinecraftForge.EVENT_BUS.register(RunEventHandlers)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.dimension.SlotDimensionInitializer)
        
        // Phase 3: FE System handlers
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.run.InstanceTickHandler)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.run.RunBossBarManager)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.dimension.DimensionCollapseHandler)
        MinecraftForge.EVENT_BUS.register(dev.yourname.obelisks.run.FERegenerationHandler)
    }

    // Command registration moved to ObeliskCommands
}
