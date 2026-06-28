package dev.yourname.obelisks.gametest

import dev.yourname.obelisks.integration.tcon.TConAffixRewards
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.SimpleContainer
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries
import slimeknights.tconstruct.library.materials.definition.MaterialVariant
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId
import slimeknights.tconstruct.library.tools.helper.ToolBuildHandler
import slimeknights.tconstruct.library.tools.item.IModifiable
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT
import slimeknights.tconstruct.library.tools.nbt.ToolStack
import slimeknights.tconstruct.library.tools.stat.ToolStats
import slimeknights.tconstruct.tools.TinkerToolParts
import slimeknights.tconstruct.tools.TinkerTools

@PrefixGameTestTemplate(false)
class ObeliskTConGameTests {
    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_tcon", timeoutTicks = 200)
    fun reward_parts_roll_with_affixes_and_multiplier_tags(helper: GameTestHelper) {
        repeat(24) { seed ->
            val part = requireNotNull(TConAffixRewards.rollAffixedPart(net.minecraft.util.RandomSource.create(seed.toLong() + 1L))) {
                "Expected TCon reward part roll for seed $seed"
            }
            val partId = ForgeRegistries.ITEMS.getKey(part.item)?.toString()
            helper.assertTrue(partId != null, "Expected rolled part to have a registry id")
            helper.assertTrue(
                TConAffixRewards.partProfiles.any { it.itemId == partId },
                "Expected rolled part $partId to come from the configured part drop pool"
            )
            val affixes = TConAffixRewards.existingToolAffixes(part)
            helper.assertTrue(affixes.isNotEmpty(), "Expected rolled part $partId to contain at least one affix")
            affixes.forEach { affix ->
                helper.assertTrue(
                    affix.getString("source_part") == partId,
                    "Expected rolled affix source part to match dropped part id; expected=$partId actual=${affix.getString("source_part")}"
                )
            }
        }
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_tcon", timeoutTicks = 200)
    fun affixed_parts_update_fresh_tconstruct_tool_stats(helper: GameTestHelper) {
        val result = buildPickaxe()
        val baseline = ToolStack.from(result.copy())
        val baselineMining = baseline.getStats().get(ToolStats.MINING_SPEED)
        val baselineDurability = baseline.getStats().get(ToolStats.DURABILITY)

        val pickHead = ItemStack(TinkerToolParts.pickHead.get())
        val handle = ItemStack(TinkerToolParts.toolHandle.get())
        val binding = ItemStack(TinkerToolParts.toolBinding.get())
        TConAffixRewards.writeToolAffixes(
            pickHead,
            listOf(
                TConAffixRewards.createAffix("tconstruct:mining_speed", 0.20, "tconstruct:pick_head"),
                TConAffixRewards.createAffix("tconstruct:durability", 0.15, "tconstruct:pick_head")
            )
        )

        val player = FakePlayerFactory.getMinecraft(helper.level)
        val inventory = SimpleContainer(3)
        inventory.setItem(0, pickHead)
        inventory.setItem(1, handle)
        inventory.setItem(2, binding)

        TConAffixRewards.onItemCrafted(PlayerEvent.ItemCraftedEvent(player, result, inventory))

        val affixes = TConAffixRewards.existingToolAffixes(result)
        helper.assertTrue(affixes.size == 2, "Expected fresh pickaxe result to inherit both pick head affixes")
        val tool = ToolStack.from(result)
        helper.assertTrue(approx(tool.getMultipliers().get(ToolStats.MINING_SPEED).toDouble(), 1.20), "Expected fresh pickaxe to gain 1.20 mining-speed multiplier, found ${tool.getMultipliers().get(ToolStats.MINING_SPEED)}")
        helper.assertTrue(approx(tool.getMultipliers().get(ToolStats.DURABILITY).toDouble(), 1.15), "Expected fresh pickaxe to gain 1.15 durability multiplier, found ${tool.getMultipliers().get(ToolStats.DURABILITY)}")
        helper.assertTrue(tool.getStats().get(ToolStats.MINING_SPEED) > baselineMining, "Expected fresh pickaxe mining speed to increase after affix transfer")
        helper.assertTrue(tool.getStats().get(ToolStats.DURABILITY) > baselineDurability, "Expected fresh pickaxe durability to increase after affix transfer")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_tcon", timeoutTicks = 200)
    fun modifier_affixes_apply_to_fresh_tconstruct_tool(helper: GameTestHelper) {
        val result = buildPickaxe()
        val pickHead = ItemStack(TinkerToolParts.pickHead.get())
        val handle = ItemStack(TinkerToolParts.toolHandle.get())
        val binding = ItemStack(TinkerToolParts.toolBinding.get())

        val gravehook = requireNotNull(TConAffixRewards.definition("gravehook"))
        TConAffixRewards.writeToolAffixes(
            pickHead,
            listOf(TConAffixRewards.createAffix(gravehook, gravehook.tiers.first { it.rank == 3 }, "tconstruct:pick_head", net.minecraft.util.RandomSource.create(3L)))
        )

        val player = FakePlayerFactory.getMinecraft(helper.level)
        val inventory = SimpleContainer(3)
        inventory.setItem(0, pickHead)
        inventory.setItem(1, handle)
        inventory.setItem(2, binding)

        TConAffixRewards.onItemCrafted(PlayerEvent.ItemCraftedEvent(player, result, inventory))

        val affixes = TConAffixRewards.existingToolAffixes(result)
        helper.assertTrue(affixes.size == 1, "Expected fresh pickaxe result to inherit the modifier affix")
        helper.assertTrue(TConAffixRewards.grantedModifiers(affixes.single()).any { it.id == "tconstruct:magnetic" }, "Expected inherited affix to grant magnetic")
        val tool = ToolStack.from(result)
        val magneticId = requireNotNull(slimeknights.tconstruct.library.modifiers.ModifierId.tryParse("tconstruct:magnetic"))
        helper.assertTrue(tool.getUpgrades().getLevel(magneticId) == 1, "Expected fresh pickaxe to gain Magnetic I")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_tcon", timeoutTicks = 200)
    fun part_replacement_on_existing_tool_rebuilds_stats_without_readding_old_affixes(helper: GameTestHelper) {
        val baseline = buildPickaxe()
        val baselineTool = ToolStack.from(baseline.copy())
        val baselineAttack = baselineTool.getStats().get(ToolStats.ATTACK_DAMAGE)
        val baselineMining = baselineTool.getStats().get(ToolStats.MINING_SPEED)
        val baselineDurability = baselineTool.getStats().get(ToolStats.DURABILITY)

        val existing = baseline.copy()
        val existingAffixes = listOf(
            TConAffixRewards.createAffix("tconstruct:attack_damage", 0.10, "tconstruct:small_blade"),
            TConAffixRewards.createAffix("tconstruct:durability", 0.12, "tconstruct:tool_handle")
        )
        TConAffixRewards.writeToolAffixes(existing, existingAffixes)
        TConAffixRewards.applyMultipliers(existing, existingAffixes)
        TConAffixRewards.refreshTConTool(existing)

        val result = existing.copy()
        val replacementPart = ItemStack(TinkerToolParts.smallBlade.get())
        TConAffixRewards.writeToolAffixes(
            replacementPart,
            listOf(TConAffixRewards.createAffix("tconstruct:mining_speed", 0.25, "tconstruct:small_blade"))
        )

        val player = FakePlayerFactory.getMinecraft(helper.level)
        val inventory = SimpleContainer(2)
        inventory.setItem(0, existing)
        inventory.setItem(1, replacementPart)

        TConAffixRewards.onItemCrafted(PlayerEvent.ItemCraftedEvent(player, result, inventory))

        val affixes = TConAffixRewards.existingToolAffixes(result)
        helper.assertTrue(affixes.size == 2, "Expected modified tool result to keep one handle affix and one replaced blade affix")
        helper.assertTrue(affixes.none { it.getString("stat") == "tconstruct:attack_damage" }, "Expected replacement blade affix to remove the old blade attack affix; found $affixes")
        helper.assertTrue(affixes.any { it.getString("stat") == "tconstruct:durability" && it.getString("source_part") == "tconstruct:tool_handle" }, "Expected modified tool result to keep the handle durability affix")
        helper.assertTrue(affixes.any { it.getString("stat") == "tconstruct:mining_speed" && it.getString("source_part") == "tconstruct:small_blade" }, "Expected modified tool result to gain the replacement blade mining-speed affix")

        val tool = ToolStack.from(result)
        helper.assertTrue(!tool.getMultipliers().hasStat(ToolStats.ATTACK_DAMAGE), "Expected modified tool result to clear the replaced attack-damage multiplier")
        helper.assertTrue(approx(tool.getMultipliers().get(ToolStats.DURABILITY).toDouble(), 1.12), "Expected modified tool result to keep the handle durability multiplier, found ${tool.getMultipliers().get(ToolStats.DURABILITY)}")
        helper.assertTrue(approx(tool.getMultipliers().get(ToolStats.MINING_SPEED).toDouble(), 1.25), "Expected modified tool result to apply the replacement blade mining-speed multiplier, found ${tool.getMultipliers().get(ToolStats.MINING_SPEED)}")
        helper.assertTrue(tool.getStats().get(ToolStats.DURABILITY) > baselineDurability, "Expected modified tool durability to stay above baseline from the preserved handle affix")
        helper.assertTrue(tool.getStats().get(ToolStats.MINING_SPEED) > baselineMining, "Expected modified tool mining speed to rise after the replacement blade affix")
        helper.assertTrue(approx(tool.getStats().get(ToolStats.ATTACK_DAMAGE).toDouble(), baselineAttack.toDouble()), "Expected modified tool attack damage to return to baseline after replacing the blade affix")
        helper.succeed()
    }

    @GameTest(templateNamespace = "dimensionalfonts", template = "bootstrap/empty", batch = "obelisk_tcon", timeoutTicks = 200)
    fun replacement_swaps_modifier_grants_without_accumulating_stale_effects(helper: GameTestHelper) {
        val base = buildPickaxe()
        val gravehook = requireNotNull(TConAffixRewards.definition("gravehook"))
        val charward = requireNotNull(TConAffixRewards.definition("charward"))

        val existing = base.copy()
        val existingAffixes = listOf(
            TConAffixRewards.createAffix(gravehook, gravehook.tiers.first { it.rank == 4 }, "tconstruct:pick_head", net.minecraft.util.RandomSource.create(11L))
        )
        TConAffixRewards.writeToolAffixes(existing, existingAffixes)
        TConAffixRewards.applyGrantedModifiers(existing, emptyList(), existingAffixes)

        val result = existing.copy()
        val replacementPart = ItemStack(TinkerToolParts.pickHead.get())
        val replacementAffixes = listOf(
            TConAffixRewards.createAffix(charward, charward.tiers.first { it.rank == 4 }, "tconstruct:pick_head", net.minecraft.util.RandomSource.create(12L))
        )
        TConAffixRewards.writeToolAffixes(replacementPart, replacementAffixes)

        val player = FakePlayerFactory.getMinecraft(helper.level)
        val inventory = SimpleContainer(2)
        inventory.setItem(0, existing)
        inventory.setItem(1, replacementPart)

        TConAffixRewards.onItemCrafted(PlayerEvent.ItemCraftedEvent(player, result, inventory))

        val affixes = TConAffixRewards.existingToolAffixes(result)
        helper.assertTrue(affixes.size == 1, "Expected replacement to leave one pick-head affix")
        helper.assertTrue(TConAffixRewards.grantedModifiers(affixes.single()).any { it.id == "tconstruct:autosmelt" }, "Expected replacement pick head to grant autosmelt")
        val tool = ToolStack.from(result)
        val magneticId = requireNotNull(slimeknights.tconstruct.library.modifiers.ModifierId.tryParse("tconstruct:magnetic"))
        val autosmeltId = requireNotNull(slimeknights.tconstruct.library.modifiers.ModifierId.tryParse("tconstruct:autosmelt"))
        helper.assertTrue(tool.getUpgrades().getLevel(magneticId) == 0, "Expected replacement to remove stale Magnetic")
        helper.assertTrue(tool.getUpgrades().getLevel(autosmeltId) == 1, "Expected replacement to apply Autosmelt I")
        helper.succeed()
    }

    private fun buildPickaxe(): ItemStack {
        val pickaxe = TinkerTools.pickaxe.get() as IModifiable
        val materials = MaterialNBT.of(
            MaterialVariant.of(MaterialVariantId.parse("tconstruct:copper")),
            MaterialVariant.of(MaterialVariantId.parse("tconstruct:wood")),
            MaterialVariant.of(MaterialVariantId.parse("tconstruct:leather"))
        )
        return ToolBuildHandler.buildItemFromMaterials(pickaxe, materials)
    }

    private fun approx(actual: Double, expected: Double, tolerance: Double = 0.0001): Boolean {
        return kotlin.math.abs(actual - expected) <= tolerance
    }
}
