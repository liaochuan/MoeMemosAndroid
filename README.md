# Moe Memos

[![justforfunnoreally.dev badge](https://img.shields.io/badge/justforfunnoreally-dev-9ff)](https://justforfunnoreally.dev)

<img alt="Moe Memos" src="https://memos.moe/memos.png" width="160" height="160" />

**Moe Memos** is an app to help you capture thoughts and ideas.

You can use Moe Memos with either a self-hosted [✍️memos](https://github.com/usememos/memos) server or locally on your device (no server required).

**Note: Current Moe Memos version supports Memos 0.21.0, Memos 0.27.0 to 0.31.0, and Memos 26.10 to 26.11. Memos update may introduce breaking API changes. If you are using an unsupported version, it is recommended to use [Mortis](https://github.com/mudkipme/mortis) to convert the newer Memos API to the Memos 0.21.0 API and re-login in Moe Memos.**

> [!IMPORTANT]
> 当前 `liaocl` 分支是基于上游 [mudkipme/MoeMemosAndroid](https://github.com/mudkipme/MoeMemosAndroid) 的**自定义修改分支**，并非上游官方版本。
> 本分支会不定时同步上游的提交，因此上游功能与本文档其余内容仍然适用；下列修改点仅存在于本分支。

## 相对上游的修改点

- **应用标识改为 `top.liaocl.moememos`**：可与上游原版（`me.mudkip.moememos`）同时安装，两者数据互不干扰。Kotlin 包名与 `namespace` 保持不变，FileProvider authority 与快捷方式 `targetPackage` 已同步调整。
- **新增「单条 Memo」桌面小部件**：创建时绑定单条 memo（可新建或从列表中选择），在桌面上展示正文并把 `- [ ]` / `- [x]` 渲染为真正的勾选框，点击即可写回该 memo；支持置顶/私有标记、缺失 memo 提示、按尺寸裁剪内容与定期刷新。
- **新增 checklist 解析与切换工具**（`MemoChecklist`）：负责解析 memo 中的复选框行、按字符区间切换勾选状态，并附有单元测试。
- **memo 三点菜单新增「清除已完成待办」**：仅当该 memo 存在已勾选的复选框时显示，确认后一次性删除这些已完成的行并走正常同步；代码块中的示例复选框不受影响。
- **新增 arm64-v8a 单 ABI 打包脚本** `scripts/build-release-arm64.ps1`：产出 `dist/moememos-<versionName>-arm64-v8a.apk`，会校验 APK 内只含 arm64-v8a；签名信息由环境变量或本地 `scripts/signing.local.json` 提供（该文件不入库）。
- **构建适配**：`app/build.gradle` 支持可选的 `-PabiFilter` 单 ABI 构建；`settings.gradle` 支持 `-PpreferSettingsRepos`，用于绕过本机 Gradle init 镜像脚本与 `FAIL_ON_PROJECT_REPOS` 的冲突（默认仍为严格模式，CI 不受影响）。
- **`.gitignore` 补充**：忽略本地签名配置与 `dist/` 打包产物。

## 同步与上游贡献

- 本分支会不定时合并上游 `main`，合并后可能重新生成本文档中的差异列表。
- 这些修改主要由 AI 辅助开发，**尚未经过完整的代码审查**，因此不会向上游提交 Pull Request，以免污染上游代码库。如需使用稳定版本，请优先选择上游官方仓库或应用商店版本。

## Upstream notice

This `liaocl` branch is a custom fork of [mudkipme/MoeMemosAndroid](https://github.com/mudkipme/MoeMemosAndroid). It is synced with upstream from time to time. The changes listed above are maintained here only and are not proposed upstream, as they were largely AI-assisted and have not gone through a full code review.

## Installation

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.mudkip.moememos/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=me.mudkip.moememos)

Or download and install the APK package from the [Releases section](https://github.com/mudkipme/MoeMemosAndroid/releases/latest).

## Features

- Write memos like tweeting to yourself
- Use Moe Memos locally on your device (with backup and restore) or sync with your own ✍️memos server
- Offline-first experience with automatic sync when you are back online
- Material You design with dynamic themes and themed icon
- Rich memo content: Markdown editor and renderer, images, non-image attachments, and to-do items
- Organize and find memos with tags, pinning, and search
- Home screen widget and share sheet integration (text, images, and webpages)
- View your memo activity with a progress graph
- Full privacy protection, no data collection

Moe Memos is a third-party client for [✍️memos](https://github.com/usememos/memos) and both projects aren't affiliated with each other.

## Development

Moe Memos tends to keep minimal and optimized for best native experience. The Android version is developed with Kotlin and Jetpack Compose.

Any contributions are greatly appreciated.

## License

The Android version of Moe Memos is under [GPLv3](LICENSE).
