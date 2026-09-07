package com.bettercontent.dimensiondrink.worldgen

import com.bettercontent.dimensiondrink.data.ObeliskDefinition
import net.minecraft.world.level.ChunkPos
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontSelectorTest {
    private val equalDefinitions = listOf("aether", "bumblezone", "nether", "ratlantis").map { id ->
        ObeliskDefinition(id = id, displayName = id, worldgenWeight = 1.0)
    }

    @Test
    fun selectionIsSortedWeightedAndRejectsInvalidWeights() {
        val definitions = listOf(
            ObeliskDefinition("z", "z", worldgenWeight = 3.0),
            ObeliskDefinition("disabled", "disabled", enabled = false, worldgenWeight = 100.0),
            ObeliskDefinition("nan", "nan", worldgenWeight = Double.NaN),
            ObeliskDefinition("zero", "zero", worldgenWeight = 0.0),
            ObeliskDefinition("a", "a", worldgenWeight = 1.0)
        )
        assertEquals("a", FontSelector.select(definitions, 0.0)?.id)
        assertEquals("z", FontSelector.select(definitions, 0.26)?.id)
        assertEquals("z", FontSelector.select(definitions, 1.0)?.id)
        assertNull(FontSelector.select(definitions.filter { it.worldgenWeight <= 0.0 }, 0.5))
    }

    @Test
    fun layoutAndDefinitionSeedsUseIndependentDomains() {
        val chunk = ChunkPos(123, -456)
        assertEquals(FontSelector.layoutSeed(9988L, chunk), FontSelector.layoutSeed(9988L, chunk))
        assertNotEquals(FontSelector.layoutSeed(9988L, chunk), FontSelector.definitionSeed(9988L, chunk))
    }

    @Test
    fun normalizationRemainsFiniteForVeryLargeWeights() {
        val definitions = listOf(
            ObeliskDefinition("a", "a", worldgenWeight = Double.MAX_VALUE),
            ObeliskDefinition("b", "b", worldgenWeight = Double.MAX_VALUE)
        )
        assertEquals(mapOf("a" to 0.5, "b" to 0.5), FontSelector.normalizedWeights(definitions))
        assertEquals("b", FontSelector.select(definitions, 0.75)?.id)
    }

    @Test
    fun millionCandidateCorpusStaysEvenBeforeAndAfterIndependentOpportunityRejection() {
        val sampleCount = 1_048_576
        val candidateCounts = equalDefinitions.associate { it.id to 0 }.toMutableMap()
        val acceptedCounts = equalDefinitions.associate { it.id to 0 }.toMutableMap()
        var acceptedTotal = 0
        repeat(sampleCount) { index ->
            val worldSeed = index.toLong() * -7046029254386353131L
            val chunk = ChunkPos(index and 2047, (index ushr 11) - 256)
            val definitionSeed = FontSelector.definitionSeed(worldSeed, chunk)
            val definition = requireNotNull(FontSelector.select(equalDefinitions, unitSample(definitionSeed)))
            candidateCounts.compute(definition.id) { _, count -> requireNotNull(count) + 1 }

            // Synthetic terrain opportunity is deliberately based only on the layout domain.
            val accepted = unitSample(FontSelector.layoutSeed(worldSeed, chunk)) >= 0.30
            if (accepted) {
                acceptedCounts.compute(definition.id) { _, count -> requireNotNull(count) + 1 }
                acceptedTotal++
            }
        }

        candidateCounts.values.forEach { count ->
            assertTrue(abs(count.toDouble() / sampleCount - 0.25) <= 0.005, "candidate counts=$candidateCounts")
        }
        acceptedCounts.values.forEach { count ->
            assertTrue(abs(count.toDouble() / acceptedTotal - 0.25) <= 0.015, "accepted counts=$acceptedCounts")
        }
    }

    private fun unitSample(seed: Long): Double = (seed ushr 11).toDouble() / 9_007_199_254_740_992.0
}
