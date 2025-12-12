package dev.yourname.obelisks.util

import dev.yourname.obelisks.ObelisksConstants

/**
 * Global rate limiter for visual and audio effects.
 * Prevents lag by capping the number of particles and sounds per tick.
 */
object EffectLimiter {
    private var particleCountThisTick = 0
    private var soundCountThisTick = 0
    private var currentTick = 0L

    /**
     * Reset counters at the start of each server tick.
     * Should be called from a server tick event.
     */
    fun onServerTick(tickNumber: Long) {
        if (tickNumber != currentTick) {
            currentTick = tickNumber
            particleCountThisTick = 0
            soundCountThisTick = 0
        }
    }

    /**
     * Try to spawn particles. Returns true if allowed, false if limit reached.
     * @param count Number of particles to spawn
     */
    fun trySpawnParticles(count: Int = 1): Boolean {
        if (particleCountThisTick + count > ObelisksConstants.MAX_PARTICLES_PER_TICK) {
            return false
        }
        particleCountThisTick += count
        return true
    }

    /**
     * Try to play sound. Returns true if allowed, false if limit reached.
     */
    fun tryPlaySound(): Boolean {
        if (soundCountThisTick >= ObelisksConstants.MAX_SOUNDS_PER_TICK) {
            return false
        }
        soundCountThisTick++
        return true
    }

    /**
     * Get current particle count for this tick (for debugging).
     */
    fun getParticleCount(): Int = particleCountThisTick

    /**
     * Get current sound count for this tick (for debugging).
     */
    fun getSoundCount(): Int = soundCountThisTick
}
