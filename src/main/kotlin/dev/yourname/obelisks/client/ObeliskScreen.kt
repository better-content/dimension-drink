package dev.yourname.obelisks.client

import com.mojang.blaze3d.systems.RenderSystem
import dev.yourname.obelisks.ObelisksConstants
import dev.yourname.obelisks.content.ObeliskMenu
import dev.yourname.obelisks.network.ModNetwork
import dev.yourname.obelisks.network.TeleportButtonPacket
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

/**
 * Client-side GUI screen for the Obelisk.
 * Displays FE statistics and a teleport button.
 */
class ObeliskScreen(
    menu: ObeliskMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<ObeliskMenu>(menu, playerInventory, title) {

    companion object {
        // Note: Not using TEXTURE anymore, rendering programmatically
        private const val GUI_WIDTH = 176
        private const val GUI_HEIGHT = 166
    }

    private var teleportButton: Button? = null

    init {
        this.imageWidth = GUI_WIDTH
        this.imageHeight = GUI_HEIGHT
    }

    override fun init() {
        super.init()

        val buttonX = leftPos + (imageWidth - 100) / 2
        val buttonY = topPos + 120

        teleportButton = Button.builder(
            Component.literal("Teleport"),
            Button.OnPress { onTeleportPressed() }
        )
            .bounds(buttonX, buttonY, 100, 20)
            .build()

        addRenderableWidget(teleportButton!!)
    }

    override fun containerTick() {
        super.containerTick()

        // Check if joining existing run vs starting new
        val hasActiveRun = menu.hasActiveRun()
        val feStored = menu.getFEStored()
        val maxFE = menu.getMaxFE()
        val fePercent = if (maxFE > 0) (feStored.toDouble() / maxFE * 100).toInt() else 0
        val cooldownRemaining = menu.getCooldownRemaining()

        // Update button state
        when {
            cooldownRemaining > 0 -> {
                // Obelisk on cooldown
                teleportButton?.active = false
                teleportButton?.message = Component.literal("Cooldown: ${cooldownRemaining}s")
            }
            hasActiveRun -> {
                // Joining existing run - always allowed
                teleportButton?.active = true
                teleportButton?.message = Component.literal("Join Run")
            }
            fePercent < 100 -> {
                // Starting new run but not fully charged
                teleportButton?.active = false
                teleportButton?.message = Component.literal("Charging: $fePercent%")
            }
            else -> {
                // Ready to start new run
                teleportButton?.active = true
                teleportButton?.message = Component.literal("Start Run")
            }
        }
    }

    private fun onTeleportPressed() {
        // Send packet to server to initiate teleport
        ModNetwork.sendToServer(TeleportButtonPacket(menu.getObeliskPos()))
        onClose()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(guiGraphics)
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        renderTooltip(guiGraphics, mouseX, mouseY)
    }

    override fun renderBg(guiGraphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        // Simple grey background for now (GUI is WIP and will change a lot)
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFC6C6C6.toInt())

        // Dark border
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF373737.toInt()) // Top
        guiGraphics.fill(leftPos, topPos + imageHeight - 2, leftPos + imageWidth, topPos + imageHeight, 0xFF373737.toInt()) // Bottom
        guiGraphics.fill(leftPos, topPos, leftPos + 2, topPos + imageHeight, 0xFF373737.toInt()) // Left
        guiGraphics.fill(leftPos + imageWidth - 2, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF373737.toInt()) // Right
    }

    override fun renderLabels(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        // Title
        guiGraphics.drawString(font, title, 8, 6, 0x404040, false)

        // FE Statistics
        val feStored = menu.getFEStored()
        val maxFE = menu.getMaxFE()
        val fePercent = if (maxFE > 0) (feStored.toDouble() / maxFE.toDouble() * 100).toInt() else 0

        val yStart = 25

        // FE Storage
        guiGraphics.drawString(
            font,
            "Energy: $feStored / $maxFE FE",
            8,
            yStart,
            0x404040,
            false
        )

        // FE Percentage bar
        val barWidth = 160
        val barHeight = 10
        val barX = 8
        val barY = yStart + 12

        // Background (empty bar)
        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF8B8B8B.toInt())

        // Foreground (filled bar)
        val fillWidth = (barWidth * fePercent / 100).coerceIn(0, barWidth)
        val color = when {
            fePercent > 60 -> 0xFF00FF00.toInt() // Green
            fePercent > 30 -> 0xFFFFFF00.toInt() // Yellow
            else -> 0xFFFF0000.toInt() // Red
        }
        guiGraphics.fill(barX, barY, barX + fillWidth, barY + barHeight, color)

        // Percentage text
        guiGraphics.drawString(
            font,
            "$fePercent%",
            barX + barWidth / 2 - font.width("$fePercent%") / 2,
            barY + 1,
            0xFFFFFF,
            false
        )

        // Dimension Type - use display name if available, otherwise fall back to dimensionId
        val dimensionName = menu.getObeliskBlockEntity()?.dimensionDisplayName
            ?: menu.getObeliskBlockEntity()?.targetDimensionId
            ?: "Unknown"

        guiGraphics.drawString(
            font,
            "Dimension: $dimensionName",
            8,
            yStart + 28,
            0x404040,
            false
        )

        // Active Run Status
        val hasActiveRun = menu.hasActiveRun()
        val statusText = if (hasActiveRun) "Status: Active Run" else "Status: Ready"
        val statusColor = if (hasActiveRun) 0xFF0000 else 0x00FF00

        guiGraphics.drawString(
            font,
            statusText,
            8,
            yStart + 40,
            statusColor,
            false
        )

        // FE Regeneration Rate (modified by obelisk modifiers)
        val regenPerTick = menu.getRegenRate()
        val regenPerSecond = regenPerTick * ObelisksConstants.TICKS_PER_SECOND
        guiGraphics.drawString(
            font,
            "Regen: +$regenPerSecond FE/s ($regenPerTick/t)",
            8,
            yStart + 52,
            0x00AA00, // Dark green
            false
        )

        // FE Drain Rate (modified by obelisk modifiers + multiplier if active)
        val drainMultiplier = menu.getDrainMultiplier()
        val modifiedBaseDrain = menu.getBaseDrain()
        val modifiedPlayerDrain = menu.getPlayerDrain()
        val baseDrainRate = modifiedBaseDrain + modifiedPlayerDrain
        val actualDrainRate = (baseDrainRate * drainMultiplier).toInt()
        val drainPerSecond = actualDrainRate * ObelisksConstants.TICKS_PER_SECOND

        val drainText = if (hasActiveRun && drainMultiplier > 1.01) {
            "Drain: -$drainPerSecond FE/s (${String.format("%.2f", drainMultiplier)}x)"
        } else {
            "Drain: -${baseDrainRate * ObelisksConstants.TICKS_PER_SECOND} FE/s per player"
        }
        guiGraphics.drawString(
            font,
            drainText,
            8,
            yStart + 64,
            0xAA0000, // Dark red
            false
        )

        // Estimated Runtime
        if (hasActiveRun && feStored > 0 && actualDrainRate > 0) {
            val runtimeTicks = feStored / actualDrainRate
            val runtimeSeconds = runtimeTicks / ObelisksConstants.TICKS_PER_SECOND
            guiGraphics.drawString(
                font,
                "Est. Runtime: ${runtimeSeconds}s (current rate)",
                8,
                yStart + 76,
                0x404040,
                false
            )
        }
    }
}
