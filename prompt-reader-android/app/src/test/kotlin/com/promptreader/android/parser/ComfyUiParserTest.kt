package com.promptreader.android.parser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyUiParserTest {
    @Test
    fun `extracts model through model chain and builds entries`() {
        val prompt = Fixtures.read("fixtures/comfyui_prompt.json")
        val workflow = Fixtures.read("fixtures/comfyui_workflow.json")
        val r = ComfyUiParser.parse(promptJsonText = prompt, workflowText = workflow)

        assertEquals("a cute cat, best quality", r.positive)
        assertEquals("blurry, lowres", r.negative)

        assertTrue(r.settingEntries.isNotEmpty())
        assertEquals("Model", r.settingEntries.first().key)
        assertEquals("sdxl.safetensors", r.settingEntries.first().value)

        val map = r.settingEntries.associate { it.key to it.value }
        assertEquals("28", map["Steps"])
        assertEquals("k_dpmpp_2m", map["Sampler"])
        assertEquals("5.5", map["CFG scale"])
        assertEquals("123456789", map["Seed"])

        val detail = JSONObject(r.settingDetail)
        assertEquals("sdxl.safetensors", detail.getString("Model"))
    }

    @Test
    fun `parses workflow-only via SDPromptReader`() {
        val workflow = Fixtures.read("fixtures/comfyui_workflow_prompt_reader.json")
        val r = ComfyUiParser.parseWorkflow(workflowText = "null\n$workflow")

        assertEquals("a cute cat, best quality", r.positive)
        assertEquals("blurry, lowres", r.negative)

        assertTrue(r.settingEntries.isNotEmpty())
        val map = r.settingEntries.associate { it.key to it.value }
        assertEquals("models/sd/sdxl.safetensors", map["Model"])
        assertEquals("28", map["Steps"])
        assertEquals("7.3", map["CFG scale"])
        assertEquals("363312086", map["Seed"])
        assertEquals("1472x704", map["Size"])

        val detail = JSONObject(r.settingDetail)
        assertTrue(detail.has("workflow_meta"))
    }
}
