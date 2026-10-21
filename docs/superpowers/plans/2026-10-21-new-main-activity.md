# NewMainActivity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让独立的 NewMainActivity 接管 HOME，保留 CleanMyTV 顶部并提供 MiFlow 风格应用网格、置顶和移动。

**Architecture:** 新页独立持有应用扫描、顺序存储和网格显示；只复用现有资源、Config、LibraryApps 和 AppLibraryDialog，不改旧页的 dock 与 order.txt。Manifest 只把 HOME/电视启动入口转给新页，旧页仍可显式启动。

**Tech Stack:** Java 17、Android 原生 View、AndroidX RecyclerView、SharedPreferences、JUnit 4；沿用项目 compileSdk/targetSdk 34 和 minSdk 26。

**Spec:** `docs/superpowers/specs/2026-10-21-new-main-activity-design.md`

## Global Constraints

- 沿用现有 `launcherApplicationId`，不修改包名、无障碍服务或电视系统桌面状态。
- 顶部时钟、日期、问候语、按钮外观与单击用途保留；新页不提供旧 Settings 长按菜单。
- 中间宽屏 6 列、窄屏 4 列；置顶在前，移动不可跨置顶组。
- 新旧顺序数据分离；旧 MainActivity 保持显式可启动。

## Review Focus

- 保存的顺序里有重复或已卸载组件：恢复时只显示现存组件一次（Task 1 测试）。
- 安装新应用后刷新：已有顺序不变，新组件追加到对应组（Task 1 测试）。
- 置顶应用卸载后：置顶集合移除无效组件，计数正确（Task 1 测试）。
- 移动到组边界：卡片不得跨越置顶/普通分界（Task 1 测试）。
- 移动多步后按返回：恢复进入移动前的完整顺序（Task 1 测试）。

---

### Task 1: 应用顺序与置顶模型

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/NewHomeOrder.java`
- Create: `launcher/app/src/main/java/com/gathin/tvhome/NewHomeStore.java`
- Test: `launcher/app/src/test/java/com/gathin/tvhome/NewHomeOrderTest.java`

**Interfaces:**
- Produces: `NewHomeOrder.reconcile(List<String> installed, List<String> saved, Set<String> pinned): List<String>`；`NewHomeOrder.move(List<String> order, String id, int delta, Set<String> pinned): List<String>`；`NewHomeOrder.livePins(Set<String> pinned, List<String> installed): Set<String>`；`NewHomeStore(Context)` 的 `order()`、`saveOrder(List<String>)`、`pins()`、`savePins(Set<String>)`。

- [ ] **Step 1: Write failing tests** — `NewHomeOrderTest` 分别断言首次排序、重复/卸载过滤、新应用追加、置顶分组、卸载后有效置顶集合、跨组移动不变、移动多步时输入快照不变、返回可恢复该快照。
- [ ] **Step 2: Run focused test** — `launcher\gradlew.bat :app:testDebugUnitTest --tests com.gathin.tvhome.NewHomeOrderTest`；预期因类未定义失败。
- [ ] **Step 3: Implement the model** — 实现上述三个静态方法；存储键使用独立 `new_home` SharedPreferences 的 `order` 和 `pins`，不改 `Order.java`。
- [ ] **Step 4: Run focused test** — 同 Step 2 命令，预期全部 PASS。
- [ ] **Step 5: Commit** — 只提交 Task 1 文件。

### Task 2: 应用扫描与新卡片

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/NewHomeApps.java`
- Create: `launcher/app/src/main/java/com/gathin/tvhome/NewAppAdapter.java`
- Create: `launcher/app/src/main/res/layout/item_new_app.xml`
- Create: `launcher/app/src/main/res/drawable/new_app_glass.xml`
- Create: `launcher/app/src/main/res/drawable/new_app_glass_focused.xml`

**Interfaces:**
- Consumes: Task 1 的组件 ID 顺序和置顶集合。
- Produces: `NewHomeApps.load(Context): List<Entry>`，其中 `Entry` 含 `id`、`packageName`、`label`、`component`、`icon`；`NewAppAdapter` 接收有序 Entry 列表及焦点/点击/长按回调。

- [ ] **Step 1: Implement scanner** — 合并 LEANBACK_LAUNCHER/LAUNCHER，组件去重，排除自身及不可启动项；标签按当前语言排序。
- [ ] **Step 2: Implement card adapter/resources** — 图标、名称、置顶标记和 MiFlow 玻璃焦点态；使用 RecyclerView 稳定 ID 或组件 ID 恢复焦点。
- [ ] **Step 3: Verify compile** — `launcher\gradlew.bat :app:compileDebugJavaWithJavac`，预期 BUILD SUCCESSFUL。
- [ ] **Step 4: Commit** — 只提交 Task 2 文件。

### Task 3: 新首页布局、顶部与编辑操作

**Files:**
- Create: `launcher/app/src/main/res/layout/activity_new_main.xml`
- Create: `launcher/app/src/main/java/com/gathin/tvhome/NewMainActivity.java`
- Modify: `launcher/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: Tasks 1–2 的存储、扫描和卡片列表；现有 `Config`、`LibraryApps`、`AppLibraryDialog`、顶部样式与 `popup_inputs`。
- Produces: `NewMainActivity` 的焦点导航、应用启动、编辑菜单、置顶/移动状态机以及顶部按钮单击行为。

- [ ] **Step 1: Build layout** — 复制现有顶部的尺寸/资源，加入标题、计数、说明和 6/4 列 RecyclerView；保持适配窄屏滚动。
- [ ] **Step 2: Wire existing top actions** — Inputs、Wi-Fi、Settings、Library 的单击用途与当前页一致；Reorder 进入新网格移动选择；不添加 Settings 长按监听。
- [ ] **Step 3: Wire card actions** — OK 启动，长按 OK/Menu 弹出置顶/移动/应用信息；移动时方向键同组换位、OK 保存、Back 恢复快照；在 onResume 和包变化广播后刷新并保留焦点。
- [ ] **Step 4: Verify compile and behavior hooks** — `launcher\gradlew.bat :app:compileDebugJavaWithJavac` 通过，并检查对应监听、返回处理与存储调用。
- [ ] **Step 5: Commit** — 只提交 Task 3 文件。

### Task 4: HOME 接管与回归

**Files:**
- Modify: `launcher/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 3 的 `NewMainActivity`。
- Produces: 标准 HOME/DEFAULT 与 LEANBACK_LAUNCHER 都指向新页；旧页可通过显式组件启动。

- [ ] **Step 1: Transfer intent filters** — 沿用旧页 HOME 的 activity 属性，移走旧页两个启动过滤器，只给新页。
- [ ] **Step 2: Run regression checks** — `launcher\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` 通过；检查合并 Manifest 中 HOME 只出现于新页。
- [ ] **Step 3: Device check when available** — 若 ADB 在线，安装 debug APK 并实际检查 Home、焦点、启动、置顶、移动和取消；无设备则记录待真机验证，不声称已验证。
- [ ] **Step 4: Commit** — 只提交 Task 4 文件，并确认 `git status --short` 仅有原先无关的 `.idea` 文件。

