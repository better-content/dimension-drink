package dev.yourname.dimensiondrink.runtime.backend

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertNull

class RunSiteRecordParsingTest {
    @Test
    fun fromTagRejectsMissingSiteId() {
        val tag = CompoundTag().apply {
            putString("template_id", "minecraft:the_end")
            putString("backend_level_key", "minecraft:the_end")
        }

        assertNull(RunSiteRecord.fromTag(tag))
    }

    @Test
    fun fromTagRejectsBlankTemplateId() {
        val tag = CompoundTag().apply {
            putString("site_id", java.util.UUID.randomUUID().toString())
            putString("template_id", "")
        }

        assertNull(RunSiteRecord.fromTag(tag))
    }
}
