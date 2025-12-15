package com.promptreader.android.parser

import org.json.JSONObject

object SwarmUiParser {
    data class Result(
        val positive: String,
        val negative: String,
        val setting: String,
        val raw: String,
        val settingEntries: List<SettingEntry> = emptyList(),
        val settingDetail: String = "",
    )

    fun parse(jsonText: String): Result {
        val root = JSONObject(jsonText)
        val params = root.optJSONObject("sui_image_params") ?: JSONObject()

        val positive = params.optString("prompt", "").trim()
        val negative = params.optString("negativeprompt", "").trim()

        // Remove prompt/negativeprompt from display settings, similar to Python
        val copy = JSONObject(params.toString())
        copy.remove("prompt")
        copy.remove("negativeprompt")

        val entries = buildSettingEntries(params)
        val setting = if (entries.isNotEmpty()) {
            entries.joinToString(", ") { "${it.key}: ${it.value}" }
        } else {
            copy.toString().trim('{', '}').replace("\"", "").trim()
        }
        val settingDetail = copy.toString(2)
        val raw = listOf(positive, negative, params.toString()).filter { it.isNotBlank() }.joinToString("\n")

        return Result(
            positive = positive,
            negative = negative,
            setting = setting,
            raw = raw,
            settingEntries = entries,
            settingDetail = settingDetail,
        )
    }

    private fun buildSettingEntries(params: JSONObject): List<SettingEntry> {
        fun firstNonBlank(vararg keys: String): String? {
            for (k in keys) {
                val v = params.optString(k, "").trim()
                if (v.isNotBlank() && v != "null") return v
            }
            return null
        }

        fun firstNumberString(vararg keys: String): String? {
            for (k in keys) {
                val v = params.opt(k) ?: continue
                val s = v.toString().trim()
                if (s.isNotBlank() && s != "null") return s
            }
            return null
        }

        val width = params.optInt("width", 0).takeIf { it > 0 }
            ?: params.optInt("W", 0).takeIf { it > 0 }
        val height = params.optInt("height", 0).takeIf { it > 0 }
            ?: params.optInt("H", 0).takeIf { it > 0 }

        val entries = ArrayList<SettingEntry>()
        firstNonBlank("model", "model_name", "checkpoint", "ckpt_name", "modelname")?.let { entries += SettingEntry("Model", it) }
        firstNumberString("steps", "stepcount")?.let { entries += SettingEntry("Steps", it) }
        firstNonBlank("sampler", "sampler_name", "samplername")?.let { entries += SettingEntry("Sampler", it) }
        firstNumberString("cfgscale", "cfg", "cfg_scale")?.let { entries += SettingEntry("CFG scale", it) }
        firstNumberString("seed", "noise_seed")?.let { entries += SettingEntry("Seed", it) }
        if (width != null && height != null) entries += SettingEntry("Size", "${width}x${height}")

        return entries
    }
}
