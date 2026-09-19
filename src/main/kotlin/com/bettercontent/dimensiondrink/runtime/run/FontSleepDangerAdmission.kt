package com.bettercontent.dimensiondrink.runtime.run

import com.bettercontent.bettercontentfixes.compat.sleeping.SleepDangerInterruption
import net.minecraft.server.level.ServerPlayer

/** Admits only a player still in an active Font window to the sleep-danger bridge. */
internal object FontSleepDangerAdmission {
    internal fun shouldInterrupt(activeSession: Boolean, stillInWindow: Boolean): Boolean =
        activeSession && stillInWindow

    internal fun interrupt(
        player: ServerPlayer?,
        activeSession: Boolean,
        stillInWindow: Boolean,
        interrupter: (ServerPlayer, SleepDangerInterruption.Reason) -> Boolean =
            { admittedPlayer, reason -> SleepDangerInterruption.interrupt(admittedPlayer, reason) }
    ): Boolean {
        if (player == null || !shouldInterrupt(activeSession, stillInWindow)) return false
        return interrupter(player, SleepDangerInterruption.Reason.FONT_WINDOW)
    }
}
