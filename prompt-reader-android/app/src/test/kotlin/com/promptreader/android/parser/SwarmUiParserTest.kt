package com.promptreader.android.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmUiParserTest {
    @Test
    fun `parses entries and pretty detail without prompts`() {
        val raw = Fixtures.read("fixtures/swarmui.json")
        val r = SwarmUiParser.parse(raw)

        assertEquals("a cute cat, best quality", r.positive)
        assertEquals("blurry, lowres", r.negative)
        assertTrue(r.settingEntries.isNotEmpty())

        val map = r.settingEntries.associate { it.key to it.value }
        assertEquals("sdxl.safetensors", map["Model"])
        assertEquals("30", map["Steps"])
        assertEquals("euler", map["Sampler"])
        assertEquals("6.0", map["CFG scale"])
        assertEquals("42", map["Seed"])
        assertEquals("512x768", map["Size"])

        assertTrue(r.settingDetail.startsWith("{"))
        assertFalse(r.settingDetail.contains("\"prompt\""))
        assertFalse(r.settingDetail.contains("\"negativeprompt\""))
    }
}

