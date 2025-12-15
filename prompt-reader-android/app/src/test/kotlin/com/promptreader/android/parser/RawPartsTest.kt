package com.promptreader.android.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class RawPartsTest {
    @Test
    fun `buildCombinedRaw returns raw when parts empty`() {
        assertEquals("abc", buildCombinedRaw("abc", emptyList()))
    }

    @Test
    fun `buildCombinedRaw formats parts with headers`() {
        val parts = listOf(
            RawPart(title = "PNG tEXt: prompt", text = """{"a":1}""", mime = "application/json"),
            RawPart(title = "PNG tEXt: workflow", text = """{"b":2}""", mime = "application/json"),
        )
        val s = buildCombinedRaw(raw = "ignored", rawParts = parts)
        assertEquals(
            "### PNG tEXt: prompt\n{\"a\":1}\n\n### PNG tEXt: workflow\n{\"b\":2}",
            s,
        )
    }
}

