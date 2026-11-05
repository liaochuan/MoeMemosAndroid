# MoeMemosAndroid（liaocl 分支）

Kotlin + Jetpack Compose 的 memos 客户端，单 `:app` 模块。支持自建 memos 服务器，也支持完全本地使用（无服务器）。

## 身份标识

| 项 | 值 | 说明 |
|---|---|---|
| `applicationId` | `top.liaocl.moememos` | 安装身份，与本仓库上游原版 `me.mudkip.moememos` 互不冲突，可同时安装 |
| `namespace` / Kotlin 包名 | `me.mudkip.moememos` | **不要改**。改了要动 150 个源文件的目录与 700+ 处引用，同步上游时 diff 会变成灾难 |
| FileProvider authority | `${applicationId}.fileprovider` | 代码用 `context.packageName + ".fileprovider"` 拼，两者必须一致 |
| 快捷方式 | `res/xml/shortcuts.xml` 的 `targetPackage` | 必须等于 `applicationId`；`targetClass` 仍是 `me.mudkip.moememos.MainActivity` |

## 环境与工具链

- JDK 17：`E:\Code\SDK\jdk\corretto-17.0.17`
- Android SDK：`E:\Code\SDK\android`（`local.properties` 里写 `sdk.dir`，该文件已被 gitignore）
- Gradle：用仓库自带 wrapper（9.7.1）；AGP 9.4.0 要求 Gradle ≥ 9.6，不要换成本机的旧版本
- `compileSdk` / `targetSdk` 37，`minSdk` 26，NDK `28.2.13676358`
- 本机执行任何 Gradle 命令都要显式指定 JDK，否则会用错版本：

```powershell
$env:JAVA_HOME="E:\Code\SDK\jdk\corretto-17.0.17"
$env:ANDROID_HOME="E:\Code\SDK\android"
.\gradlew.bat <task> "-Dorg.gradle.java.home=E:\Code\SDK\jdk\corretto-17.0.17" "-Dorg.gradle.java.installations.auto-download=false"
```

`-D` 参数必须整体加引号，否则 PowerShell 会把 `.gradle...` 当成任务名。

## 构建与测试

Windows 上用 `.\gradlew.bat`；`.justfile` 里写的是 `./gradlew`，只在 POSIX shell 下可用。

| 目的 | 命令 |
|---|---|
| 单元测试（纯 JVM） | `.\gradlew.bat :app:testDebugUnitTest` |
| 单个测试类 | `.\gradlew.bat :app:testDebugUnitTest --tests "me.mudkip.moememos.util.MemoChecklistTest"` |
| 测试报告 | `app/build/test-results/testDebugUnitTest/*.xml`（`tests=` / `failures=`） |
| 仪器测试（需设备） | `.\gradlew.bat :app:connectedDebugAndroidTest` |
| Debug APK | `.\gradlew.bat :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| Lint | `.\gradlew.bat lint`（`build` 会连带跑 lint，`-x lint` 可跳过） |
| 打包 arm64 release | `.\scripts\build-release-arm64.ps1` |

**本机特例**：全局 Gradle init 脚本 `C:\Users\22797\.gradle\init.d\mirrors.gradle` 会把镜像仓库注入成 project 仓库，与 `settings.gradle` 的 `FAIL_ON_PROJECT_REPOS` 冲突，构建会直接失败。加 `-PpreferSettingsRepos` 即可绕过（默认值仍是严格模式，CI 不受影响）。打包脚本已内置该参数。

单元测试不能碰 Android API（没有 Robolectric）：涉及 `Context`、Room、Compose、`Uri` 的一律放 `app/src/androidTest`。仪器测试用 `java.lang.reflect.Proxy` 伪造 Retrofit 接口，不引 mocking 库。

## 目录与架构

```
app/src/main/java/me/mudkip/moememos/
├── data/          api（Retrofit）、local（Room）、repository、service、model
├── ui/            page（Compose 页面）、component、theme、util
├── viewmodel/
├── widget/        Glance 小部件 + WorkManager + WidgetEntryPoint（Hilt）
└── ext/           扩展函数（含 R.string.foo.string）
```

- 分层：`ui/page/**` → `viewmodel/**` → `data/service/{MemoService,AccountService}` → `data/repository/*` → Room / Retrofit。
- `AccountService` 持有当前 `AbstractMemoRepository`，切换账户时替换实现（`LocalDatabaseRepository` / 包在 `SyncingRepository` 里的 `MemosV0Repository`、`MemosV1Repository`）。取仓库统一用 `MemoService.getRepository()`，不要直接注入具体实现。
- 同时支持 Memos 0.21.0+（V0 REST）与 0.27.0–0.31.0（V1 JSON REST），版本判定在 `AccountService.evaluateVersionPolicy` + `MemosVersionSupport`，不要假设只有一种 API 形态。
- 离线优先：Room（`MoeMemosDatabase`，库名 `moememos_database_localfirst`）是唯一数据源；本地改动置 `needsSync`，同步按 `lastModified` / `lastSyncedAt` 做比较并写（compare-and-set）。同步期间的编辑不能被覆盖，保留现有的条件写模式。
- Room schema JSON 在 `app/schemas/`，由 KSP 生成并提交。改实体必须：升 `@Database` version、加 `Migration`（禁止 destructive fallback）、补迁移测试；`androidTest` 会从 assets 读旧 schema，**不要删历史 schema 文件**。
- 账户与设置存 DataStore `settings_v3.json`；token 在 DataStore 里故意留空，由 `SecureTokenStorage`（AndroidKeyStore AES-GCM）加密保存，别改回明文。
- 所有 memo/资源都以 `accountKey`（`local` 或 `memos:<host>:<remoteIdentifier>`）分区；附件在 `filesDir/resources/<base64url(accountKey)>`。
- 导航：根路由在 `RouteName` + `ui/page/common/Navigation.kt`，抽屉内层栈在 `ui/page/memos/MemosNavigation.kt`，两者共用 `MemosNavHost`。
- 小部件是 Glance + WorkManager，通过 `widget/WidgetEntryPoint.kt` 拿 Hilt 依赖；统一刷新入口是 `WidgetUpdateScheduler.updateAllWidgets`。
- 用户可见文案进 `app/src/main/res/values/strings.xml`，并同步 `values-zh-rCN`、`values-zh-rTW`；非 Compose 环境用 `R.string.foo.string`；不翻译的字符串标 `translatable="false"`。
- API 37+ 访问局域网服务器需要 `ACCESS_LOCAL_NETWORK` 运行时权限，申请与重试逻辑在 `Navigation.kt`。

## 代码约定

- 依赖版本集中在根 `build.gradle` 的 `ext {}`，新增库走这里，不要在 `app/build.gradle` 里写死版本
- 代码风格是 Kotlin 官方风格（`.idea/codeStyles/Project.xml`），仓库没有 ktlint / spotless / detekt，别引入
- 新增用户可见字符串必须同时补 `values`、`values-zh-rCN`、`values-zh-rTW`；其余语言允许回退英文
- 新增/修改 Room 实体按上文迁移流程走，schema 文件只增不删
- 不要给 debug 变体加 `applicationIdSuffix`：FileProvider authority、快捷方式 `targetPackage`、小部件配置都直接依赖 `applicationId`，加后缀会让调试包与正式包的数据、权限路径分叉，也让打包脚本的 ABI/签名校验失去参考意义

## 打包与发布

### 本地打包（arm64-v8a 单 ABI）

```powershell
.\scripts\build-release-arm64.ps1
# 可选：-OutDir D:\apk
```

- 产物：`dist/moememos-<versionName>-arm64-v8a.apk`（`dist/` 已 gitignore）
- 脚本会校验 APK 里只有 `arm64-v8a`，出现其它 ABI 直接失败；随后复制产物并打印大小与 SHA256
- 版本号取自 `app/build.gradle` 的 `versionCode` / `versionName`，脚本不改仓库文件
- 签名与工具链按此顺序解析：**脚本参数 > 环境变量（`ANDROID_SIGNING_*` / `JAVA_HOME` / `ANDROID_HOME`）> `scripts/signing.local.json`**
- `scripts/signing.local.json` 存 keystore 路径与密码，**已被 gitignore，永远不要提交**；新环境从 `scripts/signing.local.example.json` 复制一份填写
- 单 ABI 依赖 `app/build.gradle` 的可选属性 `-PabiFilter=arm64-v8a`；不传该属性就是 universal APK（CI 行为不变）

### 版本与 CI

- 发版先改 `app/build.gradle` 的 `versionCode` / `versionName`，并补 `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
- 推 `X.Y.Z`（或 `-alpha.N` / `-beta.N`）标签会触发 `.github/workflows/build-signed-release-apk.yml`：用 `ANDROID_SIGNING_*` 签名、产出 universal APK、发 GitHub Release
- 本地缺签名变量时 `assembleRelease` 仍可跑，只是未签名

## 分支与提交

- `main`：只跟踪上游 `origin/main`，**不要直接提交**，否则之后 sync fork 或提 PR 会产生大量冲突
- `liaocl`：本地所有改动（功能、打包脚本、包名）都在这里，提 PR 也从这里开
- 需要新开实验分支时从 `liaocl` 切，命名 `feat/<主题>` / `fix/<主题>`
- 提交信息用 Conventional Commits：`feat:` / `fix:` / `chore:` / `docs:` / `refactor:`；首行英文简洁，正文可中文，写清"为什么"
- 提交前检查 `git status`，只 `git add` 明确路径；以下内容不得入库：`local.properties`、`dist/`、`scripts/signing.local.json`、`.superpowers/`、构建产物
- 上游 PR 注意：不要改 Kotlin 包名 / `namespace`，不要在无关文件里顺手格式化

## README 维护

- `README.md` 顶部的「相对上游的修改点」列表是本分支的对外说明，必须随改动同步更新
- 凡是新增或调整了相对上游的行为，都要在同一个提交里补一条：新功能、`applicationId` / 清单变化、构建与打包脚本、依赖或仓库配置、默认行为调整
- 只影响内部实现、重构、修 bug 且不改变对外行为的，可以不写；拿不准时按"需要写"处理
- 条目描述用户能感知的结果，不要罗列文件清单；上游同步（合并 `main`）不属于本分支的修改点
- 修改点较多或已被上游吸收时，同步整理列表，避免与上游实际差异脱节

## 已知坑

1. **Windows PowerShell 5.1 读 `.ps1` 按 ANSI 解码**：脚本里有中文就必须存成 UTF-8 **带 BOM**，否则解析报 `字符串缺少终止符`。
2. 全局 Gradle init 镜像脚本与 `FAIL_ON_PROJECT_REPOS` 冲突 → 构建加 `-PpreferSettingsRepos`。
3. 改 `applicationId` 时必须同步 FileProvider authority 与 `shortcuts.xml` 的 `targetPackage`，否则分享图片崩溃、桌面快捷方式失效。
4. 已删除（`isDeleted`）的 memo 不能再走 `updateMemo` 写回，否则会把已删除记录"复活"。
5. 小部件里的点击写回要用 `ActionCallback`，不要用已废弃的进程内 lambda action。
