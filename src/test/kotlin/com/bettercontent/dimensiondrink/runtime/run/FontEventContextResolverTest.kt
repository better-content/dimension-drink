package com.bettercontent.dimensiondrink.runtime.run

import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FontEventContextResolverTest {
    @Test
    fun canonicalFontDestinationsResolveTheirMatchingAggregate() {
        val expected = mapOf(
            "minecraft:the_nether" to "minecraft:netherrack",
            "aether:the_aether" to "aether:holystone",
            "undergarden:undergarden" to "undergarden:deepsoil",
            "deeperdarker:otherside" to "deeperdarker:cobbled_sculk_stone",
            "minecraft:the_end" to "minecraft:end_stone",
            "minecraft:overworld" to "minecraft:stone"
        )

        expected.forEach { (destination, aggregate) ->
            assertEquals(
                ResourceLocation(aggregate),
                FontEventContextResolver.aggregateFor(ResourceLocation(destination)),
                "Expected $destination to report $aggregate"
            )
        }
    }

    @Test
    fun unknownDestinationDoesNotInventAnAggregate() {
        assertNull(FontEventContextResolver.aggregateFor(ResourceLocation("example:unknown")))
    }
}
