package com.bettercontent.dimensiondrink.worldgen

import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import kotlin.random.Random

/** Canonical deterministic selector for every weighted dimensional-font choice. */
object FontSelector {
    private const val LAYOUT_DOMAIN = 0x4f1bbcdc2d6a5f3bL
    private const val DEFINITION_DOMAIN = -0x2d0d4f2b17af39c1L

    fun eligible(definitions: Iterable<ObeliskDefinition>): List<ObeliskDefinition> = definitions
        .filter { it.enabled && it.worldgenWeight.isFinite() && it.worldgenWeight > 0.0 }
        .sortedBy(ObeliskDefinition::id)

    fun select(definitions: Iterable<ObeliskDefinition>, unitSample: Double): ObeliskDefinition? {
        val eligible = eligible(definitions)
        if (eligible.isEmpty()) return null
        val scale = eligible.maxOf(ObeliskDefinition::worldgenWeight)
        val total = eligible.sumOf { it.worldgenWeight / scale }
        var cursor = unitSample.coerceIn(0.0, Math.nextDown(1.0)) * total
        for (definition in eligible) {
            cursor -= definition.worldgenWeight / scale
            if (cursor < 0.0) return definition
        }
        return eligible.last()
    }

    fun select(definitions: Iterable<ObeliskDefinition>, random: RandomSource): ObeliskDefinition? =
        select(definitions, random.nextDouble())

    fun select(definitions: Iterable<ObeliskDefinition>, random: Random): ObeliskDefinition? =
        select(definitions, random.nextDouble())

    fun normalizedWeights(definitions: Iterable<ObeliskDefinition>): Map<String, Double> {
        val eligible = eligible(definitions)
        if (eligible.isEmpty()) return emptyMap()
        val scale = eligible.maxOf(ObeliskDefinition::worldgenWeight)
        val scaledTotal = eligible.sumOf { it.worldgenWeight / scale }
        return eligible.associate { it.id to (it.worldgenWeight / scale / scaledTotal) }
    }

    fun layoutSeed(worldSeed: Long, chunk: ChunkPos): Long = domainSeed(worldSeed, chunk, LAYOUT_DOMAIN)

    fun definitionSeed(worldSeed: Long, chunk: ChunkPos): Long = domainSeed(worldSeed, chunk, DEFINITION_DOMAIN)

    internal fun domainSeed(worldSeed: Long, chunkX: Int, chunkZ: Int, domain: Long): Long =
        domainSeed(worldSeed, ChunkPos(chunkX, chunkZ), domain)

    private fun domainSeed(worldSeed: Long, chunk: ChunkPos, domain: Long): Long {
        var value = worldSeed xor domain
        value = value xor (chunk.x.toLong() * -7046029254386353131L)
        value = value xor (chunk.z.toLong() * -4658895280553007687L)
        value = value xor (value ushr 30)
        value *= -4658895280553007687L
        value = value xor (value ushr 27)
        value *= -7723592293110705685L
        return value xor (value ushr 31)
    }
}
