package com.promptreader.android.parser

object A1111Parser {
    data class Result(
        val positive: String,
        val negative: String,
        val setting: String,
        val raw: String,
        val settingEntries: List<SettingEntry> = emptyList(),
        val settingDetail: String = "",
    )

    fun parse(raw: String): Result {
        if (raw.isBlank()) return Result("", "", "", "")

        val stepsIndex = raw.indexOf("\nSteps:")
        var positive = ""
        var negative = ""
        var setting = ""

        if (stepsIndex != -1) {
            positive = raw.substring(0, stepsIndex).trim()
            setting = raw.substring(stepsIndex + 1).trim()
        } else {
            positive = raw.trim()
        }

        val negMarker = "\nNegative prompt:"
        val negIndex = raw.indexOf(negMarker)
        if (negIndex != -1) {
            positive = raw.substring(0, negIndex).trim()
            if (stepsIndex != -1) {
                negative = raw.substring(negIndex + negMarker.length, stepsIndex).trim()
            } else {
                negative = raw.substring(negIndex + negMarker.length).trim()
            }
        }

        val entries = parseSettingEntries(setting)
        return Result(
            positive = positive,
            negative = negative,
            setting = setting,
            raw = raw.trim(),
            settingEntries = entries,
            settingDetail = setting,
        )
    }

    private fun parseSettingEntries(setting: String): List<SettingEntry> {
        if (setting.isBlank()) return emptyList()

        val parts = setting.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val entries = ArrayList<SettingEntry>(parts.size)
        for (p in parts) {
            val idx = p.indexOf(':')
            if (idx <= 0 || idx >= p.length - 1) continue
            val key = p.substring(0, idx).trim()
            val value = p.substring(idx + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) entries += SettingEntry(key, value)
        }
        return entries
    }
}
