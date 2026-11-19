package com.simplechat.app.ui.markdown

internal fun selectionCopyText(markdown: String): String {
    val output = mutableListOf<String>()
    var fenced = false
    var previousWasList = false
    normalizeHtml(markdown).lines().forEach { line ->
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            fenced = !fenced
            output += line.trim()
            previousWasList = false
            return@forEach
        }
        if (fenced) {
            output += line
            return@forEach
        }
        val bullet = Regex("^(\\s*)(?:[-+*]|\\u2022)\\s+(.*)$").matchEntire(line)
        val ordered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$").matchEntire(line)
        var text = when {
            bullet != null -> "  ".repeat(bullet.groupValues[1].length / 2) + "\u00b7 " + bullet.groupValues[2]
            ordered != null -> "  ".repeat(ordered.groupValues[1].length / 2) + ordered.groupValues[2] + ". " + ordered.groupValues[3]
            else -> line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
        }
        text = text.replace(Regex("<[^>]*>"), "")
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(`{1,3}|\\*{1,2}|_{1,2}|~~)(.*?)\\1"), "$2")
        if (text.isBlank()) {
            if (output.isNotEmpty() && output.last().isNotEmpty()) output += ""
            previousWasList = false
        } else {
            if (!previousWasList && output.isNotEmpty() && output.last().isNotEmpty() && (bullet != null || ordered != null)) output += ""
            output += text
            previousWasList = bullet != null || ordered != null
        }
    }
    return output.joinToString("\n").trim()
}
