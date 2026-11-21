package com.orcamobile.feature.settings

/**
 * OrcaSlicer's text serialization (libslic3r/Config.cpp), so edited text options
 * round-trip exactly: a string option is C-escaped (\n, \r, \\, \"), and a list of
 * strings is separated by ';' with any element containing spaces, tabs, ';',
 * '\', '"' or line breaks quoted.
 */
internal object ConfigText {

    /** escape_string_cstyle */
    fun escape(text: String): String = buildString {
        for (c in text) when (c) {
            '\r' -> append("\\r")
            '\n' -> append("\\n")
            '\\', '"' -> append('\\').append(c)
            else -> append(c)
        }
    }

    /** unescape_string_cstyle */
    fun unescape(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** escape_strings_cstyle */
    fun joinStrings(values: List<String>): String = values.joinToString(";") { value ->
        val quote = (values.size == 1 && value.isEmpty()) || value.any { it in " \t;\\\"\r\n" }
        if (!quote) {
            value
        } else {
            buildString {
                append('"')
                for (c in value) when (c) {
                    '\\', '"' -> append('\\').append(c)
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
                append('"')
            }
        }
    }

    /** unescape_strings_cstyle, step by step; malformed input yields what was read so far. */
    fun splitStrings(text: String): List<String> {
        val out = mutableListOf<String>()
        if (text.isEmpty()) return out
        var i = 0
        while (true) {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i == text.length) return out
            val word = StringBuilder()
            if (text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    var c = text[i]
                    if (c == '\\') {
                        if (++i == text.length) return out
                        c = when (text[i]) {
                            'r' -> '\r'
                            'n' -> '\n'
                            else -> text[i]
                        }
                    }
                    word.append(c)
                    i++
                }
                if (i == text.length) return out
                i++ // the closing quote
            } else {
                while (i < text.length && text[i] != ';') word.append(text[i++])
            }
            out += word.toString()
            if (i == text.length) return out
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            if (i == text.length) return out
            if (text[i] != ';') return out
            if (++i == text.length) {
                out += "" // a trailing ';' ends with one more, empty, string
                return out
            }
        }
    }
}
