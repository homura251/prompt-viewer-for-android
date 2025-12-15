package com.promptreader.android.parser

object Fixtures {
    fun read(path: String): String {
        val fullPath = if (path.startsWith("/")) path else "/$path"
        val stream = requireNotNull(Fixtures::class.java.getResourceAsStream(fullPath)) {
            "Fixture not found: $fullPath"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

