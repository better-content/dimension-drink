package dev.yourname.obelisks.integration.tcon

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.util.RandomSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TConAffixRewardsTest {
    @Test
    fun affixCountRollsUseWeightedPoeStyleBands() {
        assertEquals(6, TConAffixRewards.affixCountForRoll(0.0f))
        assertEquals(6, TConAffixRewards.affixCountForRoll(0.02999f))
        assertEquals(5, TConAffixRewards.affixCountForRoll(0.03f))
        assertEquals(5, TConAffixRewards.affixCountForRoll(0.09999f))
        assertEquals(4, TConAffixRewards.affixCountForRoll(0.10f))
        assertEquals(4, TConAffixRewards.affixCountForRoll(0.24999f))
        assertEquals(3, TConAffixRewards.affixCountForRoll(0.25f))
        assertEquals(3, TConAffixRewards.affixCountForRoll(0.49999f))
        assertEquals(2, TConAffixRewards.affixCountForRoll(0.50f))
        assertEquals(2, TConAffixRewards.affixCountForRoll(0.77999f))
        assertEquals(1, TConAffixRewards.affixCountForRoll(0.78f))
        assertEquals(1, TConAffixRewards.affixCountForRoll(0.99999f))
    }

    @Test
    fun affixPoolIsWeightedTieredAndHasUniqueIds() {
        assertTrue(TConAffixRewards.affixPool.size >= 20)

        val ids = TConAffixRewards.affixPool.map { it.id }
        assertEquals(ids.distinct(), ids)

        TConAffixRewards.affixPool.forEach { definition ->
            assertTrue(definition.id.isNotBlank())
            assertTrue(definition.name.isNotBlank(), definition.id)
            assertTrue(definition.group.isNotBlank(), definition.id)
            assertTrue(definition.weight > 0, definition.id)
            assertTrue(definition.families.isNotEmpty(), definition.id)
            assertTrue(definition.stats.isNotEmpty(), definition.id)
            definition.stats.forEach { line ->
                assertTrue(line.stat.startsWith("tconstruct:"), definition.id)
                assertTrue(line.scale > 0.0, definition.id)
            }
            definition.tiers.forEach { tier ->
                assertTrue(tier.rank in 1..5, definition.id)
                assertTrue(tier.name.isNotBlank(), definition.id)
                assertTrue(tier.minPercent > 0.0, definition.id)
                assertTrue(tier.maxPercent >= tier.minPercent, definition.id)
                assertTrue(tier.weight > 0, definition.id)
            }
            assertEquals(definition.tiers.map { it.rank }.distinct(), definition.tiers.map { it.rank }, definition.id)
        }
    }

    @Test
    fun everyPartFamilyHasPrefixAndSuffixCandidates() {
        enumValues<TConAffixRewards.PartFamily>().forEach { family ->
            val candidates = TConAffixRewards.affixPool.filter { it.allows(family) }

            assertTrue(candidates.any { it.kind == TConAffixRewards.AffixKind.PREFIX }, family.name)
            assertTrue(candidates.any { it.kind == TConAffixRewards.AffixKind.SUFFIX }, family.name)
        }
    }

    @Test
    fun rolledAffixesRespectFamilyCapsGroupsAndTierBounds() {
        enumValues<TConAffixRewards.PartFamily>().forEachIndexed { familyIndex, family ->
            repeat(80) { sample ->
                val affixes = TConAffixRewards.rollAffixes(
                    sourcePart = "test:${family.name.lowercase()}",
                    family = family,
                    random = RandomSource.create((familyIndex * 10_000L) + sample)
                )

                assertTrue(affixes.size in 1..6, family.name)
                assertTrue(affixes.count { it.getString("kind") == TConAffixRewards.AffixKind.PREFIX.id } <= 3, family.name)
                assertTrue(affixes.count { it.getString("kind") == TConAffixRewards.AffixKind.SUFFIX.id } <= 3, family.name)
                assertEquals(
                    affixes.map { it.getString("group") }.distinct(),
                    affixes.map { it.getString("group") },
                    family.name
                )

                affixes.forEach { rolled ->
                    val definition = assertNotNull(TConAffixRewards.definition(rolled.getString("id")))
                    val tier = assertNotNull(definition.tiers.firstOrNull { it.rank == rolled.getInt("tier") })

                    assertTrue(definition.allows(family), definition.id)
                    assertEquals(definition.kind.id, rolled.getString("kind"))
                    assertEquals(definition.group, rolled.getString("group"))
                    assertEquals(definition.name, rolled.getString("name"))
                    assertEquals(tier.name, rolled.getString("tier_name"))
                    assertEquals("test:${family.name.lowercase()}", rolled.getString("source_part"))
                    assertRollsMatchDefinition(rolled, definition, tier)
                }
            }
        }
    }

    @Test
    fun sixAffixRollsCanBeGeneratedWithoutViolatingPrefixSuffixCaps() {
        val seed = findSeedForAffixCount(6)

        val affixes = TConAffixRewards.rollAffixes(
            sourcePart = "tconstruct:pick_head",
            family = TConAffixRewards.PartFamily.TOOL_HEAD,
            random = RandomSource.create(seed)
        )

        assertEquals(6, affixes.size)
        assertEquals(3, affixes.count { it.getString("kind") == TConAffixRewards.AffixKind.PREFIX.id })
        assertEquals(3, affixes.count { it.getString("kind") == TConAffixRewards.AffixKind.SUFFIX.id })
        assertEquals(affixes.map { it.getString("group") }.distinct(), affixes.map { it.getString("group") })
    }

    @Test
    fun rolledAffixTagsContainTierMetadataAndSingleStatCompatibility() {
        val definition = assertNotNull(TConAffixRewards.definition("vital_temper"))
        val tier = definition.tiers.first { it.rank == 2 }
        val rolled = TConAffixRewards.createAffix(definition, tier, "tconstruct:small_blade", RandomSource.create(42L))

        assertEquals("vital_temper", rolled.getString("id"))
        assertEquals("Vital Temper", rolled.getString("name"))
        assertEquals("prefix", rolled.getString("kind"))
        assertEquals("damage", rolled.getString("group"))
        assertEquals("exalted", rolled.getString("tier_name"))
        assertEquals(2, rolled.getInt("tier"))
        assertEquals("tconstruct:small_blade", rolled.getString("source_part"))
        assertEquals("tconstruct:attack_damage", rolled.getString("stat"))
        assertTrue(rolled.contains("percent", Tag.TAG_DOUBLE.toInt()))
        assertRollsMatchDefinition(rolled, definition, tier)
    }

    @Test
    fun multiStatAffixesWriteRollListsInsteadOfSingleLegacyStat() {
        val definition = assertNotNull(TConAffixRewards.definition("fontbound_assault"))
        val tier = definition.tiers.first { it.rank == 1 }
        val rolled = TConAffixRewards.createAffix(definition, tier, "tconstruct:tool_handle", RandomSource.create(17L))

        assertFalse(rolled.contains("stat"))
        assertFalse(rolled.contains("percent"))
        assertEquals(2, TConAffixRewards.rolls(rolled).size)
        assertRollsMatchDefinition(rolled, definition, tier)
    }

    @Test
    fun mergeReplacesOnlyMatchingSourcePart() {
        val oldHead = affix("tconstruct:attack_damage", 0.05, "tconstruct:pick_head")
        val oldHandle = affix("tconstruct:durability", 0.10, "tconstruct:tool_handle")
        val newHead = affix("tconstruct:mining_speed", 0.12, "tconstruct:pick_head")

        val merged = TConAffixRewards.mergeAffixes(listOf(oldHead, oldHandle), listOf(newHead))

        assertEquals(
            listOf("tconstruct:tool_handle", "tconstruct:pick_head"),
            merged.map { it.getString("source_part") }
        )
        assertEquals(listOf("tconstruct:durability", "tconstruct:mining_speed"), merged.map { it.getString("stat") })
    }

    @Test
    fun mergeCopiesAffixTags() {
        val input = affix("tconstruct:attack_speed", 0.07, "tconstruct:small_blade")

        val merged = TConAffixRewards.mergeAffixes(emptyList(), listOf(input))
        input.putDouble("percent", 0.99)

        assertEquals(0.07, merged.single().getDouble("percent"))
    }

    @Test
    fun mergeWithEmptyInputPreservesExistingAffixes() {
        val existing = listOf(
            affix("tconstruct:durability", 0.10, "tconstruct:tool_handle"),
            affix("tconstruct:attack_damage", 0.05, "tconstruct:small_blade")
        )

        val merged = TConAffixRewards.mergeAffixes(existing, emptyList())

        assertEquals(existing.map { it.getString("stat") }, merged.map { it.getString("stat") })
        assertEquals(existing.map { it.getString("source_part") }, merged.map { it.getString("source_part") })
    }

    @Test
    fun mergeDoesNotTreatBlankSourcePartAsReplacementKey() {
        val existing = affix("tconstruct:durability", 0.10, "")
        val input = affix("tconstruct:mining_speed", 0.12, "")

        val merged = TConAffixRewards.mergeAffixes(listOf(existing), listOf(input))

        assertEquals(listOf("tconstruct:durability", "tconstruct:mining_speed"), merged.map { it.getString("stat") })
    }

    @Test
    fun multiplierTagCompoundsMatchingStatsIndependently() {
        val multipliers = TConAffixRewards.multiplierTag(
            listOf(
                affix("tconstruct:attack_damage", 0.10, "tconstruct:small_blade"),
                affix("tconstruct:attack_damage", 0.20, "tconstruct:tool_handle"),
                affix("tconstruct:mining_speed", 0.15, "tconstruct:pick_head")
            )
        )

        assertEquals(1.32, multipliers.getFloat("tconstruct:attack_damage").toDouble(), 0.00001)
        assertEquals(1.15, multipliers.getFloat("tconstruct:mining_speed").toDouble(), 0.00001)
    }

    @Test
    fun multiplierTagCompoundsMultiStatAffixRollsByStat() {
        val assault = assertNotNull(TConAffixRewards.definition("fontbound_assault"))
        val service = assertNotNull(TConAffixRewards.definition("of_blood_and_breath"))
        val affixes = listOf(
            TConAffixRewards.createAffix(assault, assault.tiers.first { it.rank == 3 }, "tconstruct:small_blade", RandomSource.create(100L)),
            TConAffixRewards.createAffix(service, service.tiers.first { it.rank == 4 }, "tconstruct:tool_handle", RandomSource.create(101L))
        )

        val expectedDamage = expectedMultiplier(affixes, "tconstruct:attack_damage")
        val expectedAttackSpeed = expectedMultiplier(affixes, "tconstruct:attack_speed")
        val expectedDurability = expectedMultiplier(affixes, "tconstruct:durability")
        val multipliers = TConAffixRewards.multiplierTag(affixes)

        assertEquals(expectedDamage, multipliers.getFloat("tconstruct:attack_damage").toDouble(), 0.00001)
        assertEquals(expectedAttackSpeed, multipliers.getFloat("tconstruct:attack_speed").toDouble(), 0.00001)
        assertEquals(expectedDurability, multipliers.getFloat("tconstruct:durability").toDouble(), 0.00001)
    }

    @Test
    fun multiplierTagIsEmptyForNoAffixes() {
        assertTrue(TConAffixRewards.multiplierTag(emptyList()).isEmpty)
    }

    @Test
    fun mergedReplacementAffixesProduceExpectedToolMultipliers() {
        val merged = TConAffixRewards.mergeAffixes(
            existing = listOf(
                affix("tconstruct:attack_damage", 0.05, "tconstruct:small_blade"),
                affix("tconstruct:durability", 0.10, "tconstruct:tool_handle")
            ),
            input = listOf(
                affix("tconstruct:mining_speed", 0.12, "tconstruct:small_blade"),
                affix("tconstruct:attack_speed", 0.08, "tconstruct:small_blade")
            )
        )
        assertEquals(
            listOf("tconstruct:tool_handle", "tconstruct:small_blade", "tconstruct:small_blade"),
            merged.map { it.getString("source_part") }
        )
        val multipliers = TConAffixRewards.multiplierTag(merged)
        assertEquals(1.10, multipliers.getFloat("tconstruct:durability").toDouble(), 0.00001)
        assertEquals(1.12, multipliers.getFloat("tconstruct:mining_speed").toDouble(), 0.00001)
        assertEquals(1.08, multipliers.getFloat("tconstruct:attack_speed").toDouble(), 0.00001)
        assertFalse(multipliers.contains("tconstruct:attack_damage"))
    }

    private fun affix(stat: String, percent: Double, sourcePart: String): CompoundTag {
        return TConAffixRewards.createAffix(stat, percent, sourcePart)
    }

    private fun assertRollsMatchDefinition(
        affix: CompoundTag,
        definition: TConAffixRewards.AffixDefinition,
        tier: TConAffixRewards.Tier
    ) {
        val rolls = TConAffixRewards.rolls(affix)

        assertEquals(definition.stats.size, rolls.size, definition.id)
        definition.stats.forEachIndexed { index, line ->
            val (stat, percent) = rolls[index]
            assertEquals(line.stat, stat, definition.id)
            assertTrue(percent >= tier.minPercent * line.scale, "${definition.id} $stat $percent")
            assertTrue(percent <= tier.maxPercent * line.scale, "${definition.id} $stat $percent")
        }
    }

    private fun expectedMultiplier(affixes: List<CompoundTag>, stat: String): Double {
        return affixes
            .flatMap(TConAffixRewards::rolls)
            .filter { it.first == stat }
            .fold(1.0) { acc, (_, percent) -> acc * (1.0 + percent) }
    }

    private fun findSeedForAffixCount(count: Int): Long {
        for (seed in 0L until 100_000L) {
            val random = RandomSource.create(seed)
            if (TConAffixRewards.affixCountForRoll(random.nextFloat()) == count) return seed
        }
        error("No deterministic seed found for $count affixes")
    }
}
