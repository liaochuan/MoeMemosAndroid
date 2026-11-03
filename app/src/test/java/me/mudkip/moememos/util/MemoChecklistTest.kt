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
        assertEquals(listOf("本周采购", "鸡蛋", "面包", "备注"), lines.map { it.text })
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
            visibleLines(lines, lineBudget = 2).lines.map { it.text }
        )
        assertTrue(visibleLines(lines, lineBudget = 2).truncated)
        assertEquals(false, visibleLines(lines, lineBudget = 5).truncated)
    }

    @Test
    fun visibleLinesHandlesZeroBudgetAndHugeText() {
        val lines = parseMemoChecklist("- [ ] a")

        assertEquals(emptyList<MemoLine>(), visibleLines(lines, lineBudget = 0).lines)
        assertTrue(visibleLines(lines, lineBudget = 0).truncated)
        assertEquals(1, visibleLines(lines, lineBudget = 3).lines.size)
    }

    @Test
    fun visibleLinesKeepsAVeryLongLineWhole() {
        // Every entry costs one rendered line, so a long line must not eat the whole budget and
        // leave the widget showing nothing but the truncation marker.
        val lines = parseMemoChecklist("- [ ] " + "x".repeat(10_000))

        val visible = visibleLines(lines, lineBudget = 1)

        assertEquals(1, visible.lines.size)
        assertEquals(false, visible.truncated)
    }

    @Test
    fun parsesEmptyContentAsNoLines() {
        assertEquals(emptyList<MemoLine>(), parseMemoChecklist(""))
    }

    @Test
    fun treatsInlineCheckboxMarkersAsText() {
        val lines = parseMemoChecklist("a - [ ] b")

        assertEquals(listOf(MemoLineKind.TEXT), lines.map { it.kind })
        assertEquals("a - [ ] b", lines[0].text)
    }

    @Test
    fun doesNotCloseATildeFenceWithBackticks() {
        val content = "~~~\n- [ ] in fence\n```\n- [ ] still fenced\n~~~\n- [ ] real task"
        val lines = parseMemoChecklist(content)

        assertEquals(
            listOf(
                MemoLineKind.TEXT,
                MemoLineKind.TEXT,
                MemoLineKind.TEXT,
                MemoLineKind.TEXT,
                MemoLineKind.TEXT,
                MemoLineKind.CHECKBOX
            ),
            lines.map { it.kind }
        )
        assertEquals("real task", lines.last().text)
    }

    @Test
    fun togglesUppercaseMarker() {
        val content = "- [X] milk"

        assertEquals("- [ ] milk", toggleCheckboxIn(content, 0, content.length, false))
    }

    @Test
    fun lineAtAcceptsContentEndingWithBareCarriageReturn() {
        assertEquals("a", lineAt("a\r", 0, 1))
    }

    @Test
    fun stripsHeadingAndQuoteMarkersForDisplay() {
        val lines = parseMemoChecklist("### 待办\n> 引用\n- [ ] milk")

        assertEquals(listOf("待办", "引用", "milk"), lines.map { it.text })
        assertEquals(listOf(3, 0, 0), lines.map { it.headingLevel })
    }

    @Test
    fun keepsHeadingMarkersInsideCodeFences() {
        val lines = parseMemoChecklist("```\n### 待办\n```")

        assertEquals(listOf("```", "### 待办", "```"), lines.map { it.text })
        assertEquals(listOf(0, 0, 0), lines.map { it.headingLevel })
    }

    @Test
    fun smallWidgetDropsTheHeaderAndStillShowsSeveralLines() {
        assertEquals(false, widgetShowsHeader(110f))
        assertEquals(4, widgetLineBudget(110f))
        assertEquals(true, widgetShowsHeader(220f))
        assertEquals(9, widgetLineBudget(220f))
        assertEquals(1, widgetLineBudget(0f))
    }
}
