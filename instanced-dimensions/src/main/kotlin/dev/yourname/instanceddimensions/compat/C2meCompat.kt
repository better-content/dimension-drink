package dev.yourname.instanceddimensions.compat

import net.minecraftforge.fml.ModList

/**
 * Centralizes C2MEF-sensitive lifecycle policy so runtime-world teardown can
 * adapt without scattering hard-coded mod checks through engine code.
 */
object C2meCompat {

    private const val C2ME_MOD_ID = "c2me"

    fun isLoaded(): Boolean = ModList.get().isLoaded(C2ME_MOD_ID)

    fun unloadDrainTicks(): Long = if (isLoaded()) 4L else 2L

    fun warmupTicketTicks(): Long = if (isLoaded()) 30L else 20L

    fun teardownProgressBudgetPerTick(): Int = if (isLoaded()) 2 else 1

    fun chunkDrainIterations(closing: Boolean): Int = when {
        isLoaded() && closing -> 8
        isLoaded() -> 4
        closing -> 4
        else -> 2
    }

    fun chunkTaskPollLimit(closing: Boolean): Int = when {
        isLoaded() && closing -> 1024
        isLoaded() -> 256
        closing -> 256
        else -> 64
    }

    fun closeGracePasses(): Int = if (isLoaded()) 2 else 1

    fun profileName(): String = if (isLoaded()) "c2me-aware" else "vanilla"
}
