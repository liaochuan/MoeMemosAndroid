# 单条 Memo 小部件 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个绑定单条 memo 的 Glance 小部件，把内容里的复选框渲染为可点的勾选框，点击后写回这条 memo。

**Architecture:** 纯 Kotlin 的 `util/MemoChecklist.kt` 负责解析行、定位偏移、切换勾选与按高度裁剪；`widget/` 下的 Glance 部件只做读取、渲染和派发 `ActionCallback`；配置页沿用现有 `MeoMemosGlanceWidgetConfigurationActivity` 的骨架（`AppLockGate` + `PreferencesGlanceStateDefinition`）。写回统一走 `AbstractMemoRepository.updateMemo`，与 App 内勾选同一条路径。

**Tech Stack:** Kotlin、Jetpack Glance 1.2.0（`CheckBox`、`LocalSize`、`ActionCallback`）、Hilt、Room-backed repository、JUnit 4（纯 JVM 单测）。

**Spec:** `docs/superpowers/specs/2026-10-30-single-memo-widget-design.md`

## Global Constraints

- 单元测试只能用纯 JVM（无 Robolectric、无 `Context`/`Uri`/Room）；部件与配置页的行为靠 `assembleDebug` + 真机/模拟器手测。
- 不改 Room schema，不新增 DAO / 仓库方法，不新增 `Migration`。
- 不改变现有 `MoeMemosGlanceWidget`、`MemoryGlanceWidget` 的行为。
- 新增依赖一律经根 `build.gradle` 的 `ext {}`；本计划不新增依赖。
- 用户可见字符串进 `res/values/strings.xml`，同步补 `values-zh-rCN`、`values-zh-rTW`；其余语言回退英文。
- 风格跟随仓库：Kotlin 官方风格，无 ktlint/spotless；提交信息用 Conventional Commits（`feat:` / `refactor:` / `chore:`）。
- 在独立 worktree 中执行（`superpowers:using-git-worktrees`），每个任务单独提交。
- 构建命令在 Windows 上用 `.\gradlew.bat`。

## Review Focus

- **App 内改过内容后再点桌面勾选框**：偏移可能已指向另一行，必须放弃写入而不是勾错一条（`lineAt` 校验 + `ToggleCheckboxAction`）。
- **远端删除的 memo**：推送成功前本地只有 `isDeleted = true`，离线时长期存在；部件必须显示"已删除"，且勾选不得把 `isDeleted` 重置成 `false` 复活它。
- **围栏代码块里的 `- [ ]`**：是示例代码，必须按文字显示，不能被点。
- **`\r\n`、行首缩进、`[X]`、`- [ ]` 后无文本**：解析与写回不能错位、不能越界。
- **极小的部件尺寸**（2x2 或更扁）：行预算可能为 0，不能崩溃、不能负预算死循环，至少显示一行或省略号。

---

### Task 1: 解析、定位与勾选切换（纯 Kotlin）

**Files:**
- Create: `app/src/main/java/me/mudkip/moememos/util/MemoChecklist.kt`
- Test: `app/src/test/java/me/mudkip/moememos/util/MemoChecklistTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  ```kotlin
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

  fun parseMemoChecklist(content: String): List<MemoLine>
  fun lineAt(content: String, startOffset: Int, endOffset: Int): String?
  fun toggleCheckboxIn(content: String, startOffset: Int, endOffset: Int, checked: Boolean): String?
  fun visibleLines(lines: List<MemoLine>, lineBudget: Int, charsPerLine: Int): VisibleLines
  ```

**规则（实现时按这个来，不要自行扩展）：**
- 复选框行匹配 `^(\s*)([-*+])\s+\[([ xX])\](?:\s(.*))?$`，`text` 取第 4 组（缺失时为 `""`）。
- `startOffset` 是该行首字符下标，`endOffset` 是行尾（不含换行符）；`\r\n` 时 `endOffset` 停在 `\r` 之前。
- 空行 / 仅空白行不产生 `MemoLine`（部件里不保留空段落）。
- 围栏代码块：行首（允许前导空白）以 ```` ``` ```` 或 `~~~` 开头的行切换"围栏内"状态；围栏内所有行都是 `TEXT`，包括开闭围栏行本身。
- `indentLevel = (前导空白折算成空格数 / 2).coerceIn(0, 2)`，`\t` 记 4 个空格。
- `toggleCheckboxIn` 只在区间仍是一整行复选框行时工作：`lineAt` 必须能取到该行且该行匹配复选框正则；`checked` 与现状相同则返回 `null`。写回只替换 `[ ]`/`[x]` 标记里的那一个字符。
- `visibleLines` 从第一行开始累加消耗行数：每行消耗 `ceil(max(1, text.length) / charsPerLine)`，累加超过 `lineBudget` 就停止并置 `truncated = true`；`lineBudget <= 0` 返回空列表。
- `charsPerLine` 由调用方保证 `>= 1`；实现里仍要 `coerceAtLeast(1)` 防除零。

- [ ] **Step 1: 写失败的测试**

  ```kotlin
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

          assertEquals(listOf(MemoLineKind.TEXT, MemoLineKind.CHECKBOX, MemoLineKind.CHECKBOX, MemoLineKind.TEXT), lines.map { it.kind })
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

          assertEquals(listOf(MemoLineKind.TEXT, MemoLineKind.TEXT, MemoLineKind.TEXT, MemoLineKind.CHECKBOX), lines.map { it.kind })
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
          assertEquals("x\r\n  - [x] milk\r\n", toggleCheckboxIn(content, checkbox.startOffset, checkbox.endOffset, true))
      }

      @Test
      fun visibleLinesStopsAtBudgetAndReportsTruncation() {
          val lines = parseMemoChecklist("- [ ] a\n- [ ] b\n- [ ] c")

          assertEquals(listOf("a", "b"), visibleLines(lines, lineBudget = 2, charsPerLine = 100).lines.map { it.text })
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
  ```

- [ ] **Step 2: 跑测试确认失败**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.mudkip.moememos.util.MemoChecklistTest"`
  Expected: 编译失败，`Unresolved reference: parseMemoChecklist`

- [ ] **Step 3: 实现 `util/MemoChecklist.kt`**

  按上面的规则实现 5 个公开符号；解析用一次线性扫描维护 `offset` 与 `inFence`，不要对整段内容反复 `substring`。`toggleCheckboxIn` 先把行文本按正则匹配，再用匹配到的标记组位置在原文里替换单个字符。

- [ ] **Step 4: 跑测试确认通过**

  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "me.mudkip.moememos.util.MemoChecklistTest"`
  Expected: 8 个测试全部 PASS

- [ ] **Step 5: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/util/MemoChecklist.kt app/src/test/java/me/mudkip/moememos/util/MemoChecklistTest.kt
  git commit -m "feat: parse and toggle memo checklist lines"
  ```

---

### Task 2: 部件骨架（receiver、provider、状态键、占位渲染）

**Files:**
- Create: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetKeys.kt`
- Create: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt`
- Create: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidgetReceiver.kt`
- Create: `app/src/main/res/xml/single_memo_glance_widget_info.xml`
- Modify: `app/src/main/AndroidManifest.xml`（加 receiver）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `MemoService.getRepository().getMemo(identifier)`、`context.settingsDataStore`
- Produces:
  ```kotlin
  object SingleMemoWidgetKeys {
      val memoId: Preferences.Key<String>       // "single_memo_id"
      val accountKey: Preferences.Key<String>   // "single_memo_account_key"
      val appWidgetId: Preferences.Key<Int>     // "single_memo_app_widget_id"
      val refreshKey: Preferences.Key<Long>     // "single_memo_refresh_key"
  }

  class SingleMemoGlanceWidget : GlanceAppWidget()
  class SingleMemoGlanceWidgetReceiver : GlanceAppWidgetReceiver()
  ```

- [ ] **Step 1: 写 provider xml**

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
      android:minWidth="110dp"
      android:minHeight="110dp"
      android:minResizeWidth="110dp"
      android:minResizeHeight="110dp"
      android:targetCellWidth="2"
      android:targetCellHeight="3"
      android:updatePeriodMillis="1800000"
      android:initialLayout="@android:layout/simple_list_item_1"
      android:resizeMode="horizontal|vertical"
      android:widgetCategory="home_screen"
      android:description="@string/single_memo_widget_description" />
  ```

  先不加 `android:configure`，Task 3 再加。

- [ ] **Step 2: 写状态键与 receiver**

  `SingleMemoWidgetKeys` 用 `stringPreferencesKey` / `intPreferencesKey` / `longPreferencesKey`，值见上方注释。`SingleMemoGlanceWidgetReceiver` 照抄 `MoeMemosGlanceWidgetReceiver` 的结构：`glanceAppWidget` 指向 `SingleMemoGlanceWidget()`，`onEnabled` 调 `WidgetUpdateScheduler.scheduleWidgetUpdates(context)`，`onDisabled` 调 `cancelWidgetUpdatesIfNoWidgets(context)`，不重复定义 Worker。

- [ ] **Step 3: 写部件骨架**

  `stateDefinition = PreferencesGlanceStateDefinition`；`provideGlance` 里 `EntryPointAccessors.fromApplication(...)` 取 `MemoService`，读 `editGesture`（照 `MoeMemosGlanceWidget` 的写法），然后 `provideContent(createContent(context, memoService, openInEditor))`，composable lambda 留在协程状态机外。

  本步的 `WidgetContent` 只做三件事：
  - `currentState<Preferences>()` 取 `memoId`；为 null 时居中显示 `R.string.single_memo_widget_empty`。
  - 非 null 时在 `LaunchedEffect(memoId, refreshKey)` 中 `withContext(Dispatchers.IO)` 调 `repository.getMemo(memoId)`，把结果放进 `remember { mutableStateOf<MemoEntity?>(null) }`；`null` 或异常时显示 `R.string.memo_not_found`。
  - 有 memo 时用 `Text(memo.content, maxLines = 20)` 直接显示原文（Task 5 再换成逐行渲染）。

  根布局：`Column` + `fillMaxSize` + `background(GlanceTheme.colors.background)` + `padding(12.dp)`，不用 `LazyColumn`。

- [ ] **Step 4: 注册 receiver 与字符串**

  manifest 里加：

  ```xml
  <receiver
      android:name=".widget.SingleMemoGlanceWidgetReceiver"
      android:label="@string/single_memo_widget_title"
      android:exported="true">
      <intent-filter>
          <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
      </intent-filter>
      <meta-data
          android:name="android.appwidget.provider"
          android:resource="@xml/single_memo_glance_widget_info" />
  </receiver>
  ```

  字符串（`values` 英文，zh-rCN / zh-rTW 各补对应翻译）：
  - `single_memo_widget_title`：`Single Memo`
  - `single_memo_widget_description`：`Shows one memo, with its checkboxes.`
  - `single_memo_widget_empty`：`Pick a memo for this widget.`

- [ ] **Step 5: 构建验证**

  Run: `.\gradlew.bat :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetKeys.kt app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidgetReceiver.kt app/src/main/res/xml/single_memo_glance_widget_info.xml app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
  git commit -m "feat: add single memo widget skeleton"
  ```

---

### Task 3: 配置页（从现有 memo 中选择并绑定）

**Files:**
- Create: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetConfigurationActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`（加 activity）、`app/src/main/res/xml/single_memo_glance_widget_info.xml`（加 `android:configure`）
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `SingleMemoWidgetKeys`（Task 2）、`MemoService`、`AccountService`、`getAppWidgetState` / `updateAppWidgetState`
- Produces: `class SingleMemoWidgetConfigurationActivity : FragmentActivity()`，保存时写入 `memoId` / `accountKey` / `appWidgetId`，并更新部件

- [ ] **Step 1: 写 Activity 骨架**

  `@AndroidEntryPoint`，`@Inject lateinit var memoService: MemoService`、`@Inject lateinit var accountService: AccountService`。`appWidgetId` 从 `EXTRA_APPWIDGET_ID` 读取，非法则 `finish()` 并 `return`；先 `setResult(RESULT_CANCELED)`，与现有配置页一致。`setContent { MoeMemosTheme { AppLockGate { ConfigurationScreen() } } }`。

- [ ] **Step 2: 加载账户与 memo 列表**

  `LaunchedEffect(Unit)`：`accountService.currentAccount.first()` 取 `Account?`；`memoService.getRepository().listMemos()` 用 `suspendOnSuccess` 取数据，过滤 `!isDeleted && !archived`，按 `date` 倒序，`take(200)`。同时用 `getAppWidgetState` 读已有绑定并预选。

- [ ] **Step 3: 写 UI**

  `Scaffold` + `TopAppBar`，标题 `R.string.single_memo_widget_title`，导航图标取消（`R.string.cancel`），确认图标（`R.string.confirm`）。内容区 `LazyColumn`：
  - 第一项：`OutlinedTextField`（占位 `R.string.single_memo_widget_new_memo_hint`）+ 按钮 `R.string.single_memo_widget_create_and_bind`。本步按钮只负责校验非空、留空操作（Task 4 实现创建）。
  - 第二项：标题 `R.string.single_memo_widget_pick_memo` + `OutlinedTextField` 搜索框（`R.string.single_memo_widget_search_hint`），输入按 `content.contains(query, ignoreCase = true)` 过滤。
  - 其余项：每条 memo 一行，显示 `DateUtils.getRelativeTimeSpanString(...)` 与两行截断内容；`Modifier.clickable { selectedMemoId = memo.identifier }`，选中项加 `Icon(Icons.Default.Check)`。列表为空时显示 `R.string.no_memos`。

- [ ] **Step 4: 保存**

  `private fun saveConfig(identifier: String? = null)`：在 `lifecycleScope.launch` 里取 `glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)`，`identifier ?: selectedMemoId` 为空则返回；`updateAppWidgetState` 写入 `memoId`、`accountKey = account?.accountKey()`、`appWidgetId`，bump `refreshKey`，再 `SingleMemoGlanceWidget().update(this, glanceId)`，`setResult(RESULT_OK, Intent().putExtra(EXTRA_APPWIDGET_ID, appWidgetId))`，`finish()`。`selectedMemoId == null` 时确认按钮禁用。

- [ ] **Step 5: 注册 activity 并挂到 provider**

  manifest：

  ```xml
  <activity
      android:name=".widget.SingleMemoWidgetConfigurationActivity"
      android:exported="true"
      android:theme="@style/Theme.MoeMemos">
      <intent-filter>
          <action android:name="android.appwidget.action.APPWIDGET_CONFIGURE" />
      </intent-filter>
  </activity>
  ```

  `single_memo_glance_widget_info.xml` 加 `android:configure="me.mudkip.moememos.widget.SingleMemoWidgetConfigurationActivity"`。

  字符串：`single_memo_widget_pick_memo`（`Pick an existing memo`）、`single_memo_widget_search_hint`（`Search memos`）、`single_memo_widget_new_memo_hint`（`Write a memo to pin…`）、`single_memo_widget_create_and_bind`（`Create and bind`），各补 zh 翻译。

- [ ] **Step 6: 构建 + 手测**

  Run: `.\gradlew.bat :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

  手测（真机或模拟器）：安装 debug APK → 桌面添加"Single Memo" → 配置页选中一条含 `- [ ]` 的 memo → 确认 → 部件显示该 memo 原文。记录实际结果。

- [ ] **Step 7: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetConfigurationActivity.kt app/src/main/AndroidManifest.xml app/src/main/res/xml/single_memo_glance_widget_info.xml app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
  git commit -m "feat: configure single memo widget by picking a memo"
  ```

---

### Task 4: 配置页内新建 memo 并绑定

**Files:**
- Modify: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetConfigurationActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `AbstractMemoRepository.createMemo(content, visibility, resources, tags, deferPush, location)`、`Account.toUser().defaultVisibility`
- Produces: 配置页"创建并绑定"可用；无新增公开符号

- [ ] **Step 1: 实现创建并绑定**

  `createAndBind()`：`lifecycleScope.launch` 内用 `newMemoContent.trim()`；空则返回。可见性取 `account?.toUser()?.defaultVisibility ?: MemoVisibility.PRIVATE`。调用 `memoService.getRepository().createMemo(content, visibility, emptyList(), emptyList())`，`suspendOnSuccess` 里 `saveConfig(identifier = data.identifier)`（Task 3 已定义成接受可空 identifier）。失败时在输入框下方显示 `R.string.error_unknown`（`mutableStateOf<String?>`），不清空输入。

- [ ] **Step 2: 构建 + 手测**

  Run: `.\gradlew.bat :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

  手测：添加部件 → 输入 `- [ ] 买牛奶` → 创建并绑定 → 部件显示这条内容；App 的 memo 列表里出现同一条；断网重复一次，同样成功（本地插入）。

- [ ] **Step 3: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/widget/SingleMemoWidgetConfigurationActivity.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
  git commit -m "feat: create and bind a memo from the widget configuration"
  ```

---

### Task 5: 逐行渲染 + 勾选框写回

**Files:**
- Modify: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `parseMemoChecklist`、`visibleLines`、`lineAt`、`toggleCheckboxIn`（Task 1）；`SingleMemoWidgetKeys`（Task 2）
- Produces:
  ```kotlin
  class ToggleCheckboxAction : ActionCallback

  object ToggleCheckboxKeys {
      val memoId: ActionParameters.Key<String>
      val accountKey: ActionParameters.Key<String>
      val startOffset: ActionParameters.Key<Int>
      val endOffset: ActionParameters.Key<Int>
      val targetChecked: ActionParameters.Key<Boolean>
      val expectedLine: ActionParameters.Key<String>
  }
  ```

- [ ] **Step 1: 替换正文渲染**

  头部保留 Task 2 的时间行，加私有锁图标 / 置顶图标，照 `MoeMemosGlanceWidget.MemoItem` 的写法。

  正文：
  ```kotlin
  val size = LocalSize.current
  val lineBudget = ((size.height.value - 24f - 20f - 8f) / 20f).toInt().coerceAtLeast(1)
  val charsPerLine = ((size.width.value - 24f - 24f) / 7f).toInt().coerceAtLeast(8)
  val visible = visibleLines(parseMemoChecklist(memo.content), lineBudget, charsPerLine)
  ```

  逐行渲染：
  - `TEXT` → `Text(line.text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp), maxLines = 1)`，`indentLevel > 0` 时加 `padding(left = (8 * line.indentLevel).dp)`。
  - `CHECKBOX` → Glance 的 `CheckBox(checked = line.checked, onCheckedChange = actionRunCallback<ToggleCheckboxAction>(...), text = line.text, style = ..., maxLines = 1, key = "${memo.identifier}:${line.startOffset}")`，同样加缩进。`onCheckedChange` 传参用 `actionParametersOf`：`memoId`、`accountKey`、`startOffset`、`endOffset`、`targetChecked = !line.checked`、`expectedLine = memo.content.substring(line.startOffset, line.endOffset)`。
  - `visible.truncated` 时追加一个 `Text("…")`（`translatable="false"` 不需要字符串资源，直接字面量）。
  - memo 内容为空：`Text(R.string.single_memo_widget_empty_memo)`。

  整块正文外面套 `clickable(actionStartActivity(createMemoIntent(context, memo.identifier, openInEditor)))`，勾选框自己的点击优先，不受影响。

- [ ] **Step 2: 实现 `ToggleCheckboxAction`**

  `onAction` 里：取 6 个参数，缺任意一个直接 `update` 后返回。校验 `parameters[accountKey] == context.settingsDataStore.data.first().currentUser`，不等则只刷新。`repository.getMemo(memoId)`，为 null / `isDeleted` / `archived` 则只刷新（Task 6 会换成显式"已删除"渲染，这里先保证不写）。`lineAt(content, start, end) != expectedLine` 时只刷新。`toggleCheckboxIn(content, start, end, targetChecked)`：null 则只刷新；否则 `repository.updateMemo(memoId, content = newContent)`（`resources`、`visibility` 传 null 保持原值）。最后 `updateAppWidgetState` bump `refreshKey` + `SingleMemoGlanceWidget().update(context, glanceId)`；整段 try/catch 记 `Timber.tag("SingleMemoWidget")`。

- [ ] **Step 3: 字符串**

  `single_memo_widget_empty_memo`：`Empty memo.`（+ zh 翻译）。

- [ ] **Step 4: 构建 + 手测**

  Run: `.\gradlew.bat :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

  手测：部件里 `- [ ] 买牛奶` 显示为勾选框 + "买牛奶"；点击后部件与 App 内该 memo 都变成 `- [x] 买牛奶`；再点一次变回；断网点击也立即生效，恢复网络后服务器内容一致；代码块里的 `- [ ]` 显示为文字且点不动；把部件拉到 2x2，内容被截断、出现"…"、不崩溃。

- [ ] **Step 5: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
  git commit -m "feat: render and toggle memo checkboxes in the single memo widget"
  ```

---

### Task 6: 丢失态、重选入口与定时刷新接入

**Files:**
- Modify: `app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt`
- Modify: `app/src/main/java/me/mudkip/moememos/widget/WidgetUpdateScheduler.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`

**Interfaces:**
- Consumes: `SingleMemoWidgetKeys.appWidgetId`（Task 2）、`SingleMemoWidgetConfigurationActivity`（Task 3）
- Produces: 无新增公开符号

- [ ] **Step 1: 判定丢失并渲染提示**

  `LaunchedEffect` 里把状态拆成 `memo: MemoEntity?` 与 `missing: Boolean`：`memoId == null`、账户不匹配、`getMemo == null`、`isDeleted`、`archived` 都置 `missing = true`。`missing` 时居中显示 `R.string.single_memo_widget_missing`，整块 `clickable(actionStartActivity(createConfigureIntent(context, appWidgetId)))`，其中

  ```kotlin
  private fun createConfigureIntent(context: Context, appWidgetId: Int): Intent =
      Intent(context, SingleMemoWidgetConfigurationActivity::class.java).apply {
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
  ```

  `appWidgetId` 从 `currentState<Preferences>()[SingleMemoWidgetKeys.appWidgetId]` 读，缺失时提示文字不可点。

- [ ] **Step 2: 接入统一刷新与定时任务**

  `WidgetUpdateScheduler.updateAllWidgets`：取 `manager.getGlanceIds(SingleMemoGlanceWidget::class.java)`，对每个 id bump `refreshKey` 后 `SingleMemoGlanceWidget().update(context, glanceId)`（与列表部件同样处理）。`cancelWidgetUpdatesIfNoWidgets`：把 `hasSingleMemoWidget` 加进判断条件。

- [ ] **Step 3: 字符串**

  `single_memo_widget_missing`：`Memo deleted. Tap to choose again.`（+ zh 翻译）。

- [ ] **Step 4: 构建 + 手测**

  Run: `.\gradlew.bat :app:assembleDebug`
  Expected: BUILD SUCCESSFUL

  手测：在 App 里删除部件绑定的 memo（断网状态下删，确认 `isDeleted` 行仍在）→ 部件显示"Memo deleted. Tap to choose again."，点它回到配置页；重选另一条后部件恢复显示。切换账户 → 同样显示提示。在 App 里改这条 memo 的勾选 → 回到桌面部件内容跟着变。

- [ ] **Step 5: 跑全量单测**

  Run: `.\gradlew.bat :app:testDebugUnitTest`
  Expected: 全部 PASS（含 Task 1 的 8 个测试）

- [ ] **Step 6: 提交**

  ```bash
  git add app/src/main/java/me/mudkip/moememos/widget/SingleMemoGlanceWidget.kt app/src/main/java/me/mudkip/moememos/widget/WidgetUpdateScheduler.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml
  git commit -m "feat: handle missing memos and periodic refresh in the single memo widget"
  ```

---

## 收尾

- [ ] 按 spec 的"验收"逐条手测并记录结果。
- [ ] 若需要版本号与 changelog，按仓库约定单独提交（`chore: bump to X.Y.Z` + `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`），不混进功能提交。
- [ ] 汇报时给出：新增文件清单、`testDebugUnitTest` 结果、手测覆盖项与未覆盖项。
