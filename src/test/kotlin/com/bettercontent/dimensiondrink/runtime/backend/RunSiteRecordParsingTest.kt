package com.bettercontent.dimensiondrink.runtime.backend

import net.minecraft.nbt.CompoundTag
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunSiteRecordParsingTest {
    @Test
    fun savedDataDeclaresSchemaAndRejectsUnknownVersions() {
        val current = CompoundTag().apply {
            putInt("schema", RunSiteSavedData.SCHEMA_VERSION)
            put("sites", net.minecraft.nbt.ListTag())
        }
        val first = RunSiteSavedData.load(current)
        val encoded = first.save(CompoundTag())
        assertEquals(RunSiteSavedData.SCHEMA_VERSION, encoded.getInt("schema"))
        assertTrue(RunSiteSavedData.load(encoded).values().isEmpty())

        val newer = encoded.copy().apply { putInt("schema", RunSiteSavedData.SCHEMA_VERSION + 1) }
        assertFailsWith<IllegalArgumentException> { RunSiteSavedData.load(newer) }
        assertFailsWith<IllegalArgumentException> { RunSiteSavedData.load(CompoundTag()) }
    }

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
