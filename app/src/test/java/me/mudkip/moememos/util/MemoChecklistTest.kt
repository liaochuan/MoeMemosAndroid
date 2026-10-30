package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoChecklistTest {
    @Test
    fun parsesTextAndCheckboxesInOrderSkippingBlankLines() {
        val content = "本周采购\n\n- [ ] 鸡蛋\n* [x] 面包\n# 备注"
        val lines = parseMemoChecklist(content)

        assertEquals(
            listOf(MemoLineKind.TEXT, MemoLineKind.CHECKBOX, MemoLineKind.CHECKBOX, MemoLineKind.TEXT),
            lines.map { it.kind }
        )
        assertEquals(listOf("本周采购", "鸡蛋", "面包", "# 备注"), lines.map { it.text })
        assertEquals(listOf(false, false, true, false), lines.map { it.checked })
    }

    @Test
    fun keepsOffsetsThatSliceExactlyTheRawLine() {
        val content = "a\n- [ ] milk\nb"
        val lines = parseMemoChecklist(content)
        val checkbox = lines[1]

        assertEquals("- [ ] milk", content.substring(checkbox.startOffset, checkbox.endOffset))
        assertEquals("- [ ] milk", lineAt(content, checkbox.startOffset, checkbox.endOffset))
    }

    @Test
    fun treatsFencedCodeBlockLinesAsText() {
        val content = "```\n- [ ] not a task\n```\n- [ ] real task"
        val lines = parseMemoChecklist(content)

        assertEquals(
            listOf(MemoLineKind.TEXT, MemoLineKind.TEXT, MemoLineKind.TEXT, MemoLineKind.CHECKBOX),
            lines.map { it.kind }
        )
        assertEquals("- [ ] not a task", lines[1].text)
        assertEquals(false, lines[1].checked)
        assertEquals("real task", lines[3].text)
    }

    @Test
    fun togglesOnlyTheMarkerInsideTheLine() {
        val content = "a\n- [ ] milk\nb"
        val checkbox = parseMemoChecklist(content)[1]

        assertEquals("a\n- [x] milk\nb", toggleCheckboxIn(content, checkbox.startOffset, checkbox.endOffset, true))
        assertEquals("a\n- [ ] milk\nb", toggleCheckboxIn("a\n- [X] milk\nb", 2, 12, false))
        assertEquals("- [x]", toggleCheckboxIn("- [ ]", 0, 5, true))
    }

    @Test
    fun rejectsNonCheckboxOrNoOpToggle() {
        val content = "plain\n- [ ] milk"
        val textLine = parseMemoChecklist(content)[0]
        val checkbox = parseMemoChecklist(content)[1]

        assertNull(toggleCheckboxIn(content, textLine.startOffset, textLine.endOffset, true))
        assertNull(toggleCheckboxIn(content, checkbox.startOffset, checkbox.endOffset, false))
        assertNull(toggleCheckboxIn(content, 0, content.length + 10, true))
        assertNull(lineAt(content, 0, content.length + 10))
    }

    @Test
    fun handlesCrLfAndIndentedCheckboxes() {
        val content = "x\r\n  - [ ] milk\r\n"
        val checkbox = parseMemoChecklist(content)[1]

        assertEquals(2, checkbox.indentLevel)
        assertEquals("  - [ ] milk", content.substring(checkbox.startOffset, checkbox.endOffset))
        assertEquals(
            "x\r\n  - [x] milk\r\n",
            toggleCheckboxIn(content, checkbox.startOffset, checkbox.endOffset, true)
        )
    }

    @Test
    fun visibleLinesStopsAtBudgetAndReportsTruncation() {
        val lines = parseMemoChecklist("- [ ] a\n- [ ] b\n- [ ] c")

        assertEquals(
            listOf("a", "b"),
            visibleLines(lines, lineBudget = 2, charsPerLine = 100).lines.map { it.text }
        )
        assertTrue(visibleLines(lines, lineBudget = 2, charsPerLine = 100).truncated)
        assertEquals(false, visibleLines(lines, lineBudget = 5, charsPerLine = 100).truncated)
    }

    @Test
    fun visibleLinesHandlesZeroBudgetAndHugeText() {
        val lines = parseMemoChecklist("- [ ] a")

        assertEquals(emptyList<MemoLine>(), visibleLines(lines, lineBudget = 0, charsPerLine = 100).lines)
        assertTrue(visibleLines(lines, lineBudget = 0, charsPerLine = 100).truncated)
        assertEquals(1, visibleLines(lines, lineBudget = 3, charsPerLine = 1).lines.size)
    }
}
