# Clear Completed Todos Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "清除已完成待办" (Clear completed todos) action to the memo three-dot menu that deletes every checked `- [x]` line from that memo.

**Architecture:** A pure, unit-tested helper in `util/MemoChecklist.kt` computes the completed-item count and the rewritten content. `MemosCardActionButton` shows the menu item only when the count is above zero, asks for confirmation, then writes the rewritten content through the existing `MemosViewModel.editMemo` path.

**Tech Stack:** Kotlin, Jetpack Compose Material3, JUnit4 (pure JVM unit tests).

**Spec:** Approved in chat on 2026-11-05: per-memo scope, delete the lines, confirmation dialog required, menu label "清除已完成待办".

## Global Constraints

- Kotlin official code style; no ktlint/spotless/detekt.
- User-visible strings go in `values`, `values-zh-rCN`, and `values-zh-rTW` in the same change.
- No new dependencies.
- Do not commit; the project's dev rules keep commits with the user.
- Memo edits must go through `MemosViewModel.editMemo(identifier, content, resources, visibility)` so sync and widget refresh keep working.

## Review Focus

- Memo containing only a fenced code block with `- [x]`: the menu item must stay hidden and content must not change.
- CRLF memo where the last line is completed: deletion must not leave a stray `\r` or a dangling blank line.
- Memo whose only line is completed: result is empty content, still written once (no crash, no repeated dialog).
- Completed lines adjacent to each other, including indented ones: all are removed in one write.
- Writing while offline / on a remote account: existing `needsSync` behaviour is unchanged.

---

### Task 1: Checklist rewrite helpers

**Files:**
- Modify: `app/src/main/java/me/mudkip/moememos/util/MemoChecklist.kt`
- Test: `app/src/test/java/me/mudkip/moememos/util/MemoChecklistTest.kt`

**Interfaces:**
- Consumes: `parseMemoChecklist(content: String): List<MemoLine>`, `MemoLineKind`, `MemoLine.checked`, `MemoLine.startOffset`, `MemoLine.endOffset`
- Produces:
  - `fun completedChecklistCount(content: String): Int` — number of checked checkbox lines outside fenced code blocks.
  - `fun removeCompletedChecklistLines(content: String): String?` — content with every checked checkbox line (line break included) removed; `null` when there is nothing to remove.

- [ ] **Step 1: Write the failing tests**

Add to `MemoChecklistTest.kt`:

```kotlin
@Test
fun countsOnlyCheckedCheckboxLines() {
    assertEquals(2, completedChecklistCount("a\n- [x] one\n- [ ] two\n* [X] three"))
    assertEquals(0, completedChecklistCount("plain"))
    assertEquals(0, completedChecklistCount("```\n- [x] code\n```\n- [ ] real"))
}

@Test
fun removesCompletedCheckboxLinesAndTheirLineBreaks() {
    assertEquals(
        "a\n- [ ] two\nb",
        removeCompletedChecklistLines("a\n- [x] one\n- [ ] two\nb")
    )
    assertEquals(
        "a\r\n- [ ] two\r\nb",
        removeCompletedChecklistLines("a\r\n- [X] one\r\n- [ ] two\r\nb")
    )
    assertEquals("a", removeCompletedChecklistLines("a\n  - [x] indented"))
    assertEquals("", removeCompletedChecklistLines("- [x] only"))
}

@Test
fun returnsNullWhenNothingCompleted() {
    assertNull(removeCompletedChecklistLines("a\n- [ ] open"))
    assertNull(removeCompletedChecklistLines("```\n- [x] code\n```"))
    assertNull(removeCompletedChecklistLines(""))
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.mudkip.moememos.util.MemoChecklistTest" "-PpreferSettingsRepos" "-Dorg.gradle.java.home=E:\Code\SDK\jdk\corretto-17.0.17" "-Dorg.gradle.java.installations.auto-download=false"`
Expected: compilation fails with unresolved references to `completedChecklistCount` / `removeCompletedChecklistLines`.

- [ ] **Step 3: Implement the helpers**

Append to `MemoChecklist.kt`:

```kotlin
/** Number of checked checkbox lines, ignoring fenced code blocks. */
fun completedChecklistCount(content: String): Int =
    parseMemoChecklist(content).count { it.kind == MemoLineKind.CHECKBOX && it.checked }

/**
 * Removes every checked checkbox line together with its line break. Returns null when nothing was
 * checked, so callers can treat null as "nothing to write".
 *
 * When a removed line is the last non-terminated line, the preceding line break is removed too, so
 * clearing the tail of a memo does not leave a dangling empty line.
 */
fun removeCompletedChecklistLines(content: String): String? {
    val completed = parseMemoChecklist(content)
        .filter { it.kind == MemoLineKind.CHECKBOX && it.checked }
    if (completed.isEmpty()) return null

    var result = content
    for (line in completed.asReversed()) {
        val end = line.endOffset
        val lineEnd = when {
            content.getOrNull(end) == '\r' && content.getOrNull(end + 1) == '\n' -> end + 2
            content.getOrNull(end) == '\n' -> end + 1
            else -> end
        }
        val start = if (lineEnd == content.length) {
            when {
                line.startOffset >= 2 && content.startsWith("\r\n", line.startOffset - 2) -> line.startOffset - 2
                line.startOffset >= 1 && content[line.startOffset - 1] == '\n' -> line.startOffset - 1
                else -> line.startOffset
            }
        } else {
            line.startOffset
        }
        result = result.removeRange(start, lineEnd)
    }
    return result
}
```

- [ ] **Step 4: Run the tests and watch them pass**

Run the same command as Step 2.
Expected: `BUILD SUCCESSFUL`, all `MemoChecklistTest` tests pass.

### Task 2: Menu entry and confirmation dialog

**Files:**
- Modify: `app/src/main/java/me/mudkip/moememos/ui/component/MemosCard.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `completedChecklistCount` and `removeCompletedChecklistLines` from Task 1; `MemosViewModel.editMemo`.
- Produces: no public API.

- [ ] **Step 1: Add strings**

`values/strings.xml`:

```xml
<string name="clear_completed_tasks">Clear completed tasks</string>
<plurals name="clear_completed_tasks_confirm">
    <item quantity="one">%1$d completed task will be removed from this memo.</item>
    <item quantity="other">%1$d completed tasks will be removed from this memo.</item>
</plurals>
```

`values-zh-rCN/strings.xml`:

```xml
<string name="clear_completed_tasks">清除已完成待办</string>
<plurals name="clear_completed_tasks_confirm">
    <item quantity="other">将从这条 MEMO 中删除 %1$d 条已完成待办。</item>
</plurals>
```

`values-zh-rTW/strings.xml`: same Traditional Chinese copy, using the file's existing terms (`MEMO`/`備忘`). Use 清除已完成待辦 and 將從這則 MEMO 中刪除 %1$d 條已完成待辦。

- [ ] **Step 2: Wire the menu item and dialog**

In `MemosCardActionButton`, add state and helpers:

```kotlin
val completedCount = remember(memo.content) { completedChecklistCount(memo.content) }
var showClearCompletedDialog by remember { mutableStateOf(false) }
```

Insert a `DropdownMenuItem` after Copy / Copy link and before Archive, guarded by `if (completedCount > 0)`:

```kotlin
DropdownMenuItem(
    text = { Text(R.string.clear_completed_tasks.string) },
    onClick = {
        showClearCompletedDialog = true
        menuExpanded = false
    },
    leadingIcon = {
        Icon(Icons.Outlined.ClearAll, contentDescription = null)
    })
```

Add the confirmation `AlertDialog` next to the existing delete dialog:

```kotlin
if (showClearCompletedDialog) {
    AlertDialog(
        onDismissRequest = { showClearCompletedDialog = false },
        title = { Text(R.string.clear_completed_tasks.string) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.clear_completed_tasks_confirm,
                    completedCount,
                    completedCount
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newContent = removeCompletedChecklistLines(memo.content)
                    if (newContent == null) {
                        showClearCompletedDialog = false
                    } else {
                        scope.launch {
                            memosViewModel.editMemo(
                                memo.identifier,
                                newContent,
                                memo.resources,
                                memo.visibility
                            ).suspendOnSuccess {
                                showClearCompletedDialog = false
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(R.string.clear.string)
            }
        },
        dismissButton = {
            TextButton(onClick = { showClearCompletedDialog = false }) {
                Text(R.string.cancel.string)
            }
        }
    )
}
```

Imports: `androidx.compose.material.icons.outlined.ClearAll`, `androidx.compose.ui.res.pluralStringResource`, and `me.mudkip.moememos.util.completedChecklistCount` / `me.mudkip.moememos.util.removeCompletedChecklistLines`.

- [ ] **Step 3: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin "-PpreferSettingsRepos" "-Dorg.gradle.java.home=E:\Code\SDK\jdk\corretto-17.0.17" "-Dorg.gradle.java.installations.auto-download=false"`
Expected: `BUILD SUCCESSFUL`.

### Task 3: README and verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add the README bullet**

Under "相对上游的修改点", add one bullet in the feature group: memo 三点菜单新增「清除已完成待办」，仅在该 memo 存在已勾选复选框时显示，确认后删除这些行并走正常同步。

- [ ] **Step 2: Run the full unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest "-PpreferSettingsRepos" "-Dorg.gradle.java.home=E:\Code\SDK\jdk\corretto-17.0.17" "-Dorg.gradle.java.installations.auto-download=false"`
Expected: `BUILD SUCCESSFUL`, 0 failures.

- [ ] **Step 3: Review the diff**

Run: `git diff` and confirm only the six intended files changed, no debug leftovers, and the conditional menu item cannot appear for fenced code.
