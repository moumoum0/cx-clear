---
paths:
  - "gui/src/**"
---

# GUI 约定

- UI 只用 `androidx.compose.material3.*`，不要引入 M2 `androidx.compose.material.*`（图标 `material.icons.*` 可用）。
- 不硬塞小字（如「已选 X」「未选」「统计中…」）；进行中优先显示动态数据，别写「正在……」空话。
- 有稳定结构的空态用骨架占位（与有数据时同形）；加载中用进度指示。
- 动效时长与缓动统一取 `ui/theme/Motion.kt`，别在组件里自写毫秒数。扫描页的节拍依赖 `components/FlipBytesText.kt` 的 `FlipMs`。
- 跨整窗（含标题栏）的浮层挂到 `OverlayHost.kt`，挂在页面里盖不住标题栏。
- 界面里列工具一律用 `components/ToolIcon.kt` 的 `ToolEntries`（从 `tools()` 派生）。
- 扫描页分类 `components/ScanModels.kt` 的 `buildCategories` 只影响展示，不定义可删除的路径。它靠 `target.id` 子串关键字（`plugins` / `downloads` / `sandbox` / `vendor` / `extension` / `cached` / `runtime`）分到「插件与安装缓存」桶；新 `id` 要能进对的桶。
