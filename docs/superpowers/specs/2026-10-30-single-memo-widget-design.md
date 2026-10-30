# 单条 Memo 小部件 设计

日期：2026-10-30
状态：待实现

## 背景

`app/src/main/java/me/mudkip/moememos/widget/` 下现有两个 Glance 小部件：

- `MoeMemosGlanceWidget`：2x3 起步、可横竖调整，配置页选标签 / 仅置顶 / 条数，列表展示多条 memo。
- `MemoryGlanceWidget`：随机展示一条 memo 的"回忆"。

两者都直接把 `memo.content` 当纯文本交给 `Text`，所以 `- [ ] 买牛奶` 会原样显示为这串 markdown 字符。

用户的需求：桌面上放一条固定 memo 当待办板，抬头就能看到，勾一下就能完成。所以需要一个绑定了单条 memo 的新部件，复选框渲染成真正的勾选框，点击后真的修改这条 memo。

## 目标

- 新增第三个独立小部件（不改变现有两个的行为），创建时绑定一条 memo。
- 展示这条 memo 的全部内容：文字按原顺序显示，复选框行渲染为勾选框。
- 点击勾选框写回这条 memo（本地立即生效、标记待同步），走与 App 内勾选相同的写入路径。
- 默认 2x3，可在桌面自由拉伸；内容不滚动，放不下的部分省略。
- 部件绑定的 memo 不存在时，显示"已删除"提示并允许重新选择。

## 非目标

- 不改数据库 schema、不新增同步逻辑。
- 不渲染图片 / 附件资源（`memo.resources` 忽略），不渲染 Markdown 富文本（粗体、标题、链接、代码块保持原文）。
- 不支持在一个部件里聚合多条 memo、不支持标签过滤（已有列表部件负责）。
- 部件内不做文本编辑，只做勾选切换。

## 用户可见行为

### 创建与配置

添加小部件时启动配置页（`SingleMemoWidgetConfigurationActivity`）：

1. 顶部输入框 + "创建并绑定"：输入内容后确认，立即 `createMemo` 得到新 memo 并绑定给这个部件，不离开配置页。
2. 下方"选择已有 memo"：按时间倒序的 memo 列表（带搜索框，内存过滤，最多取 200 条），点击选中一条，选中态可见。
3. 确认后写入部件状态、刷新部件、`RESULT_OK` 结束。

新建 memo 的可见性取当前账户的默认可见性（`settings.usersList[currentUser].settings.defaultVisibility`），与 `MemoInputPage` 一致。

memo 丢失后重新进入配置页时，预填当前绑定项，且允许切换到别的 memo 或新建；此时不创建重复绑定。

### 展示

- 顶部一行：相对时间（`DateUtils.getRelativeTimeSpanString`），私有 memo 显示锁图标，置顶显示 pin 图标，与现有部件一致。
- 正文：按行解析 memo 内容，按原顺序渲染。
  - 文字行 → 单行 `Text`（超宽省略号）。
  - 复选框行 → Glance 勾选框 + 单行文本，保留列表缩进（每级 8dp，最多 2 级）。
- 尺寸：`targetCellWidth=2`、`targetCellHeight=3`、`minWidth/minHeight=110dp`、`resizeMode="horizontal|vertical"`。
- 内容超出可视高度：按可用高度估算行预算（`LocalSize` + 每行约 20dp），超出后停止渲染并追加一个"…"。
- memo 正文为空：显示"无内容"占位。
- 加载中 / 读取异常：沿用现有部件的 `loading` / `error_unknown` 文案布局。

### 勾选

- 点击某行的勾选框 → `ActionCallback`（带该行在原文中的字符区间参数）。
- 读取当前 memo（`repository.getMemo(identifier)`），在区间内把 `[ ]` ↔ `[x]` 互换（`[X]` 也视为已勾选，取消勾选统一写成 `[ ]`），再 `repository.updateMemo(identifier, content = 新内容)`。
- 与 App 内一致：本地先生效、`needsSync` 置位，离线时由既有同步机制补推。
- 成功后立刻刷新该部件（bump `refreshKey` 再 `update`）。
- 写回失败时也刷新部件（显示仓库里的真实状态），并记录 `Timber` 日志，不弹窗、不弹 Toast。

### 点击其他区域

打开 App 并进入这条 memo：`ACTION_VIEW_MEMO` / `ACTION_EDIT_MEMO`（按用户 `editGesture` 设置），携带 `EXTRA_MEMO_ID`，与现有部件一致。

### memo 丢失

判定"丢失"的条件有两个：`getMemo(identifier)` 返回 null，或返回的行 `isDeleted == true` / `archived == true`（远端删除在推送成功前只是打标记，离线时能停留很久；归档后 App 主列表也不再显示）。此时：

- 正文区域显示"memo 已删除，点击重新选择"。
- 点击该区域打开配置页（带 `EXTRA_APPWIDGET_ID`），可重新选择或新建 memo。
- 顶部时间行不渲染。
- `ToggleCheckboxAction` 遇到这类 memo 直接放弃写入（只刷新部件）。否则 `SyncingRepository.updateMemo` 会把 `isDeleted` 重置为 false，等于在部件上点一下就把已删除的 memo 复活。

### 账户切换

部件状态里记录绑定时的 `accountKey`。当前活动账户与绑定账户不一致时，按"memo 丢失"处理（显示提示 + 可重选），不跨账户读取同 identifier 的数据。

## 技术设计

### 新增文件

- `widget/SingleMemoGlanceWidget.kt`：部件主体 + 勾选 `ActionCallback` + 意图构造。
- `widget/SingleMemoGlanceWidgetReceiver.kt`：`GlanceAppWidgetReceiver`。
- `widget/SingleMemoWidgetConfigurationActivity.kt`：配置页（`FragmentActivity` + `@AndroidEntryPoint` + `AppLockGate` + `MoeMemosTheme`）。
- `widget/SingleMemoWidgetState.kt`：`object SingleMemoWidgetKeys`（`memoId`、`accountKey`、`refreshKey`）。
- `res/xml/single_memo_glance_widget_info.xml`：`appwidget-provider`，含 `android:configure`。
- `util/MemoChecklist.kt`：纯 Kotlin 的行解析与勾选切换（不依赖 Android API，可放 JVM 单元测试）。

### 修改文件

- `app/src/main/AndroidManifest.xml`：新增 receiver 与配置 activity（`APPWIDGET_CONFIGURE` intent-filter）。
- `widget/WidgetUpdateScheduler.kt`：`updateAllWidgets` 里加入 `SingleMemoGlanceWidget`（bump `refreshKey` 后 `update`）；`cancelWidgetUpdatesIfNoWidgets` 把它算作"有小部件"。
- `res/values/strings.xml`：小部件标题、配置页文案、删除态文案等；同步补 `values-zh-rCN`、`values-zh-rTW`，其余语言按既有机制回退英文，不在本次全量翻译。
- `app/build.gradle` / `fastlane`：版本号与 changelog 按仓库约定，在发布时处理（不属于本次功能提交的必需项）。

### 部件状态

使用 `PreferencesGlanceStateDefinition`（与现有部件一致）：

```kotlin
object SingleMemoWidgetKeys {
    val memoId = stringPreferencesKey("memo_id")
    val accountKey = stringPreferencesKey("account_key")
    val refreshKey = longPreferencesKey("refresh_key")
}
```

`provideGlance` 里拿到依赖后调用 `provideContent(createContent(...))`，composable lambda 保持在协程状态机之外（沿用现有注释所述的栈映射约束）。

数据加载放 `LaunchedEffect(memoId, accountKey, refreshKey)`，`Dispatchers.IO` 中执行，避免每次重绘都打网络。`WidgetUpdater.updateWidgets` 已由各 ViewModel 在增删改后调用，会 bump `refreshKey`，从而带动本部件刷新；定时刷新沿用现有的 30 分钟 WorkManager。

### 解析与切换（`util/MemoChecklist.kt`）

```kotlin
data class MemoLine(
    val kind: Kind,          // TEXT | CHECKBOX
    val text: String,        // 去掉标记后的显示文本
    val checked: Boolean,    // 复选框状态，TEXT 行为 false
    val startOffset: Int,    // 在原文中的区间（含标记），供写回用
    val endOffset: Int,
    val indentLevel: Int,    // 0..2，最多按 2 级缩进渲染
)

fun parseMemoChecklist(content: String): List<MemoLine>
fun toggleCheckboxIn(content: String, startOffset: Int, endOffset: Int, checked: Boolean): String?
```

- 行匹配：`^(\s*)([-*+])\s+\[([ xX])\]\s?(.*)$`，整行区间为 `startOffset..endOffset`（不含换行符）。
- 围栏代码块（``` / ~~~ 开闭）内部的行一律按文字处理，不解析成可勾选项——代码块里的 `- [ ]` 是示例代码，点一下改到内容里显然不对。扫描器需要按行维护"是否在围栏内"的状态。
- 仅替换区间内的标记字符（`[ ]` → `[x]` / `[x]`/`[X]` → `[ ]`），其余字节不变，避免误伤正文中同名文本。
- 兼容 `\r\n`（离线导入的 memo 可能带 CRLF），偏移量按原始字符串计算。
- `parseMemoChecklist` 对空内容返回空列表。
- `toggleCheckboxIn` 在区间不是合法复选框行、或 `checked` 与当前状态无变化时返回 null（调用方据此只刷新、不写作）。
- 行预算裁剪与折行估算由部件层完成，解析器保持纯函数。

### 渲染

- 勾选框使用 `androidx.glance.appwidget.CheckBox`（Glance 1.2.0 已提供）。若实测该 API 在 1.2.0 不可用，退化为 `ImageProvider`（勾选/未勾选两张 drawable）+ `clickable(actionRunCallback<ToggleCheckboxAction>())`，行为不变。
- 文字与复选框都用固定行高（`TextStyle(fontSize = 14.sp)`，行高约 20dp）参与预算计算，确保渲染结果与估算一致。
- 根布局 `Column` + `fillMaxSize` + `background(GlanceTheme.colors.background)` + `padding(12.dp)`，不滚动（不使用 `LazyColumn`）。

### 勾选回调

```kotlin
class ToggleCheckboxAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // parameters: memoId, accountKey, startOffset, endOffset, checked, expectedLine
        // 1. memoId/accountKey 与当前账户校验
        // 2. repository.getMemo(memoId) 取最新内容；null / isDeleted / archived 则放弃
        // 3. 校验 [startOffset, endOffset) 处的内容仍等于 expectedLine（渲染时的该行原文）。
        //    不等说明期间 App 内或同步改过内容，偏移已失效，直接放弃写入
        // 4. toggleCheckboxIn(...) + repository.updateMemo(...)
        // 5. bump refreshKey + update widget
    }
}
```

以仓库里的最新内容为基准做替换，而不是用部件渲染时的快照，避免在 App 里改过之后勾选把旧内容覆盖回去；`expectedLine` 校验再挡一层"偏移已经指向别的行"的情况——否则会静默勾错一条。

### 配置页

- 复用 `MeoMemosGlanceWidgetConfigurationActivity` 的骨架：`AppLockGate`、`viewLifecycleOwner.lifecycleScope` 写状态、`GlanceAppWidgetManager.getGlanceIdBy(appWidgetId)`。
- 输入框用 `OutlinedTextField`，确认按钮在输入非空或已选中 memo 时可用。
- 已有 memo 列表用 `LazyColumn`，每项显示相对时间 + 两行截断内容；点击即选中，再点确认保存。
- 保存：`updateAppWidgetState` 写入 `memoId` / `accountKey`，`SingleMemoGlanceWidget().update(context, glanceId)`，`setResult(RESULT_OK)`。

### 测试

- `app/src/test/.../util/MemoChecklistTest.kt`（纯 JVM）：
  - 混合内容（文字 + `- [ ]` + `- [x]` + `* [ ]`）解析出正确顺序、偏移与状态。
  - 勾选 / 取消勾选后只有标记变化，正文与换行不变。
  - `[X]` 视为已勾选，取消后为 `[ ]`。
  - 无复选框、空内容、CRLF 内容、行首空白缩进。
  - 围栏代码块里的 `- [ ]` 保持文字，不产生可勾选项。
  - 区间越界 / 非复选框行 / 状态未变化时 `toggleCheckboxIn` 返回 null。
  - `isDeleted` / `archived` 的 memo 由部件层判为"丢失"，不进入写入路径。
- 部件渲染与配置页属于 Android 侧，依赖 `androidTest` 或真机手测（仓库无 Robolectric）；本次至少手测：新建绑定、选择绑定、勾选、离线勾选、删除 memo 后提示、拉伸尺寸、账户切换。

## 边界与取舍（我的判断）

- 文字行单行 + 省略号：换来可预测的高度预算；长段落在部件里本来也读不完，点进 App 看更合适。
- 不显示应用图标与"Memos"标题，把有限空间留给内容；部件本身在桌面上已有名称。
- 列表部件（`MoeMemosGlanceWidget`）保持纯文本，不在本次一并改造，避免扩大改动面。
- 私有 / 置顶图标保留，帮助识别这条 memo 的来源与状态。
- "已删除"的判定放在部件层（读 `MemoEntity.isDeleted` / `archived`），不新增 DAO/仓库方法，也不改 schema，保持改动面在 `widget/` 与 `util/` 内。
- 配置页搜索只在已加载的最近 200 条里过滤，不做全量/服务器搜索，保证配置页打开速度。

## 验收

1. `.\gradlew.bat :app:testDebugUnitTest` 通过。
2. 在桌面添加小部件 → 输入内容创建并绑定 → 部件立即显示这条 memo。
3. 选择已有 memo 绑定，同样生效。
4. 复选框显示为勾选框；点一下，部件与 App 内该 memo 都显示为已勾选；断网时同理，恢复网络后同步成功。
5. 在 App 里改这条 memo，回到桌面部件内容同步更新。
6. 删除该 memo，部件显示"已删除"提示，点击可重新选择。
7. 拉伸到不同尺寸，内容按空间裁切，不出现滚动条。
8. 现有两个小部件行为不变。
