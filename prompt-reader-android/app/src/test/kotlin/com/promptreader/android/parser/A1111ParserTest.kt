package com.promptreader.android.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A1111ParserTest {
    @Test
    fun `parses entries and detail`() {
        val raw = Fixtures.read("fixtures/a1111.txt")
        val r = A1111Parser.parse(raw)

        assertTrue(r.positive.isNotBlank())
        assertTrue(r.negative.isNotBlank())
        assertTrue(r.setting.isNotBlank())
        assertEquals(r.setting, r.settingDetail)

        val map = r.settingEntries.associate { it.key to it.value }
        assertEquals("28", map["Steps"])
        assertEquals("Euler a", map["Sampler"])
        assertEquals("5.5", map["CFG scale"])
        assertEquals("123456789", map["Seed"])
        assertEquals("512x768", map["Size"])
        assertEquals("sdxl.safetensors", map["Model"])
    }
}

