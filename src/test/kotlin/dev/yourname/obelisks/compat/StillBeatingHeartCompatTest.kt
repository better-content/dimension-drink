package dev.yourname.obelisks.compat

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertEquals

class StillBeatingHeartCompatTest {
    @Test
    fun `font reads modern and legacy heart levels`() {
        val modernData = CompoundTag()
        modernData.putInt("schema_version", 2)
        modernData.putInt("level", 12)

        val legacyData = CompoundTag()
        val legacyPlayer = CompoundTag()
        legacyPlayer.putInt("experience_level", 23)
        legacyData.put("player", legacyPlayer)

        assertEquals(12, StillBeatingHeartCompat.getLevel(modernData))
        assertEquals(23, StillBeatingHeartCompat.getLevel(legacyData))
    }

    @Test
    fun `invalid heart data contributes no font level`() {
        assertEquals(0, StillBeatingHeartCompat.getLevel(CompoundTag()))

        val data = CompoundTag()
        data.putInt("level", -5)

        assertEquals(0, StillBeatingHeartCompat.getLevel(data))
    }

    @Test
    fun `higher level hearts produce stronger font blood multiplier`() {
        val low = StillBeatingHeartCompat.bloodMultiplier(level = 5, perLevelMultiplier = 0.08)
        val high = StillBeatingHeartCompat.bloodMultiplier(level = 20, perLevelMultiplier = 0.08)

        assertEquals(1.4, low)
        assertEquals(2.6, high)
    }

    @Test
    fun `font multiplier cannot reduce blood generation`() {
        assertEquals(1.0, StillBeatingHeartCompat.bloodMultiplier(level = -3, perLevelMultiplier = 0.08))
        assertEquals(1.0, StillBeatingHeartCompat.bloodMultiplier(level = 10, perLevelMultiplier = -0.25))
    }
}
