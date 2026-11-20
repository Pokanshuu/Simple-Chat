package com.simplechat.app.ui.markdown

/** Offsets in the original Markdown. Computed once per incoming snapshot, shared by its frames. */
internal class StreamingLinkBoundaries(val visibleEnd: Int, private val links: List<IntRange>) {
    fun completeThrough(end: Int): Int {
        val index = links.binarySearch { range ->
            when {
                end <= range.first -> 1
                end > range.last -> -1
                else -> 0
            }
        }
        return if (index >= 0) links[index].last + 1 else end
    }
}

/** Hold a possible inline link at the live tail until its closing delimiter or contrary evidence. */
internal fun streamingLinkBoundaries(source: String): StreamingLinkBoundaries {
    val links = mutableListOf<IntRange>()
    if ('[' !in source) return StreamingLinkBoundaries(source.length, links)
    val brackets = ArrayDeque<Int>()
    var fence: String? = null
    var offset = 0
    for (line in source.splitToSequence('\n')) {
        val marker = fenceMarkerOf(line.trimStart())
        val lastLine = offset + line.length == source.length
        if (marker != null) {
            if (fence == null) fence = marker else if (marker == fence) fence = null
        } else if (fence == null && '[' in line) {
            brackets.clear()
            var ticks = 0
            var i = 0
            while (i < line.length) {
                val c = line[i++]
                when {
                    c == '`' -> {
                        val start = i - 1
                        while (i < line.length && line[i] == '`') i++
                        val count = i - start
                        if (ticks == 0) ticks = count else if (ticks == count) ticks = 0
                    }
                    ticks != 0 -> Unit
                    c == '\\' -> i++
                    c == '[' -> brackets.addLast(i - 1)
                    c == ']' && brackets.isNotEmpty() -> {
                        val start = brackets.removeLast()
                        if (i == line.length && lastLine) {
                            return StreamingLinkBoundaries(offset + (brackets.firstOrNull() ?: start), links)
                        }
                        if (line.getOrNull(i) != '(') continue
                        val end = linkDestinationEnd(line, i)
                        if (end == null) {
                            if (lastLine) return StreamingLinkBoundaries(offset + (brackets.firstOrNull() ?: start), links)
                            break
                        }
                        if (end == i) continue // Invalid destination; resume scanning the prose.
                        links += offset + start until offset + end
                        brackets.clear() // An inline link cannot contain another link.
                        i = end
                    }
                }
            }
            if (lastLine && brackets.isNotEmpty()) return StreamingLinkBoundaries(offset + brackets.first(), links)
        }
        offset += line.length + 1
    }
    return StreamingLinkBoundaries(source.length, links)
}

/** End (exclusive), null while incomplete, or [open] if the rest is ordinary text. */
private fun linkDestinationEnd(line: String, open: Int): Int? {
    var i = open + 1
    fun skipSpace() { while (i < line.length && line[i].isWhitespace()) i++ }
    fun skipDelimited(close: Char): Boolean {
        i++
        while (i < line.length) {
            when (line[i++]) {
                close -> return true
                '\\' -> if (i < line.length) i++
            }
        }
        return false
    }
    skipSpace()
    if (line.getOrNull(i) == '<') {
        if (!skipDelimited('>')) return null
    } else {
        var depth = 0
        while (i < line.length && !line[i].isWhitespace()) {
            when (line[i++]) {
                '(' -> depth++
                ')' -> if (depth-- == 0) return i
                '\\' -> if (i < line.length) i++
            }
        }
        if (depth != 0 && i < line.length) return open
    }
    skipSpace()
    when (val next = line.getOrNull(i)) {
        '"', '\'', '(' -> if (!skipDelimited(if (next == '(') ')' else next)) return null
    }
    skipSpace()
    return when (line.getOrNull(i)) {
        null -> null
        ')' -> i + 1
        else -> open
    }
}
