package me.mudkip.moememos.util

enum class MemoLineKind { TEXT, CHECKBOX }

data class MemoLine(
    val kind: MemoLineKind,
    val text: String,
    val checked: Boolean,
    val startOffset: Int,
    val endOffset: Int,
    val indentLevel: Int,
)

data class VisibleLines(val lines: List<MemoLine>, val truncated: Boolean)

private val checkboxLinePattern = Regex("""^(\s*)([-*+])\s+\[([ xX])\](?:\s(.*))?$""")

/**
 * Splits memo content into renderable lines, keeping each line's offset in the original content so a
 * checkbox tap can be written back without re-serializing the whole memo.
 *
 * Lines inside fenced code blocks are reported as text: `- [ ]` there is an example, not a task.
 */
fun parseMemoChecklist(content: String): List<MemoLine> {
    val lines = mutableListOf<MemoLine>()
    var inFence = false
    var fenceChar = ' '
    var fenceLength = 0
    var lineStart = 0
    while (lineStart < content.length) {
        val newline = content.indexOf('\n', lineStart)
        val rawEnd = if (newline == -1) content.length else newline
        val end = if (rawEnd > lineStart && content[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
        val raw = content.substring(lineStart, end)

        if (raw.isNotBlank()) {
            val trimmed = raw.trimStart()
            val fence = fenceMarkerOf(trimmed)
            when {
                inFence -> {
                    lines += MemoLine(MemoLineKind.TEXT, raw, false, lineStart, end, 0)
                    // Only the fence character that opened the block can close it.
                    if (fence != null && fence.first == fenceChar && fence.second >= fenceLength) {
                        inFence = false
                    }
                }
                fence != null -> {
                    lines += MemoLine(MemoLineKind.TEXT, raw, false, lineStart, end, 0)
                    inFence = true
                    fenceChar = fence.first
                    fenceLength = fence.second
                }
                else -> {
                    val match = checkboxLinePattern.matchEntire(raw)
                    if (match == null) {
                        lines += MemoLine(
                            MemoLineKind.TEXT,
                            raw,
                            false,
                            lineStart,
                            end,
                            indentLevelOf(raw.takeWhile { it == ' ' || it == '\t' })
                        )
                    } else {
                        val marker = match.groupValues[3]
                        val indentLevel = indentLevelOf(match.groupValues[1])
                        lines += MemoLine(
                            MemoLineKind.CHECKBOX,
                            match.groupValues[4],
                            marker == "x" || marker == "X",
                            lineStart,
                            end,
                            indentLevel
                        )
                    }
                }
            }
        }

        if (newline == -1) break
        lineStart = newline + 1
    }
    return lines
}

/** Returns the fence character and its run length when [trimmed] starts a fenced code block. */
private fun fenceMarkerOf(trimmed: String): Pair<Char, Int>? {
    val char = trimmed.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val length = trimmed.takeWhile { it == char }.length
    return if (length >= 3) char to length else null
}

/** Returns the raw line at the given offsets, or null when the range is not exactly one line of [content]. */
fun lineAt(content: String, startOffset: Int, endOffset: Int): String? {
    if (startOffset < 0 || endOffset > content.length || startOffset >= endOffset) return null
    if (startOffset > 0 && content[startOffset - 1] != '\n') return null
    val endsLine = endOffset == content.length ||
        content[endOffset] == '\n' ||
        (content[endOffset] == '\r' &&
            (endOffset + 1 == content.length || content[endOffset + 1] == '\n'))
    if (!endsLine) return null
    val line = content.substring(startOffset, endOffset)
    return if (line.contains('\n')) null else line
}

/**
 * Flips the checkbox marker of exactly one line. Returns null when the range is not a checkbox line or
 * the requested state already holds, so callers can treat null as "nothing to write".
 */
fun toggleCheckboxIn(content: String, startOffset: Int, endOffset: Int, checked: Boolean): String? {
    val line = lineAt(content, startOffset, endOffset) ?: return null
    val match = checkboxLinePattern.matchEntire(line) ?: return null
    val marker = match.groupValues[3]
    if ((marker == "x" || marker == "X") == checked) return null
    val markerOffset = startOffset + match.groups[3]!!.range.first
    val newMarker = if (checked) "x" else " "
    return content.substring(0, markerOffset) + newMarker + content.substring(markerOffset + 1)
}

/**
 * Keeps the leading lines that fit [lineBudget], flagging whether anything was cut.
 *
 * The widget renders one visual line per entry and Glance has no ellipsis for overflowing text, so
 * every entry costs exactly one line no matter how long it is.
 */
fun visibleLines(lines: List<MemoLine>, lineBudget: Int): VisibleLines {
    if (lineBudget <= 0) return VisibleLines(emptyList(), lines.isNotEmpty())
    val visible = lines.take(lineBudget)
    return VisibleLines(visible, visible.size < lines.size)
}

private fun indentLevelOf(whitespace: String): Int {
    var spaces = 0
    for (char in whitespace) {
        spaces += if (char == '\t') 4 else 1
    }
    return spaces.coerceIn(0, 2)
}
