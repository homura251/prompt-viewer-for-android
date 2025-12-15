package com.promptreader.android.parser

fun buildCombinedRaw(raw: String, rawParts: List<RawPart>): String {
    if (rawParts.isEmpty()) return raw
    return rawParts.joinToString("\n\n") { part ->
        val header = "### ${part.title}".trim()
        val body = part.text.trim()
        if (body.isBlank()) header else "$header\n$body"
    }
}

