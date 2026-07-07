package dev.yourname.obelisks.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CanonicalTargetResolverTest {
    @Test
    fun targetIdPrefersExplicitTargetDimension() {
        val definition = ObeliskDefinition(
            id = "test_id",
            displayName = "Test",
            instanceTemplateId = "otherside",
            targetDimension = "deeperdarker:otherside"
        )

        assertEquals("deeperdarker:otherside", CanonicalTargetResolver.targetId(definition))
    }

    @Test
    fun targetIdFallsBackToLegacyAliases() {
        val definition = ObeliskDefinition(
            id = "nether",
            displayName = "Nether",
            instanceTemplateId = ""
        )

        assertEquals("minecraft:the_nether", CanonicalTargetResolver.targetId(definition))
    }

    @Test
    fun coordinateScaleUsesLegacyNetherRule() {
        val definition = ObeliskDefinition(
            id = "nether",
            displayName = "Nether",
            instanceTemplateId = "nether"
        )

        assertEquals(0.125, CanonicalTargetResolver.coordinateScale(definition))
        assertEquals(0.125, CanonicalTargetResolver.defaultCoordinateScale("minecraft:the_nether"))
        assertEquals(1.0, CanonicalTargetResolver.defaultCoordinateScale("minecraft:overworld"))
    }

    @Test
    fun resourceLocationParserHandlesInvalidValues() {
        assertNotNull(CanonicalTargetResolver.resourceLocationOrNull("minecraft:overworld"))
        assertNull(CanonicalTargetResolver.resourceLocationOrNull("not a valid id"))
        assertNull(CanonicalTargetResolver.resourceLocationOrNull("bad:value:extra"))
    }
}
